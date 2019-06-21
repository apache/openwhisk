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
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.InvokerInstanceId
import pureconfig.loadConfig

import scala.concurrent.ExecutionContext

/**
 * This factory provides a Docker for Mac client which exposes action container's ports on the host.
 */
object DockerForWindowsContainerFactory extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {

    new DockerContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new DockerForWindowsClient()(actorSystem.dispatcher)(logging, actorSystem),
      new RuncClient()(actorSystem.dispatcher)(logging, actorSystem))
  }

}

trait WindowsDockerClient {
  self: DockerClient =>

  override protected def executableAlternatives: List[String] = {
    val executable = loadConfig[String]("whisk.docker.executable").map(Some(_)).getOrElse(None)
    List(Some("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe"), executable).flatten
  }
}

class DockerForWindowsClient(dockerHost: Option[String] = None)(executionContext: ExecutionContext)(
  implicit log: Logging,
  as: ActorSystem)
    extends DockerForMacClient(dockerHost)(executionContext)
    with WindowsDockerClient {
  //Due to some Docker + Windows + Go parsing quirks need to add double quotes around whole command
  //See https://github.com/moby/moby/issues/27592#issuecomment-255227097
  override def inspectCommand: String = "\"{{(index (index .NetworkSettings.Ports \\\"8080/tcp\\\") 0).HostPort}}\""
}
