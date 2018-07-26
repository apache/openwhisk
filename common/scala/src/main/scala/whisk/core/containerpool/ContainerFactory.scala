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

package whisk.core.containerpool

import akka.actor.ActorSystem
import scala.concurrent.Future
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest
import whisk.core.entity.InvokerInstanceId
import whisk.spi.Spi

case class ContainerArgsConfig(network: String,
                               dnsServers: Seq[String] = Seq.empty,
                               extraArgs: Map[String, Set[String]] = Map.empty)

case class ContainerPoolConfig(numCore: Int, coreShare: Int, akkaClient: Boolean) {

  /**
   * The total number of containers is simply the number of cores dilated by the cpu sharing.
   */
  def maxActiveContainers = numCore * coreShare

  /**
   * The shareFactor indicates the number of containers that would share a single core, on average.
   * cpuShare is a docker option (-c) whereby a container's CPU access is limited.
   * A value of 1024 is the full share so a strict resource division with a shareFactor of 2 would yield 512.
   * On an idle/underloaded system, a container will still get to use underutilized CPU shares.
   */
  private val totalShare = 1024.0 // This is a pre-defined value coming from docker and not our hard-coded value.
  def cpuShare = (totalShare / maxActiveContainers).toInt
}

/**
 * An abstraction for Container creation
 */
trait ContainerFactory {

  /** create a new Container */
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
