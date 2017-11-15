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

package whisk.core.loadBalancer.test

import akka.actor.ActorSystem
import akka.actor.Address
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.ClusterEvent.UnreachableMember
import akka.cluster.Member
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import common.StreamLogging
import org.junit.runner.RunWith
import org.mockito.Mockito._
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatest.mockito.MockitoSugar
import scala.concurrent.Future
import whisk.core.WhiskConfig
import whisk.core.entity.ExecManifest
import whisk.core.loadBalancer.StaticSeedNodesProvider
import whisk.core.mesos.MarathonSeedNodes
import whisk.core.mesos.MarathonSeedNodesProvider

object SeedNodesTestConfig {
  val config =
    """
      akka.cluster {
        name = "testcluster"
      }
      whisk.cluster.discovery {
        marathon-app-url = "http://192.168.99.100:8080/v2/apps/myapp/tasks"
        marathon-check-interval-seconds = 1
      }
    """
}
@RunWith(classOf[JUnitRunner])
class SeedNodesProviderTest
    extends TestKit(ActorSystem("SeedNodesTest", ConfigFactory.parseString(SeedNodesTestConfig.config)))
    with FlatSpecLike
    with Matchers
    with StreamLogging
    with MockitoSugar {

  val host = "192.168.99.100"
  val port = 8000
  val oneFakeSeedNode = s"$host:$port"
  val twoFakeSeedNodes = s"$host:$port $host:${port + 1}"
  val twoFakeSeedNodesWithoutWhitespace = s"$host:$port$host:${port + 1}"

  behavior of "StaticSeedNodesProvider"

  it should "return a sequence with a single seed node" in {
    val seqWithOneSeedNode =
      StaticSeedNodesProvider.seedNodes(new WhiskConfig(Map("akka.cluster.seed.nodes" -> oneFakeSeedNode)), system)
    seqWithOneSeedNode shouldBe IndexedSeq(Address("akka.tcp", system.name, host, port))
  }
  it should "return a sequence with more then one seed node" in {
    val seqWithTwoSeedNodes =
      StaticSeedNodesProvider.seedNodes(new WhiskConfig(Map("akka.cluster.seed.nodes" -> twoFakeSeedNodes)), system)
    seqWithTwoSeedNodes shouldBe IndexedSeq(
      Address("akka.tcp", system.name, host, port),
      Address("akka.tcp", system.name, host, port + 1))
  }
  it should "return an empty sequence if seed nodes are provided in the wrong format" in {
    val noneResponse = StaticSeedNodesProvider.seedNodes(
      new WhiskConfig(Map("akka.cluster.seed.nodes" -> twoFakeSeedNodesWithoutWhitespace)),
      system)
    noneResponse shouldBe IndexedSeq.empty[Address]
  }
  it should "return an empty sequence if no seed nodes specified" in {
    val noneResponse = StaticSeedNodesProvider.seedNodes(new WhiskConfig(Map("akka.cluster.seed.nodes" -> "")), system)
    noneResponse shouldBe IndexedSeq.empty[Address]
  }

  behavior of "MarathonSeedNodesProvider"

  it should "return addresses of tasks in TASK_RUNNING state that are healthy" in {
    val config = new WhiskConfig(ExecManifest.requiredProperties)

    implicit val mat = ActorMaterializer()
    implicit val ec = system.dispatcher
    val seedNodes = new MarathonSeedNodes(req => {

      req.getUri().toString shouldBe "http://192.168.99.100:8080/v2/apps/myapp/tasks"
      Future.successful(
        HttpResponse(
          StatusCodes.OK,
          entity = HttpEntity(
            ContentTypes.`application/json`,
            """{"tasks":[{"id":"myapp.62c47071-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S0","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.892Z","stagedAt":"2017-11-13T17:16:28.579Z","ports":[11064],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":2,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:02.712Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c47071-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.946Z","stagedAt":"2017-11-13T17:16:28.549Z","ports":[11285],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.728Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.591Z","stagedAt":"2017-11-13T19:19:12.294Z","ports":[11170],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.4","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.786Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.821Z","lastFailureCause":null,"taskId":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.62c64532-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.966Z","stagedAt":"2017-11-13T17:16:28.591Z","ports":[11319],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.739Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c64532-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.600Z","stagedAt":"2017-11-13T19:19:12.226Z","ports":[11089],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.3","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.772Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.815Z","lastFailureCause":null,"taskId":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:13.829Z","stagedAt":"2017-11-13T19:19:12.309Z","ports":[11199],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.2","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.787Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.809Z","lastFailureCause":null,"taskId":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6"}]}]}""")))

    }).seedNodes(config, system)

    seedNodes shouldBe List(
      Address("akka.tcp", "testcluster", "192.168.99.100", 11170),
      Address("akka.tcp", "testcluster", "192.168.99.100", 11089),
      Address("akka.tcp", "testcluster", "192.168.99.100", 11199))

  }

  it should "retry while there are unhealthy tasks" in {
    val config = new WhiskConfig(ExecManifest.requiredProperties)

    implicit val mat = ActorMaterializer()
    implicit val ec = system.dispatcher
    var count = 0
    val seedNodes = new MarathonSeedNodes(req => {
      req.getUri().toString shouldBe "http://192.168.99.100:8080/v2/apps/myapp/tasks"

      count += 1
      if (count < 2) {
        //return 1 unhealthy
        Future.successful(
          HttpResponse(
            StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              """{"tasks":[{"id":"myapp.62c47071-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S0","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.892Z","stagedAt":"2017-11-13T17:16:28.579Z","ports":[11064],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":2,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:02.712Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c47071-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.946Z","stagedAt":"2017-11-13T17:16:28.549Z","ports":[11285],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.728Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.591Z","stagedAt":"2017-11-13T19:19:12.294Z","ports":[11170],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.4","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.786Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.821Z","lastFailureCause":null,"taskId":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.62c64532-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.966Z","stagedAt":"2017-11-13T17:16:28.591Z","ports":[11319],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.739Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c64532-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.600Z","stagedAt":"2017-11-13T19:19:12.226Z","ports":[11089],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.3","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.772Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.815Z","lastFailureCause":null,"taskId":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:13.829Z","stagedAt":"2017-11-13T19:19:12.309Z","ports":[11199],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.2","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.787Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.809Z","lastFailureCause":null,"taskId":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6"}]}]}""")))

      } else {
        //return all healthy
        Future.successful(
          HttpResponse(
            StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              """{"tasks":[{"id":"myapp.62c47071-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S0","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.892Z","stagedAt":"2017-11-13T17:16:28.579Z","ports":[11064],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":2,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:02.712Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c47071-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.946Z","stagedAt":"2017-11-13T17:16:28.549Z","ports":[11285],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.728Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62b220f0-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.591Z","stagedAt":"2017-11-13T19:19:12.294Z","ports":[11170],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.4","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.786Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.821Z","lastFailureCause":null,"taskId":"myapp.87e1b876-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.62c64532-c896-11e7-830b-c621fc75cd87","slaveId":"cddb218f-ef25-4833-a27e-05f33ac3c1cf-S1","host":"192.168.99.100","state":"TASK_LOST","startedAt":"2017-11-13T17:16:29.966Z","stagedAt":"2017-11-13T17:16:28.591Z","ports":[11319],"version":"2017-11-11T01:11:56.467Z","appId":"/myapp","healthCheckResults":[{"alive":false,"consecutiveFailures":3,"firstSuccess":null,"lastFailure":"2017-11-13T19:19:07.739Z","lastSuccess":null,"lastFailureCause":"ConnectException: Connection refused","taskId":"myapp.62c64532-c896-11e7-830b-c621fc75cd87"}]},{"id":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:14.600Z","stagedAt":"2017-11-13T19:19:12.226Z","ports":[11089],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.3","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.772Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.815Z","lastFailureCause":null,"taskId":"myapp.87c04dc5-c8a7-11e7-88f7-426b1ccedef6"}]},{"id":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6","slaveId":"b15cce76-769e-4f94-9dda-67217ab628cc-S0","host":"192.168.99.100","state":"TASK_RUNNING","startedAt":"2017-11-13T19:19:13.829Z","stagedAt":"2017-11-13T19:19:12.309Z","ports":[11199],"version":"2017-11-11T01:11:56.467Z","ipAddresses":[{"ipAddress":"172.17.0.2","protocol":"IPv4"}],"appId":"/myapp","healthCheckResults":[{"alive":true,"consecutiveFailures":0,"firstSuccess":"2017-11-13T19:19:22.787Z","lastFailure":null,"lastSuccess":"2017-11-13T19:19:32.809Z","lastFailureCause":null,"taskId":"myapp.87e42977-c8a7-11e7-88f7-426b1ccedef6"}]}]}""")))

      }

    }).seedNodes(config, system)

    count shouldBe 2
    seedNodes shouldBe List(
      Address("akka.tcp", "testcluster", "192.168.99.100", 11170),
      Address("akka.tcp", "testcluster", "192.168.99.100", 11089),
      Address("akka.tcp", "testcluster", "192.168.99.100", 11199))

  }

  it should "down a cluster node that becomes unreachable" in {

    val testCluster = mock[Cluster]
    val address = Address("akka.tcp", "sys", "a", 2552)
    val leaderAddress = Address("akka.tcp", "sys", "b", 2552)
    val listener = MarathonSeedNodesProvider.clusterListener(system, testCluster)
    val m = mock[Member]
    //setup mock interceptions
    //testCluster sees itself as leader
    when(testCluster.selfAddress).thenReturn(leaderAddress)
    //member that is unreachable is some other address
    when(m.address).thenReturn(address)

    //verify cluster interactions
    listener ! LeaderChanged(Some(leaderAddress))
    verify(testCluster).subscribe(listener, classOf[UnreachableMember], classOf[LeaderChanged])
    //signal the node is down
    listener ! UnreachableMember(m)

    //verify that Cluster.down() is invoked
    verify(testCluster, timeout(1000)).down(address)
  }
}
