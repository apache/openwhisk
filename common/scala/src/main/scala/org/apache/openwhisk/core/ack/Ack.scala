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

package org.apache.openwhisk.core.ack
import org.apache.openwhisk.common.{TransactionId, UserEvents}
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, EventMessage, MessageProducer}
import org.apache.openwhisk.core.entity.{ControllerInstanceId, UUID, WhiskActivation}

import scala.concurrent.Future

/**
 * A method for sending Active Acknowledgements (aka "active ack") messages to the load balancer. These messages
 * are either completion messages for an activation to indicate a resource slot is free, or result-forwarding
 * messages for continuations (e.g., sequences and conductor actions).
 *
 * The activation result is always provided because some acknowledegment messages may not carry the result of
 * the activation and this is needed for sending user events.
 *
 * @param tid the transaction id for the activation
 * @param activationResult is the activation result
 * @param blockingInvoke is true iff the activation was a blocking request
 * @param controllerInstance the originating controller/loadbalancer id
 * @param userId is the UUID for the namespace owning the activation
 * @param acknowledegment the acknowledgement message to send
 */
trait ActiveAck {
  def apply(tid: TransactionId,
            activationResult: WhiskActivation,
            blockingInvoke: Boolean,
            controllerInstance: ControllerInstanceId,
            userId: UUID,
            acknowledegment: AcknowledegmentMessage): Future[Any]
}

trait EventSender {
  def send(msg: => EventMessage): Unit
}

class UserEventSender(producer: MessageProducer) extends EventSender {
  override def send(msg: => EventMessage): Unit = UserEvents.send(producer, msg)
}
