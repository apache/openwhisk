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

package org.apache.openwhisk.core.database

import akka.Done
import akka.actor.ActorSystem

import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}
import akka.stream.{CompletionStrategy, OverflowStrategy}
import akka.stream.scaladsl.{Sink, Source}

/**
 * Enables batching of a type T.
 *
 * Batches are being created using a maximum batchSize. If there is back-pressure (concurrency is
 * maxed out and it is waiting for the operations to complete) a batch will be build up and
 * then handled accordingly. Batching will only happen under back-pressure so there is no latency
 * being traded off for batching in a non back-pressured case.
 *
 * The given concurrency controls how many batches are handled in parallel. (example: How many
 * batches of records are written to the database in parallel.)
 *
 * The operation-function takes a batch of T and does something to it that results in a sequence
 * of the same size of the batch. (example: Writing a batch of database records results in a sequence
 * of database responses). These results will be assigned to the relevant element in the batch.
 * (example: Putting a database record in batches will give you the database response for each record
 * respectively)
 *
 * @param batchSize maximum size of a batch
 * @param concurrency number of batches being handled in parallel
 * @param operation operation taking the batch
 * @tparam T the type to be batched
 * @tparam R return type of a single element after operation
 */
class Batcher[T, R](batchSize: Int, concurrency: Int)(operation: Seq[T] => Future[Seq[R]])(implicit
                                                                                           system: ActorSystem,
                                                                                           ec: ExecutionContext) {

  val cm: PartialFunction[Any, CompletionStrategy] = {
    case Done =>
      CompletionStrategy.immediately
  }

  private val stream = Source
    .actorRef[(T, Promise[R])](
      completionMatcher = cm,
      failureMatcher = PartialFunction.empty[Any, Throwable],
      bufferSize = Int.MaxValue,
      overflowStrategy = OverflowStrategy.dropNew)
    .batch(batchSize, Queue(_))((queue, element) => queue :+ element)
    .mapAsyncUnordered(concurrency) { els =>
      val elements = els.map(_._1)
      val promises = els.map(_._2)

      val f = operation(elements)
      f.onComplete {
        case Success(results) => results.zip(promises).foreach { case (result, p) => p.success(result) }
        case Failure(e)       => promises.foreach(_.failure(e))
      }
      // Recover Future to not abort stream in case of a failure
      f.recover { case _ => () }
    }
    .to(Sink.ignore)
    .run()

  /**
   * Adds an element to be batch-processed.
   *
   * @param el the element to process
   * @return a future containing the response of the database for this specific element
   */
  def put(el: T): Future[R] = {
    val promise = Promise[R]()
    stream ! (el, promise)
    promise.future
  }
}
