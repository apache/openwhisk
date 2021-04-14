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

package org.apache.openwhisk.core.containerpool.v2

import java.time.Instant

import akka.actor.ActorRef
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.{ByteSize, CodeExec, DocRevision, ExecutableWhiskAction}
import org.apache.openwhisk.core.entity.size._

import scala.concurrent.duration.{Deadline, FiniteDuration}

// Events received by the actor
case class Initialize(invocationNamespace: String,
                      action: ExecutableWhiskAction,
                      schedulerHost: String,
                      rpcPort: Int,
                      transId: TransactionId)
case class Start(exec: CodeExec[_], memoryLimit: ByteSize, ttl: Option[FiniteDuration] = None)

// Event sent by the actor
case class ContainerCreationFailed(throwable: Throwable)
case class ContainerIsPaused(data: WarmData)
case class ClientCreationFailed(throwable: Throwable,
                                container: Container,
                                invocationNamespace: String,
                                action: ExecutableWhiskAction)
case class ReadyToWork(data: PreWarmData)
case class Initialized(data: InitializedData)
case class Resumed(data: WarmData)
case class ResumeFailed(data: WarmData)
case class RecreateClient(action: ExecutableWhiskAction)

// States
sealed trait ProxyState
case object LeaseStart extends ProxyState
case object Uninitialized extends ProxyState
case object CreatingContainer extends ProxyState
case object ContainerCreated extends ProxyState
case object CreatingClient extends ProxyState
case object ClientCreated extends ProxyState
case object Running extends ProxyState
case object Pausing extends ProxyState
case object Paused extends ProxyState
case object Removing extends ProxyState
case object Rescheduling extends ProxyState

// Data
sealed abstract class Data(val memoryLimit: ByteSize) {
  def getContainer: Option[Container]
}
case class NonexistentData() extends Data(0.B) {
  override def getContainer = None
}
case class MemoryData(override val memoryLimit: ByteSize) extends Data(memoryLimit) {
  override def getContainer = None
}
trait WithClient { val clientProxy: ActorRef }
case class PreWarmData(container: Container,
                       kind: String,
                       override val memoryLimit: ByteSize,
                       expires: Option[Deadline] = None)
    extends Data(memoryLimit) {
  override def getContainer = Some(container)
  def isExpired(): Boolean = expires.exists(_.isOverdue())
}

case class ContainerCreatedData(container: Container, invocationNamespace: String, action: ExecutableWhiskAction)
    extends Data(action.limits.memory.megabytes.MB) {
  override def getContainer = Some(container)
}

case class InitializedData(container: Container,
                           invocationNamespace: String,
                           action: ExecutableWhiskAction,
                           override val clientProxy: ActorRef)
    extends Data(action.limits.memory.megabytes.MB)
    with WithClient {
  override def getContainer = Some(container)
}

case class WarmData(container: Container,
                    invocationNamespace: String,
                    action: ExecutableWhiskAction,
                    revision: DocRevision,
                    lastUsed: Instant,
                    override val clientProxy: ActorRef)
    extends Data(action.limits.memory.megabytes.MB)
    with WithClient {
  override def getContainer = Some(container)
}

// TODO
class FunctionPullingContainerProxy {}
