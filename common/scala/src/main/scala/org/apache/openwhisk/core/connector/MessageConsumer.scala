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

package org.apache.openwhisk.core.connector

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.util.Failure
import org.apache.kafka.clients.consumer.CommitFailedException
import akka.actor.FSM
import akka.pattern.pipe
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId

trait MessageConsumer {

  /** The maximum number of messages peeked (i.e., max number of messages retrieved during a long poll). */
  val maxPeek: Int

  /**
   * Gets messages via a long poll. May or may not remove messages
   * from the message connector. Use commit() to ensure messages are
   * removed from the connector.
   *
   * @param duration for the long poll
   * @return iterable collection (topic, partition, offset, bytes)
   */
  def peek(duration: FiniteDuration, retry: Int = 3): Iterable[(String, Int, Long, Array[Byte])]

  /**
   * Commits offsets from last peek operation to ensure they are removed
   * from the connector.
   */
  def commit(retry: Int = 3): Unit

  /** Closes consumer. */
  def close(): Unit

}

object MessageFeed {
  protected sealed trait FeedState
  protected[connector] case object Idle extends FeedState
  protected[connector] case object FillingPipeline extends FeedState
  protected[connector] case object DrainingPipeline extends FeedState

  protected sealed trait FeedData
  private case object NoData extends FeedData

  /** Indicates the consumer is ready to accept messages from the message bus for processing. */
  object Ready

  /** Steady state message, indicates capacity in downstream process to receive more messages. */
  object Processed

  /** Indicates the fill operation has completed. */
  private case class FillCompleted(messages: Seq[(String, Int, Long, Array[Byte])])
}

/**
 * This actor polls the message bus for new messages and dispatches them to the given
 * handler. The actor tracks the number of messages dispatched and will not dispatch new
 * messages until some number of them are acknowledged.
 *
 * This is used by the invoker to pull messages from the message bus and apply back pressure
 * when the invoker does not have resources to complete processing messages (i.e., no containers
 * are available to run new actions). It is also used in the load balancer to consume active
 * ack messages.
 * When the invoker releases resources (by reclaiming containers) it will send a message
 * to this actor which will then attempt to fill the pipeline with new messages.
 *
 * The actor tries to fill the pipeline with additional messages while the number
 * of outstanding requests is below the pipeline fill threshold.
 */
