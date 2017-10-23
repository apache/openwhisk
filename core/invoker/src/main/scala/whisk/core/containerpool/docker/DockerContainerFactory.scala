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

package whisk.core.containerpool.docker

import akka.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerFactory
import whisk.core.containerpool.ContainerFactoryProvider
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

class DockerContainerFactory(config: WhiskConfig, instance: InstanceId, parameters: Map[String, Set[String]])(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging)
    extends ContainerFactory {

  /** Initialize container clients */
  implicit val docker = new DockerClientWithFileAccess()(ec)
  implicit val runc = new RuncClient(ec)

  /** Create a container using docker cli */
  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    val image = if (userProvidedImage) {
      actionImage.publicImageName
    } else {
      actionImage.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
    }

    DockerContainer.create(
      tid,
      image = image,
      userProvidedImage = userProvidedImage,
      memory = memory,
      cpuShares = config.invokerCoreShare.toInt,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      network = config.invokerContainerNetwork,
      dnsServers = config.invokerContainerDns,
      name = Some(name),
      useRunc = config.invokerUseRunc,
      parameters)
  }

  /** Perform cleanup on init */
  override def init(): Unit = removeAllActionContainers()

  /** Perform cleanup on exit - to be registered as shutdown hook */
  override def cleanup(): Unit = {
    implicit val transid = TransactionId.invoker
    try {
      removeAllActionContainers()
    } catch {
      case e: Exception => logging.error(this, s"Failed to remove action containers: ${e.getMessage}")
    }
  }

  /**
   * Removes all wsk_ containers - regardless of their state
   *
   * If the system in general or Docker in particular has a very
   * high load, commands may take longer than the specified time
   * resulting in an exception.
   *
   * There is no checking whether container removal was successful
   * or not.
   *
   * @throws InterruptedException     if the current thread is interrupted while waiting
   * @throws TimeoutException         if after waiting for the specified time this `Awaitable` is still not ready
   */
  @throws(classOf[TimeoutException])
  @throws(classOf[InterruptedException])
  private def removeAllActionContainers(): Unit = {
    implicit val transid = TransactionId.invoker
    val cleaning = docker.ps(filters = Seq("name" -> s"wsk${instance.toInt}_"), all = true).flatMap { containers =>
      logging.info(this, s"removing ${containers.size} action containers.")
      val removals = containers.map { id =>
        (if (config.invokerUseRunc) {
           runc.resume(id)
         } else {
           docker.unpause(id)
         })
          .recoverWith {
            // Ignore resume failures and try to remove anyway
            case _ => Future.successful(())
          }
          .flatMap { _ =>
            docker.rm(id)
          }
      }
      Future.sequence(removals)
    }
    Await.ready(cleaning, 30.seconds)
  }
}

object DockerContainerFactoryProvider extends ContainerFactoryProvider {
  override def getContainerFactory(actorSystem: ActorSystem,
                                   logging: Logging,
                                   config: WhiskConfig,
                                   instanceId: InstanceId,
                                   parameters: Map[String, Set[String]]): ContainerFactory =
    new DockerContainerFactory(config, instanceId, parameters)(actorSystem, actorSystem.dispatcher, logging)
}
