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

package org.apache.openwhisk.core.containerpool.docker

import java.time.Instant

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{AkkaLogging, Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.{DockerToActivationLogStore, LogStore, LogStoreProvider}
import org.apache.openwhisk.core.entity.{
  ActivationLogs,
  ExecutableWhiskAction,
  Identity,
  InvokerInstanceId,
  WhiskActivation
}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * This factory provides a Docker for Mac client which exposes action container's ports on the host.
 */
object DockerForMacContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {

    new DockerContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new DockerForMacClient()(actorSystem.dispatcher)(logging, actorSystem),
      new RuncClient()(actorSystem.dispatcher)(logging, actorSystem))
  }

}

class DockerForMacClient(dockerHost: Option[String] = None)(executionContext: ExecutionContext)(implicit log: Logging,
                                                                                                as: ActorSystem)
    extends DockerClientWithFileAccess(dockerHost)(executionContext)
    with DockerApiWithFileAccess {

  implicit private val ec: ExecutionContext = executionContext
  private val waitForLogs: FiniteDuration = 2.seconds
  private val logTimeSpanMargin = 1.second

  override def run(image: String, args: Seq[String] = Seq.empty[String])(
    implicit transid: TransactionId): Future[ContainerId] = {
    // b/c docker for mac doesn't have a routing to the action containers
    // the port 8080 is exposed on the host on a random port number
    val extraArgs: Seq[String] = Seq("-p", "0:8080") ++ args
    super.run(image, extraArgs)
  }
  // See extended trait for description
  override def inspectIPAddress(id: ContainerId, network: String)(
    implicit transid: TransactionId): Future[ContainerAddress] = {
    super
      .runCmd(
        Seq("inspect", "--format", """{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}""", id.asString),
        10.seconds)
      .flatMap {
        case "<no value>" => Future.failed(new NoSuchElementException)
        case stdout       => Future.successful(ContainerAddress("localhost", stdout.toInt))
      }
  }

  def collectLogs(id: ContainerId, since: Instant, untill: Instant)(implicit transid: TransactionId): Future[String] = {
    //Add a slight buffer to account for delay writes of logs
    val end = untill.plusSeconds(logTimeSpanMargin.toSeconds)
    runCmd(
      Seq("logs", id.asString, "--since", since.getEpochSecond.toString, "--until", end.getEpochSecond.toString),
      waitForLogs)
  }
}

object DockerForMacLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = {
    //Logger is currently not passed implicitly to LogStoreProvider. So create one explicitly
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    new DockerForMacLogStore(actorSystem)
  }
}

class DockerForMacLogStore(system: ActorSystem)(implicit log: Logging) extends DockerToActivationLogStore(system) {
  private val client = new DockerForMacClient()(system.dispatcher)(log, system)
  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {
    //TODO Lookup for Log markers to be more precise in log collection
    client
      .collectLogs(container.containerId, activation.start, activation.end)(transid)
      .map(logs => ActivationLogs(logs.linesIterator.toVector))
  }
}