@throws[IllegalArgumentException]
class MessageFeed(description: String,
                  logging: Logging,
                  consumer: MessageConsumer,
                  maximumHandlerCapacity: Int,
                  longPollDuration: FiniteDuration,
                  handler: Array[Byte] => Future[Unit],
                  autoStart: Boolean = true,
                  logHandoff: Boolean = true)
    extends FSM[MessageFeed.FeedState, MessageFeed.FeedData] {
  import MessageFeed._

  // double-buffer to make up for message bus read overhead
  val maxPipelineDepth = maximumHandlerCapacity * 2
  private val pipelineFillThreshold = maxPipelineDepth - consumer.maxPeek

  require(
    consumer.maxPeek <= maxPipelineDepth,
    "consumer may not yield more messages per peek than permitted by max depth")

  // Immutable Queue
  // although on the surface it seems to make sense to use an immutable variable with a mutable Queue,
  // Akka Actor state defies the usual "prefer immutable" guideline in Scala, esp. w/ Collections.
  // If, for some reason, this Queue was mutable and is accidentally leaked in say an Akka message,
  // another Actor or recipient would be able to mutate the internal state of this Actor.
  // Best practice dictates a mutable variable pointing at an immutable collection for this reason
  private var outstandingMessages = immutable.Queue.empty[(String, Int, Long, Array[Byte])]
  private var handlerCapacity = maximumHandlerCapacity

  private implicit val tid = TransactionId.dispatcher

  logging.info(
    this,
    s"handler capacity = $maximumHandlerCapacity, pipeline fill at = $pipelineFillThreshold, pipeline depth = $maxPipelineDepth")

  when(Idle) {
    case Event(Ready, _) =>
      fillPipeline()
      goto(FillingPipeline)

    case _ => stay
  }

  // wait for fill to complete, and keep filling if there is
  // capacity otherwise wait to drain
  when(FillingPipeline) {
    case Event(Processed, _) =>
      updateHandlerCapacity()
      sendOutstandingMessages()
      stay

    case Event(FillCompleted(messages), _) =>
      outstandingMessages = outstandingMessages ++ messages
      sendOutstandingMessages()

      if (shouldFillQueue()) {
        fillPipeline()
        stay
      } else {
        goto(DrainingPipeline)
      }

    case _ => stay
  }

  when(DrainingPipeline) {
    case Event(Processed, _) =>
      updateHandlerCapacity()
      sendOutstandingMessages()
      if (shouldFillQueue()) {
        fillPipeline()
        goto(FillingPipeline)
      } else stay

    case _ => stay
  }

  onTransition { case _ -> Idle => if (autoStart) self ! Ready }
  startWith(Idle, MessageFeed.NoData)
  initialize()

  private implicit val ec = context.system.dispatchers.lookup("dispatchers.kafka-dispatcher")

  private def fillPipeline(): Unit = {
    if (outstandingMessages.size <= pipelineFillThreshold) {
      Future {
        blocking {
          // Grab next batch of messages and commit offsets immediately
          // essentially marking the activation as having satisfied "at most once"
          // semantics (this is the point at which the activation is considered started).
          // If the commit fails, then messages peeked are peeked again on the next poll.
          // While the commit is synchronous and will block until it completes, at steady
          // state with enough buffering (i.e., maxPipelineDepth > maxPeek), the latency
          // of the commit should be masked.
          val records = consumer.peek(longPollDuration)
          consumer.commit()
          FillCompleted(records.toSeq)
        }
      }.andThen {
          case Failure(e: CommitFailedException) =>
            logging.error(this, s"failed to commit $description consumer offset: $e")
          case Failure(e: Throwable) => logging.error(this, s"exception while pulling new $description records: $e")
        }
        .recover {
          case _ => FillCompleted(Seq.empty)
        }
        .pipeTo(self)
    } else {
      logging.error(this, s"dropping fill request until $description feed is drained")
    }
  }

  /** Send as many messages as possible to the handler. */
  @tailrec
  private def sendOutstandingMessages(): Unit = {
    val occupancy = outstandingMessages.size
    if (occupancy > 0 && handlerCapacity > 0) {
      // Easiest way with an immutable queue to cleanly dequeue
      // Head is the first elemeent of the queue, desugared w/ an assignment pattern
      // Tail is everything but the first element, thus mutating the collection variable
      val (topic, partition, offset, bytes) = outstandingMessages.head
      outstandingMessages = outstandingMessages.tail

      if (logHandoff) logging.debug(this, s"processing $topic[$partition][$offset] ($occupancy/$handlerCapacity)")
      handler(bytes)
      handlerCapacity -= 1

      sendOutstandingMessages()
    }
  }

  private def shouldFillQueue(): Boolean = {
    val occupancy = outstandingMessages.size
    if (occupancy <= pipelineFillThreshold) {
      logging.debug(
        this,
        s"$description pipeline has capacity: $occupancy <= $pipelineFillThreshold ($handlerCapacity)")
      true
    } else {
      logging.debug(this, s"$description pipeline must drain: $occupancy > $pipelineFillThreshold")
      false
    }
  }

  private def updateHandlerCapacity(): Int = {
    logging.debug(self, s"$description received processed msg, current capacity = $handlerCapacity")

    if (handlerCapacity < maximumHandlerCapacity) {
      handlerCapacity += 1
      handlerCapacity
    } else {
      if (handlerCapacity > maximumHandlerCapacity) logging.error(self, s"$description capacity already at max")
      maximumHandlerCapacity
    }
  }
}
