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

import akka.actor.{ActorRef, ActorRefFactory, Props}
import whisk.common.TransactionId
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity._
import whisk.spi.Spi

import scala.concurrent.Future

trait ContainerPool {

  /** Creates a new container and updates state accordingly. */
  def createContainer(): (ActorRef, ContainerData)

  /** Creates a new prewarmed container */
  def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize)

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef)

}

trait ContainerPoolProvider extends Spi {
  def getContainerProxyFactory(
    factory: (TransactionId, String, ImageName, Boolean, ByteSize, Int) => Future[Container],
    ack: (TransactionId, WhiskActivation, Boolean, InstanceId, UUID) => Future[Any],
    store: (TransactionId, WhiskActivation) => Future[Any],
    collectLogs: (TransactionId, Identity, WhiskActivation, Container, ExecutableWhiskAction) => Future[ActivationLogs],
    instance: InstanceId,
    poolConfig: ContainerPoolConfig): ActorRefFactory => ActorRef

  def props(factory: ActorRefFactory => ActorRef,
            poolConfig: ContainerPoolConfig,
            feed: ActorRef,
            prewarmConfig: List[PrewarmingConfig] = List.empty): Props

  def requiredProperties: Map[String, String]
}
