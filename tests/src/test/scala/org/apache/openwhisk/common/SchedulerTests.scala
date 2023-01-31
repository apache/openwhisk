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

package org.apache.openwhisk.common

import java.time.Instant

import scala.collection.mutable.Buffer
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.PoisonPill
import common.StreamLogging
import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class SchedulerTests extends FlatSpec with Matchers with WskActorSystem with StreamLogging {

  val timeBetweenCalls = 50 milliseconds
  val callsToProduce = 5
  val schedulerSlack = 100 milliseconds

  /**
   * Calculates the duration between two consecutive elements
   *
   * @param times the points in time to calculate the difference between
   * @return duration between each element of the given sequence
   */
  def calculateDifferences(times: Seq[Instant]) = {
    times sliding (2) map {
      case Seq(a, b) => Duration.fromNanos(java.time.Duration.between(a, b).toNanos)
    } toList
  }

  /**
   * Waits for the calls to be scheduled and executed. Adds one call-interval as additional slack
   */
  def waitForCalls(calls: Int = callsToProduce, interval: FiniteDuration = timeBetweenCalls) =
    Thread.sleep((calls + 1) * interval toMillis)

  behavior of "A WaitAtLeast Scheduler"

  ignore should "be killable by sending it a poison pill" in {
    var callCount = 0
    val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
      callCount += 1
      Future successful true
    }

    waitForCalls()
    // This is equal to a scheduled ! PoisonPill
    val shutdownTimeout = 10.seconds
    Await.result(akka.pattern.gracefulStop(scheduled, shutdownTimeout, PoisonPill), shutdownTimeout)

    val countAfterKill = callCount
    callCount should be >= callsToProduce

    waitForCalls()

    callCount shouldBe countAfterKill
  }

  it should "throw an exception when passed a negative duration" in {
    an[IllegalArgumentException] should be thrownBy Scheduler.scheduleWaitAtLeast(-100 milliseconds) { () =>
      Future.successful(true)
    }
  }

  it should "wait at least the given interval between scheduled calls" in {
    val calls = Buffer[Instant]()

    val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
      calls += Instant.now
      Future successful true
    }

    waitForCalls()
    scheduled ! PoisonPill

    val differences = calculateDifferences(calls.toSeq)
    withClue(s"expecting all $differences to be >= $timeBetweenCalls") {
      differences.forall(_ >= timeBetweenCalls)
    }
  }

  it should "stop the scheduler if an uncaught exception is thrown by the passed closure" in {
    var callCount = 0
    val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
      callCount += 1
      throw new Exception
    }

    waitForCalls()

    callCount shouldBe 1
  }

  it should "log scheduler halt message with tid" in {
    implicit val transid = TransactionId.testing
    val msg = "test threw an exception"

    stream.reset()
    val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
      throw new Exception(msg)
    }

    waitForCalls()
    stream.toString.split(" ").drop(1).mkString(" ") shouldBe {
      s"[ERROR] [${transid.root}] [] [Scheduler] halted because $msg\n"
    }
  }

  it should "not stop the scheduler if the future from the closure is failed" in {
    var callCount = 0

    val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
      callCount += 1
      Future failed new Exception
    }

    waitForCalls()
    scheduled ! PoisonPill

    callCount shouldBe callsToProduce
  }

  "A WaitAtMost Scheduler" should "wait at most the given interval between scheduled calls" in {
    val calls = Buffer[Instant]()
    val timeBetweenCalls = 200 milliseconds
    val computationTime = 100 milliseconds

    val scheduled = Scheduler.scheduleWaitAtMost(timeBetweenCalls) { () =>
      calls += Instant.now
      akka.pattern.after(computationTime, actorSystem.scheduler)(Future.successful(true))
    }

    waitForCalls(interval = timeBetweenCalls)
    scheduled ! PoisonPill

    val differences = calculateDifferences(calls.toSeq)
    withClue(s"expecting all $differences to be <= $timeBetweenCalls") {
      differences should not be 'empty
      differences.forall(_ <= timeBetweenCalls + schedulerSlack)
    }
  }

  it should "delay initial schedule by given duration" in {
    val timeBetweenCalls = 200 milliseconds
    val initialDelay = 1.second
    var callCount = 0

    val scheduled = Scheduler.scheduleWaitAtMost(timeBetweenCalls, initialDelay) { () =>
      callCount += 1
      Future successful true
    }

    try {
      Thread.sleep(initialDelay.toMillis)
      callCount should be <= 1

      Thread.sleep(2 * timeBetweenCalls.toMillis)
      callCount should be > 1
    } finally {
      scheduled ! PoisonPill
    }
  }

  it should "perform work immediately when requested" in {
    val timeBetweenCalls = 200 milliseconds
    val initialDelay = 1.second
    var callCount = 0

    val scheduled = Scheduler.scheduleWaitAtMost(timeBetweenCalls, initialDelay) { () =>
      callCount += 1
      Future successful true
    }

    try {
      Thread.sleep(2 * timeBetweenCalls.toMillis)
      callCount should be(0)

      scheduled ! Scheduler.WorkOnceNow
      Thread.sleep(timeBetweenCalls.toMillis)
      callCount should be(1)
    } finally {
      scheduled ! PoisonPill
    }
  }

  it should "not wait when the closure takes longer than the interval" in {
    val calls = Buffer[Instant]()
    val timeBetweenCalls = 200 milliseconds
    val computationTime = 300 milliseconds

    val scheduled = Scheduler.scheduleWaitAtMost(timeBetweenCalls) { () =>
      calls += Instant.now
      akka.pattern.after(computationTime, actorSystem.scheduler)(Future.successful(true))
    }

    waitForCalls(interval = timeBetweenCalls)
    scheduled ! PoisonPill

    val differences = calculateDifferences(calls.toSeq)
    withClue(s"expecting all $differences to be <= $computationTime") {
      differences should not be 'empty
      differences.forall(_ <= computationTime + schedulerSlack)
    }
  }
}
