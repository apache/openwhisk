/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.dispatcher

import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import scala.util.Try

import akka.actor.Actor
import akka.actor.actorRef2Scala
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.connector.MessageConsumer

object ActivationFeed {
    sealed class ActivationNotification

    /** Pulls new messages from the message bus. */
    case class FillQueueWithMessages()

    /** Indicates resources are available because transaction completed, may cause pipeline fill. */
    case class ContainerReleased(tid: TransactionId) extends ActivationNotification

    /** Indicate resources are available because transaction failed, may cause pipeline fill. */
    case class FailedActivation(tid: TransactionId) extends ActivationNotification
}

/**
 * This actor polls the message bus for new messages and dispatches them to the given
 * handler. The actor tracks the number of messages dispatched and will not dispatch new
 * messages until some number of them are acknowledged.
 *
 * This is used by the invoker to pull messages from the message bus and apply back pressure
 * when the invoker does not have resources to complete processing messages (i.e., no containers
 * are available to run new actions).
 *
 * When the invoker releases resources (by reclaiming containers) it will send a message
 * to this actor which will then attempt to fill the pipeline with new messages.
 *
 * The actor tries to fill the pipeline with additional messages while the number
 * of outstanding requests is below the pipeline fill threshold.
 */
@throws[IllegalArgumentException]
protected class ActivationFeed(
    logging: Logging,
    consumer: MessageConsumer,
    maxPipelineDepth: Int,
    longpollDuration: FiniteDuration,
    handler: (String, Array[Byte]) => Any)
    extends Actor {
    import ActivationFeed.ActivationNotification
    import ActivationFeed.FillQueueWithMessages

    require(consumer.maxPeek <= maxPipelineDepth, "consumer may not yield more messages per peek than permitted by max depth")

    private val pipelineFillThreshold = maxPipelineDepth - consumer.maxPeek
    private var pipelineOccupancy = 0

    override def receive = {
        case FillQueueWithMessages =>
            if (pipelineOccupancy <= pipelineFillThreshold) {
                Try {
                    val messages = consumer.peek(longpollDuration) // grab next batch of messages
                    val count = messages.size
                    messages foreach {
                        case (topic, partition, offset, bytes) =>
                            logging.info(this, s"processing $topic[$partition][$offset ($count)]")(TransactionId.dispatcher)
                            pipelineOccupancy += 1
                            handler(topic, bytes)
                    }
                }
                consumer.commit()
                fill()
            } else logging.debug(this, "dropping fill request until feed is drained")

        case _: ActivationNotification =>
            pipelineOccupancy -= 1
            fill()
    }

    private def fill() = {
        if (pipelineOccupancy <= pipelineFillThreshold) {
            logging.debug(this, s"filling activation pipeline: $pipelineOccupancy <= $pipelineFillThreshold")(TransactionId.dispatcher)
            self ! FillQueueWithMessages
        } else {
            logging.info(this, s"waiting for activation pipeline to drain: $pipelineOccupancy > $pipelineFillThreshold")(TransactionId.dispatcher)
        }
    }
}
