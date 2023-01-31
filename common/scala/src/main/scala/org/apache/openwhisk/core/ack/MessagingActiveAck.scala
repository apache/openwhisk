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

import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, EventMessage, MessageProducer}
import org.apache.openwhisk.core.entity._
import pureconfig._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class MessagingActiveAck(producer: MessageProducer, instance: InstanceId, eventSender: Option[EventSender])(
  implicit logging: Logging,
  ec: ExecutionContext)
    extends ActiveAck {

  private val topicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsPrefix)

  override def apply(tid: TransactionId,
                     activationResult: WhiskActivation,
                     blockingInvoke: Boolean,
                     controllerInstance: ControllerInstanceId,
                     userId: UUID,
                     acknowledegment: AcknowledegmentMessage): Future[Any] = {
    implicit val transid: TransactionId = tid

    def send(msg: AcknowledegmentMessage, recovery: Boolean = false) = {
      producer.send(topic = topicPrefix + "completed" + controllerInstance.asString, msg).andThen {
        case Success(_) =>
          val info = if (recovery) s"recovery ${msg.messageType}" else msg.messageType
          logging.info(this, s"posted $info of activation ${acknowledegment.activationId}")
      }
    }

    // UserMetrics are sent, when the slot is free again. This ensures, that all metrics are sent.
    if (acknowledegment.isSlotFree.nonEmpty) {
      eventSender.foreach { s =>
        EventMessage.from(activationResult, instance.source, userId) match {
          case Success(msg) => s.send(msg)
          case Failure(t)   => logging.error(this, s"activation event was not sent: $t")
        }
      }
    }

    // An acknowledgement containing the result is only needed for blocking invokes in order to further the
    // continuation. A result message for a non-blocking activation is not actually registered in the load balancer
    // and the container proxy should not send such an acknowlegement unless it's a blocking request. Here the code
    // is defensive and will shrink all non-blocking acknowledegments.
    send(if (blockingInvoke) acknowledegment else acknowledegment.shrink).recoverWith {
      case t if t.getCause.isInstanceOf[RecordTooLargeException] =>
        send(acknowledegment.shrink, recovery = true)
    }
  }
}
