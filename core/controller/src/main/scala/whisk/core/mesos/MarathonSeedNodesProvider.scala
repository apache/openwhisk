/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.mesos

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.ReachableMember
import akka.cluster.ClusterEvent.UnreachableMember
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import spray.json.DefaultJsonProtocol
import whisk.core.WhiskConfig
import whisk.core.loadBalancer.SeedNodesProvider

/**
 * A Mesos+Marathon based SeedNodesProvider that will
 * - block response to seedNodes() until
 *    - all instances of a particular marathon app are in state TASK_RUNNING
 *    - all instances have passing healthchecks
 * */
object MarathonSeedNodesProvider extends SeedNodesProvider {
  var leaderOption: Option[Address] = None
  var pendingDown = Set[Address]()
  def seedNodes(whiskConfig: WhiskConfig, actorSystem: ActorSystem): Seq[Address] = {
    implicit val as = actorSystem
    implicit val ec = actorSystem.dispatcher
    implicit val mat = ActorMaterializer()
    //setup the cluster listener, to down nodes that become unreachable
    val cluster = Cluster.get(actorSystem)
    val log = actorSystem.log
    clusterListener(actorSystem, cluster)
    new MarathonSeedNodes(Http().singleRequest(_)).seedNodes(whiskConfig, actorSystem)
  }

  def clusterListener(actorSystem: ActorSystem, cluster: Cluster): ActorRef = {

    val listener = actorSystem.actorOf(Props(new Actor {
      override def receive: Receive = {
        case LeaderChanged(member) =>
          leaderOption = member
          actorSystem.log.info("Leader changed to {}", member)
          member.foreach(newLeader => {
            if (newLeader == cluster.selfAddress) {
              //down any nodes that were previously unreachable
              pendingDown.foreach(toDown => {
                actorSystem.log.info(s"downing previously unreachable ${toDown}")
                cluster.down(toDown)
              })
              //clear pendingDown
              pendingDown = Set()
            }
          })

        case UnreachableMember(member) =>
          context.system.log.info("Member detected as unreachable: {}", member)
          leaderOption match {
            case Some(leader) if leader == cluster.selfAddress =>
              require(leader != member.address, "node cannot down itself from cluster...")
              //TODO: verify down at marathon, then remove
              actorSystem.log.info("downing unreachable node: {}", member.address)
              cluster.down(member.address)
            case _ =>
              //tracking pending-down nodes, in case we become leader after a leader failure
              pendingDown += member.address
              actorSystem.log.info("non-leader ignoring unreachable node: {}", member.address)
          }
        case ReachableMember(member) =>
          //typeically this won't happen, since leader downs it immediately (for now)
          pendingDown -= member.address
        case MemberRemoved(member, _) =>
          //the leader has removed it, so we don't need to track it anymore
          pendingDown -= member.address
      }
    }))

    cluster.subscribe(listener, classOf[UnreachableMember], classOf[LeaderChanged])
    listener
  }
}

case class HealthCheckResult(alive: Boolean)
case class MarathonTask(id: String,
                        state: String,
                        host: String,
                        ports: List[Int],
                        healthCheckResults: List[HealthCheckResult])
case class MarathonTasks(tasks: List[MarathonTask])

trait MarathonJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val healthCheckFormat = jsonFormat1(HealthCheckResult.apply)
  implicit val taskFormat = jsonFormat5(MarathonTask.apply)
  implicit val tasksFormat = jsonFormat1(MarathonTasks.apply)
}

class MarathonSeedNodes(httpRequestor: (HttpRequest) => Future[HttpResponse])(implicit mat: ActorMaterializer,
                                                                              ec: ExecutionContext)
    extends Directives
    with MarathonJsonSupport {

  /**
   * Use Marathon API to discover other running tasks for this app.
   * Assumes that the OpenWhisk Controller is configured in marathon with a TCP health check to indicate liveness
   *
   * A task  may come as:
   *
   * {
          id: "akka-cluster.086db21b-7192-11e7-8203-0242ac107905",
          slaveId: "35f9af86-f5b0-4e95-a2b1-5f201b10fbaa-S0",
          host: "localhost",
          state: "TASK_RUNNING",
          startedAt: "2017-07-25T23:36:09.629Z",
          stagedAt: "2017-07-25T23:36:07.949Z",
          ports: [
            11696
          ],
          version: "2017-07-25T23:36:07.294Z",
          ipAddresses: [
            {
              ipAddress: "172.17.0.2",
              protocol: "IPv4"
            }
          ],
          appId: "/akka-cluster"
      }
   * This method extracts the host and the port of each task
   *
   * @return an array of strings with akka.tcp://{cluster-name}@{IP}:{PORT}
   */
  def seedNodes(whiskConfig: WhiskConfig, actorSystem: ActorSystem): Seq[Address] = {
    val config = actorSystem.settings.config
    val log = actorSystem.log
    val url: String = config.getString("whisk.cluster.discovery.marathon-app-url")
    val portIndex: Int = 0
    val clusterWaitInterval: Int = config.getInt("whisk.cluster.discovery.marathon-check-interval-seconds")

    log.info("loading seed nodes from {}", url)
    var taskData = parseTasks(httpRequestor(HttpRequest(uri = url)))
    var notHealthyTasks = notHealthy(taskData)

    while (notHealthyTasks.nonEmpty) {
      log.info(s"found ${notHealthyTasks.size} unhealthy tasks, will try again in ${clusterWaitInterval}s")

      Thread.sleep(clusterWaitInterval.seconds.toMillis)
      taskData = parseTasks(httpRequestor(HttpRequest(uri = url)))
      notHealthyTasks = notHealthy(taskData)

    }

    log.info(s"can start cluster now that all tasks are healthy")

    taskData.tasks
      .filter(_.state == "TASK_RUNNING")
      .map(t => Address("akka.tcp", actorSystem.name, t.host, t.ports(portIndex)))

  }

  private def notHealthy(taskData: MarathonTasks) =
    taskData.tasks.filter(
      t =>
        t.state == "TASK_STARTING" || t.state == "TASK_STAGING" || (t.state == "TASK_RUNNING" && t.healthCheckResults
          .exists(_.alive == false)))
  private def parseTasks(res: Future[HttpResponse]): MarathonTasks = {

    val tasks = res.flatMap(r => {
      if (r.status.isSuccess()) {
        Unmarshal(r.entity).to[MarathonTasks]
      } else {
        Unmarshal(r.entity).to[String]
        //cannot communicate with marathon, catastrophic failure
        throw new RuntimeException(s"failed to retrieve data from marathon ${r}")
      }
    })
    Await.result(tasks, 10.seconds)
  }
}
