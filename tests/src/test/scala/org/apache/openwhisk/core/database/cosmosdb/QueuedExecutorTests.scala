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
import akka.stream.ActorMaterializer
import common.{LoggedFunction, WskActorSystem}
import kamon.metric.{AtomicLongGauge, MeasurementUnit}
import org.apache.openwhisk.utils.retry
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

@RunWith(classOf[JUnitRunner])
class QueuedExecutorTests extends FlatSpec with Matchers with WskActorSystem with ScalaFutures {
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  val promiseDelay = 100.milliseconds
  def resolveDelayed(p: Promise[Unit], delay: FiniteDuration = promiseDelay) =
    akka.pattern.after(delay, actorSystem.scheduler) {
      p.success(())
      Future.successful(())
    }

  behavior of "QueuedExecutor"

  it should "complete queued values with the thrown exception" in {
    val executor = new QueuedExecutor[Int, Int](2, 1)(_ => Future.failed(new Exception))

    val r1 = executor.put(1)
    val r2 = executor.put(2)

    r1.failed.futureValue shouldBe an[Exception]
    r2.failed.futureValue shouldBe an[Exception]

    // the executor is still intact
    val r3 = executor.put(3)
    val r4 = executor.put(4)

    r3.failed.futureValue shouldBe an[Exception]
    r4.failed.futureValue shouldBe an[Exception]
  }

  it should "wait for executions to finish on close" in {
    val count = 5

    val ps = Seq.fill(count)(Promise[Unit]())
    val queuedPromises = mutable.Queue(ps: _*)

    val queuedOperation = LoggedFunction((i: Int) => {
      queuedPromises.dequeue().future.map(_ => i + 1)
    })

    val gauge = new AtomicLongGauge("", Map.empty, MeasurementUnit.none)
    val executor = new QueuedExecutor[Int, Int](100, 1, Some(gauge))(queuedOperation)

    val values = 1 to count
    val results = values.map(executor.put)

    // First entry
    retry(queuedOperation.calls should have size 1)
    gauge.snapshot().value shouldBe count

    ps.head.success(())
    retry(queuedOperation.calls should have size 2)

    //Trigger close
    val closeF = executor.close()

    //Let each operation complete now
    ps.foreach(_.trySuccess(()))

    //Wait for executor to close
    closeF.futureValue shouldBe Done

    queuedOperation.calls should have size count
    Future.sequence(results).futureValue.sum shouldBe values.map(_ + 1).sum
    gauge.snapshot().value shouldBe 0
  }
}
