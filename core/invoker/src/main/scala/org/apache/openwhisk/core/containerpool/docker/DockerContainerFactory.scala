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

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.InvokerInstanceId

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.core.ConfigKeys

case class DockerContainerFactoryConfig(useRunc: Boolean)

class DockerContainerFactory(instance: InvokerInstanceId,
                             parameters: Map[String, Set[String]],
                             containerArgsConfig: ContainerArgsConfig =
                               loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs),
                             protected val runtimesRegistryConfig: RuntimesRegistryConfig =
                               loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.runtimesRegistry),
                             protected val userImagesRegistryConfig: RuntimesRegistryConfig =
                               loadConfigOrThrow[RuntimesRegistryConfig](ConfigKeys.userImagesRegistry),
                             dockerContainerFactoryConfig: DockerContainerFactoryConfig =
                               loadConfigOrThrow[DockerContainerFactoryConfig](ConfigKeys.dockerContainerFactory))(
  implicit actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging,
  docker: DockerApiWithFileAccess,
  runc: RuncApi)
    extends ContainerFactory {

  /** Create a container using docker cli */
  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    val registryConfig =
      ContainerFactory.resolveRegistryConfig(userProvidedImage, runtimesRegistryConfig, userImagesRegistryConfig)
    val image = if (userProvidedImage) Left(actionImage) else Right(actionImage)
    DockerContainer.create(
      tid,
      image = image,
      registryConfig = Some(registryConfig),
      memory = memory,
      cpuShares = cpuShares,
      environment = Map("__OW_API_HOST" -> config.wskApiHost) ++ containerArgsConfig.extraEnvVarMap,
      network = containerArgsConfig.network,
      dnsServers = containerArgsConfig.dnsServers,
      dnsSearch = containerArgsConfig.dnsSearch,
      dnsOptions = containerArgsConfig.dnsOptions,
      name = Some(name),
      useRunc = dockerContainerFactoryConfig.useRunc,
      parameters ++ containerArgsConfig.extraArgs.map { case (k, v) => ("--" + k, v) })
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
    val cleaning =
      docker.ps(filters = Seq("name" -> s"${ContainerFactory.containerNamePrefix(instance)}_"), all = true).flatMap {
        containers =>
          logging.info(this, s"removing ${containers.size} action containers.")
          val removals = containers.map { id =>
            (if (dockerContainerFactoryConfig.useRunc) {
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
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instanceId: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory = {

    new DockerContainerFactory(instanceId, parameters)(
      actorSystem,
      actorSystem.dispatcher,
      logging,
      new DockerClientWithFileAccess()(actorSystem.dispatcher)(logging, actorSystem),
      new RuncClient()(actorSystem.dispatcher)(logging, actorSystem))
  }

}
