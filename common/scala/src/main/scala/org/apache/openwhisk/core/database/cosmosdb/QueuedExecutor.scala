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

package org.apache.openwhisk.core.database.cosmosdb

import akka.Done
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import kamon.metric.Gauge
import org.apache.openwhisk.common.Counter

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class QueuedExecutor[T, R](queueSize: Int, concurrency: Int, gauge: Option[Gauge] = None)(operation: T => Future[R])(
  implicit materializer: ActorMaterializer,
  ec: ExecutionContext) {
  private val counter = new Counter
  private val (queue, queueFinish) = Source
    .queue[(T, Promise[R])](queueSize, OverflowStrategy.dropNew)
    .mapAsyncUnordered(concurrency) {
      case (d, p) =>
        val f = operation(d)
        f.onComplete {
          case Success(result) =>
            elementRemoved()
            p.success(result)
          case Failure(e) =>
            elementRemoved()
            p.failure(e)
        }
        // Recover Future to not abort stream in case of a failure
        f.recover { case _ => () }
    }
    .toMat(Sink.ignore)(Keep.both)
    .run()

  /**
   * Queues an element to be written later
   *
   * @param el the element to process
   * @return a future containing the response of the database for this specific element
   */
  def put(el: T): Future[R] = {
    val promise = Promise[R]()
    queue.offer(el -> promise).flatMap {
      case QueueOfferResult.Enqueued =>
        elementAdded()
        promise.future
      case QueueOfferResult.Dropped     => Future.failed(new Exception("DB request queue is full."))
      case QueueOfferResult.QueueClosed => Future.failed(new Exception("DB request queue was closed."))
      case QueueOfferResult.Failure(f)  => Future.failed(f)
    }
    promise.future
  }

  def size: Long = counter.cur

  def close(): Future[Done] = {
    queue.complete()
    queue.watchCompletion().flatMap(_ => queueFinish)
  }

  private def elementAdded() = {
    gauge.foreach(_.increment())
    counter.next()
  }

  private def elementRemoved() = {
    gauge.foreach(_.decrement())
    counter.prev()
  }
}
