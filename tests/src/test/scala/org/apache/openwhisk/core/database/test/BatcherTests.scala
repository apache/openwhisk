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

package org.apache.openwhisk.core.database.test

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import common.{LoggedFunction, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import org.apache.openwhisk.core.database.Batcher
import org.apache.openwhisk.utils.retry

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}

@RunWith(classOf[JUnitRunner])
class BatcherTests extends FlatSpec with Matchers with WskActorSystem {

  def await[V](f: Future[V]) = Await.result(f, 10.seconds)

  def between(start: Instant, end: Instant) =
    Duration.fromNanos(java.time.Duration.between(start, end).toNanos)

  val promiseDelay = 100.milliseconds
  def resolveDelayed(p: Promise[Unit], delay: FiniteDuration = promiseDelay) =
    akka.pattern.after(delay, actorSystem.scheduler) {
      p.success(())
      Future.successful(())
    }

  behavior of "Batcher"

  it should "batch based on batch size" in {
    val ps = Seq.fill(3)(Promise[Unit]())
    val batchPromises = mutable.Queue(ps: _*)

    val transform = (i: Int) => i + 1

    val batchOperation = LoggedFunction((els: Seq[Int], retry: Int) => {
      batchPromises.dequeue().future.map(_ => els.map(transform))
    })

    val batcher = new Batcher[Int, Int](2, 1, 1)(batchOperation)

    val values = 1 to 5
    val results = values.map(batcher.put)

    // First "batch"
    retry(batchOperation.calls should have size 1, (promiseDelay.toMillis * 2).toInt)
    batchOperation.calls(0)._1 should have size 1

    // Allow batch to build up
    resolveDelayed(ps(0))

    // Second batch
    retry(batchOperation.calls should have size 2, (promiseDelay.toMillis * 2).toInt)
    batchOperation.calls(1)._1 should have size 2

    // Allow batch to build up
    resolveDelayed(ps(1))

    // Third batch
    retry(batchOperation.calls should have size 3, (promiseDelay.toMillis * 2).toInt)
    batchOperation.calls(2)._1 should have size 2
    ps(2).success(())

    await(Future.sequence(results)) shouldBe values.map(transform)
  }

  it should "run batches through the operation in parallel" in {
    val p = Promise[Unit]()
    val parallel = new AtomicInteger(0)
    val concurrency = 2

    val batcher = new Batcher[Int, Int](1, concurrency, 1)((els, _) => {
      parallel.incrementAndGet()
      p.future.map(_ => els)
    })

    val values = 1 to 3
    val results = values.map(batcher.put)

    // Before we resolve the promise, 2 batches should have entered the batch operation
    // which is now hanging and waiting for the promise to be resolved.
    retry(parallel.get shouldBe concurrency, 100)

    p.success(())

    await(Future.sequence(results)) shouldBe values
  }

  it should "complete batched values with the thrown exception" in {
    val batcher = new Batcher[Int, Int](2, 1, 1)((_, _) => Future.failed(new Exception))

    val r1 = batcher.put(1)
    val r2 = batcher.put(2)

    an[Exception] should be thrownBy await(r1)
    an[Exception] should be thrownBy await(r2)

    // the batcher is still intact
    val r3 = batcher.put(3)
    val r4 = batcher.put(4)

    an[Exception] should be thrownBy await(r3)
    an[Exception] should be thrownBy await(r4)
  }

  it should "complete batched values with max retry limit" in {
    val p = Promise[Unit]()

    val maxRetry = 3
    val batchSize = 1
    val concurrency = 1

    var retryCount = new AtomicInteger(0)

    def doStore(els: Seq[Int], retry: Int): Future[Seq[Int]] = {
      val result = if (retry > 0) {
        Future.failed(new Exception)
      } else {
        p.future.map(_ => els)
      }

      result.recoverWith {
        case _ if retry > 0 =>
          retryCount.incrementAndGet()
          doStore(els, retry - 1)
        case e =>
          Future.failed(e)
      }

    }
    val batcher = new Batcher[Int, Int](batchSize, concurrency, maxRetry)(doStore)

    val values = List(1)
    val results = values.map(batcher.put)

    p.success(())

    await(Future.sequence(results)) shouldBe values

    retryCount.get() shouldBe maxRetry
  }

  it should "complete batched values with the thrown exception with max retry limit" in {
    val p = Promise[Unit]()

    val maxRetry = 3
    val batchSize = 1
    val concurrency = 1

    val retryCount = new AtomicInteger(0)

    def doStore(els: Seq[Int], retry: Int): Future[Seq[Int]] = {
      val result = Future.failed(new Exception)

      result.recoverWith {
        case _ if retry > 0 =>
          retryCount.incrementAndGet()
          doStore(els, retry - 1)
        case e =>
          Future.failed(e)
      }

    }
    val batcher = new Batcher[Int, Int](batchSize, concurrency, maxRetry)(doStore)

    val r1 = batcher.put(1)

    an[Exception] should be thrownBy await(r1)

    retryCount.get() shouldBe maxRetry
  }
}
