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

package org.apache.openwhisk.core.containerpool

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, ExecutableWhiskAction, InvokerInstanceId}
import org.apache.openwhisk.spi.Spi

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.math.max

case class ContainerArgsConfig(network: String,
                               dnsServers: Seq[String] = Seq.empty,
                               dnsSearch: Seq[String] = Seq.empty,
                               dnsOptions: Seq[String] = Seq.empty,
                               extraEnvVars: Seq[String] = Seq.empty,
                               extraArgs: Map[String, Set[String]] = Map.empty) {

  val extraEnvVarMap: Map[String, String] =
    extraEnvVars.flatMap {
      _.split("=", 2) match {
        case Array(key)        => Some(key -> "")
        case Array(key, value) => Some(key -> value)
        case _                 => None
      }
    }.toMap
}

case class ContainerPoolConfig(userMemory: ByteSize,
                               concurrentPeekFactor: Double,
                               akkaClient: Boolean,
                               prewarmExpirationCheckInitDelay: FiniteDuration,
                               prewarmExpirationCheckInterval: FiniteDuration,
                               prewarmExpirationCheckIntervalVariance: Option[FiniteDuration],
                               prewarmExpirationLimit: Int,
                               prewarmMaxRetryLimit: Int,
                               prewarmPromotion: Boolean,
                               memorySyncInterval: FiniteDuration,
                               prewarmContainerCreationConfig: Option[PrewarmContainerCreationConfig] = None) {
  require(
    concurrentPeekFactor > 0 && concurrentPeekFactor <= 1.0,
    s"concurrentPeekFactor must be > 0 and <= 1.0; was $concurrentPeekFactor")

  require(prewarmExpirationCheckInterval.toSeconds > 0, "prewarmExpirationCheckInterval must be > 0")

  /**
   * The shareFactor indicates the number of containers that would share a single core, on average.
   * cpuShare is a docker option (-c) whereby a container's CPU access is limited.
   * A value of 1024 is the full share so a strict resource division with a shareFactor of 2 would yield 512.
   * On an idle/underloaded system, a container will still get to use underutilized CPU shares.
   */
  private val totalShare = 1024.0 // This is a pre-defined value coming from docker and not our hard-coded value.
  // Grant more CPU to a container if it allocates more memory.
  def cpuShare(reservedMemory: ByteSize) =
    max((totalShare / (userMemory.toBytes / reservedMemory.toBytes)).toInt, 2) // The minimum allowed cpu-shares is 2
}

case class PrewarmContainerCreationConfig(maxConcurrent: Int, creationDelay: FiniteDuration) {
  require(maxConcurrent > 0, "maxConcurrent for per invoker must be > 0")
  require(creationDelay.toSeconds > 0, "creationDelay must be > 0")
}

case class RuntimesRegistryCredentials(user: String, password: String)

case class RuntimesRegistryConfig(url: String, credentials: Option[RuntimesRegistryCredentials])

/**
 * An abstraction for Container creation
 */
trait ContainerFactory {

  /**
   * Create a new Container
   *
   * The created container has to satisfy following requirements:
   * - The container's file system is based on the provided action image and may have a read/write layer on top.
   *   Some managed action runtimes may need the capability to write files.
   * - If the specified image is not available on the system, it is pulled from an image
   *   repository - for example, Docker Hub.
   * - The container needs a network setup - usually, a network interface - such that the invoker is able
   *   to connect the action container. The container must be able to perform DNS resolution based
   *   on the settings provided via ContainerArgsConfig. If needed by action authors,
   *   the container should be able to connect to other systems or even the internet to consume services.
   * - The IP address of said interface is stored in the created Container instance if you want to use
   *   the standard init / run behaviour defined in the Container trait.
   * - The default process specified in the action image is run.
   * - It is desired that all stdout / stderr written by processes in the container is captured such
   *   that it can be obtained using the logs() method of the Container trait.
   * - It is desired that the container supports and enforces the specified memory limit and CPU shares.
   *   In particular, action memory limits rely on the underlying container technology.
   */
  def createContainer(
    tid: TransactionId,
    name: String,
    actionImage: ExecManifest.ImageName,
    userProvidedImage: Boolean,
    memory: ByteSize,
    cpuShares: Int,
    action: Option[ExecutableWhiskAction])(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    createContainer(tid, name, actionImage, userProvidedImage, memory, cpuShares)
  }

  def createContainer(tid: TransactionId,
                      name: String,
                      actionImage: ExecManifest.ImageName,
                      userProvidedImage: Boolean,
                      memory: ByteSize,
                      cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container]

  /** perform any initialization */
  def init(): Unit

  /** cleanup any remaining Containers; should block until complete; should ONLY be run at startup/shutdown */
  def cleanup(): Unit
}

object ContainerFactory {

  /** based on https://github.com/moby/moby/issues/3138 and https://github.com/moby/moby/blob/master/daemon/names/names.go */
  private def isAllowed(c: Char) = c.isLetterOrDigit || c == '_' || c == '.' || c == '-'

  /** include the instance name, if specified and strip invalid chars before attempting to use them in the container name */
  def containerNamePrefix(instanceId: InvokerInstanceId): String =
    s"wsk${instanceId.uniqueName.getOrElse("")}${instanceId.toInt}".filter(isAllowed)

  def resolveRegistryConfig(userProvidedImage: Boolean,
                            runtimesRegistryConfig: RuntimesRegistryConfig,
                            userImagesRegistryConfig: RuntimesRegistryConfig): RuntimesRegistryConfig = {
    if (userProvidedImage) userImagesRegistryConfig else runtimesRegistryConfig
  }
}

/**
 * An SPI for ContainerFactory creation
 * All impls should use the parameters specified as additional args to "docker run" commands
 */
trait ContainerFactoryProvider extends Spi {
  def instance(actorSystem: ActorSystem,
               logging: Logging,
               config: WhiskConfig,
               instance: InvokerInstanceId,
               parameters: Map[String, Set[String]]): ContainerFactory
}
