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

package org.apache.openwhisk.core.database.cosmosdb.lambda

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.aws.LambdaStore
import org.apache.openwhisk.core.entity.WhiskAction

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class WhiskActionToLambdaBuilder(store: LambdaStore)(implicit system: ActorSystem, materializer: ActorMaterializer)
    extends WhiskActionConsumer {
  private val bufferSize = 100
  private implicit val executionContext: ExecutionContext = system.dispatcher

  private val queue = Source
    .queue[LambdaTask](bufferSize, OverflowStrategy.dropNew) //TODO Use backpressure
    .mapAsync(5) { task =>
      //TODO Perform this with retry
      val lr = Try(store.createOrUpdateLambda(task.action)(task.tid))
      val f = lr match {
        case Success(fr) => fr
        case Failure(t)  => Future.failed(t)
      }
      f.transform {
        //Map both success and failure to Success such that stream continues
        //Client would be notified of failure and can decide what to do
        case Success(_) => Success(Success(Done), task)
        case Failure(t) => Success(Failure(t), task)
      }
    }
    .toMat(Sink.foreach({
      case (Success(_), t) =>
        t.promise.success(Done)
      case (Failure(error), t) =>
        t.promise.failure(error)
    }))(Keep.left)
    .run

  override def send(action: WhiskAction)(implicit tid: TransactionId): Future[Done] = {
    val promise = Promise[Done]
    queue.offer(LambdaTask(action, tid, promise)).flatMap {
      case QueueOfferResult.Enqueued    => promise.future
      case QueueOfferResult.Dropped     => Future.failed(new Exception("Lambda builder request queue is full."))
      case QueueOfferResult.QueueClosed => Future.failed(new Exception("Lambda builder request queue was closed."))
      case QueueOfferResult.Failure(f)  => Future.failed(f)
    }
  }

  def close(): Future[Done] = {
    queue.complete()
    queue.watchCompletion()
  }

  private case class LambdaTask(action: WhiskAction, tid: TransactionId, promise: Promise[Done])

}
