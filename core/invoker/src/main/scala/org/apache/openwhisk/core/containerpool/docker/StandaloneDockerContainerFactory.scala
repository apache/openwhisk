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
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.containerpool.{Container, ContainerFactory, ContainerFactoryProvider}
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object StandaloneDockerContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {
    val client =
      if (SystemUtils.IS_OS_MAC) new DockerForMacClient()(actorSystem.dispatcher)(logging, actorSystem)
      else if (SystemUtils.IS_OS_WINDOWS) new DockerForWindowsClient()(actorSystem.dispatcher)(logging, actorSystem)
      else new DockerClientWithFileAccess()(actorSystem.dispatcher)(logging, actorSystem)

    new StandaloneDockerContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      client,
      new RuncClient()(actorSystem.dispatcher)(logging, actorSystem))
  }
}

case class StandaloneDockerConfig(pullStandardImages: Boolean)

class StandaloneDockerContainerFactory(instance: InvokerInstanceId, parameters: Map[String, Set[String]])(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging,
  docker: DockerApiWithFileAccess,
  runc: RuncApi)
    extends DockerContainerFactory(instance, parameters) {
  private val pulledImages = new TrieMap[String, Boolean]()
  private val factoryConfig = loadConfigOrThrow[StandaloneDockerConfig](ConfigKeys.standaloneDockerContainerFactory)

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {

    //For standalone server usage we would also want to pull the OpenWhisk provided image so as to ensure if
    //local setup does not have the image then it pulls it down
    //For standard usage its expected that standard images have already been pulled in.
    val imageName = actionImage.resolveImageName(Some(runtimesRegistryConfig.url))
    val pulled =
      if (!userProvidedImage
          && factoryConfig.pullStandardImages
          && !pulledImages.contains(imageName)
          && actionImage.prefix.contains("openwhisk")) {
        docker.pull(imageName)(tid).map { _ =>
          logging.info(this, s"Pulled OpenWhisk provided image $imageName")
          pulledImages.put(imageName, true)
          true
        }
      } else Future.successful(true)

    pulled.flatMap(_ => super.createContainer(tid, name, actionImage, userProvidedImage, memory, cpuShares))
  }

  override def init(): Unit = {
    logging.info(
      this,
      s"Standalone docker container factory config pullStandardImages: ${factoryConfig.pullStandardImages}")
    super.init()
  }
}

trait WindowsDockerClient {
  self: DockerClient =>

  override protected def executableAlternatives: List[String] = {
    val executable = loadConfig[String]("whisk.docker.executable").toOption
    List("""C:\Program Files\Docker\Docker\resources\bin\docker.exe""") ++ executable
  }
}

class DockerForWindowsClient(dockerHost: Option[String] = None)(executionContext: ExecutionContext)(
  implicit log: Logging,
  as: ActorSystem)
    extends DockerForMacClient(dockerHost)(executionContext)
    with WindowsDockerClient {
  //Due to some Docker + Windows + Go parsing quirks need to add double quotes around whole command
  //See https://github.com/moby/moby/issues/27592#issuecomment-255227097
  override def inspectCommand: String = """"{{(index (index .NetworkSettings.Ports \"8080/tcp\") 0).HostPort}}""""
}
