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

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.InvokerInstanceId

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
      .runCmd(Seq("inspect", "--format", inspectCommand, id.asString), 10.seconds)
      .flatMap {
        case "<no value>" => Future.failed(new NoSuchElementException)
        case stdout       => Future.successful(ContainerAddress("localhost", stdout.toInt))
      }
  }

  def inspectCommand: String = """{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}"""

  //Pause unpause is causing issue on non Linux setups. So disable by default
  override def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = Future.successful(())

  override def unpause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] = Future.successful(())
}
