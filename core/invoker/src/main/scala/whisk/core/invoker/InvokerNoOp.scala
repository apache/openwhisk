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

package whisk.core.invoker

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.apache.kafka.common.errors.RecordTooLargeException

import akka.actor.ActorSystem
import akka.actor.Props
import spray.json.JsObject
import spray.json.JsString
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.connector.MessageFeed

import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationResponse
import whisk.core.entity.InstanceId
import whisk.core.entity.TimeLimit
import whisk.core.entity.WhiskActivation
import whisk.spi.SpiLoader

/**
 * This Invoker is only to test the Controller. Therefor this invoker only returns a succesful ActiveAck if an activation is dispatched to it.
 * This Invoker does not get the action from the DB and it does not start docker containers to execute anything.
 * It should not be used in production environments.
 */
class InvokerNoOp(config: WhiskConfig, instance: InstanceId, producer: MessageProducer)(
  implicit actorSystem: ActorSystem,
  logging: Logging) {

  implicit val ec = actorSystem.dispatcher

  /** Initialize message consumers */
  val topic = s"invoker${instance.toInt}"
  val msgProvider = SpiLoader.get[MessagingProvider]
  val consumer = msgProvider.getConsumer(
    config,
    "invokers",
    topic,
    Int.MaxValue / 2,
    maxPollInterval = TimeLimit.MAX_DURATION + 1.minute)

  val activationFeed = actorSystem.actorOf(Props {
    new MessageFeed("activation", logging, consumer, consumer.maxPeek, 500.milliseconds, processActivationMessage)
  })

  /** Sends an active-ack. */
  val ack = (tid: TransactionId,
             activationResult: WhiskActivation,
             blockingInvoke: Boolean,
             controllerInstance: InstanceId) => {
    implicit val transid = tid

    def send(res: Either[ActivationId, WhiskActivation], recovery: Boolean = false) = {
      val msg = CompletionMessage(transid, res, instance)

      producer.send(s"completed${controllerInstance.toInt}", msg)
    }

    send(Right(if (blockingInvoke) activationResult else activationResult.withoutLogsOrResult)).recoverWith {
      case t if t.getCause.isInstanceOf[RecordTooLargeException] =>
        send(Left(activationResult.activationId), recovery = true)
    }
  }

  def processActivationMessage(bytes: Array[Byte]): Future[Unit] = {
    Future(ActivationMessage.parse(new String(bytes, StandardCharsets.UTF_8)))
      .flatMap(Future.fromTry(_))
      .flatMap { msg =>
        implicit val transid = msg.transid

        val now = Instant.now
        val activation = WhiskActivation(
          activationId = msg.activationId,
          namespace = msg.activationNamespace,
          subject = msg.user.subject,
          name = msg.action.name,
          start = now,
          end = now,
          response = ActivationResponse.success(Some(JsObject("test" -> JsString("Test")))))

        activationFeed ! MessageFeed.Processed
        ack(msg.transid, activation, msg.blocking, msg.rootControllerIndex)
        Future.successful(())
      }
  }
}
