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
import whisk.core.entity.InstanceId
import whisk.spi.Spi

/**
 * An abstraction for Container creation
 */
trait ContainerFactory {

  /** create a new Container */
  def createContainer(tid: TransactionId,
                      name: String,
                      actionImage: ExecManifest.ImageName,
                      userProvidedImage: Boolean,
                      memory: ByteSize)(implicit config: WhiskConfig, logging: Logging): Future[Container]

  /** perform any initialization */
  def init(): Unit

  /** cleanup any remaining Containers; should block until complete; should ONLY be run at startup/shutdown */
  def cleanup(): Unit
}

/**
 * An SPI for ContainerFactory creation
 * All impls should use the parameters specified as additional args to "docker run" commands
 */
trait ContainerFactoryProvider extends Spi {
  def getContainerFactory(actorSystem: ActorSystem,
                          logging: Logging,
                          config: WhiskConfig,
                          instance: InstanceId,
                          parameters: Map[String, Set[String]]): ContainerFactory
}
