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

package whisk.common

import java.time.Instant

import scala.collection.mutable.Buffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.ActorSystem
import akka.actor.PoisonPill

@RunWith(classOf[JUnitRunner])
class SchedulerTests extends FlatSpec with Matchers {
    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher

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
    def waitForCalls(calls: Int = callsToProduce, interval: FiniteDuration = timeBetweenCalls) = Thread.sleep((calls + 1) * interval toMillis)

    "A WaitAtLeast Scheduler" should "be killable by sending it a poison pill" in {
        var callCount = 0
        val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
            callCount += 1
            Future successful true
        }

        waitForCalls()
        scheduled ! PoisonPill

        val countAfterKill = callCount
        callCount should be >= callsToProduce

        waitForCalls()

        callCount shouldBe countAfterKill
    }

    it should "throw an expection when passed a negative duration" in {
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

        val differences = calculateDifferences(calls)
        withClue(s"expecting all $differences to be >= $timeBetweenCalls") {
            differences.forall(_ >= timeBetweenCalls)
        }
    }

    it should "stop the scheduler if an uncaught exception is thrown by the passed closure" in {
        var callCount = 0
        val scheduled = Scheduler.scheduleWaitAtLeast(timeBetweenCalls) { () =>
            callCount += 1
            throw new Exception
            Future successful true
        }

        waitForCalls()

        callCount shouldBe 1
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
            akka.pattern.after(computationTime, system.scheduler)(Future.successful(true))
        }

        waitForCalls(interval = timeBetweenCalls)
        scheduled ! PoisonPill

        val differences = calculateDifferences(calls)
        withClue(s"expecting all $differences to be <= $timeBetweenCalls") {
            differences.forall(_ <= timeBetweenCalls + schedulerSlack)
        }
    }

    it should "not wait when the closure takes longer than the interval" in {
        val calls = Buffer[Instant]()
        val timeBetweenCalls = 200 milliseconds
        val computationTime = 300 milliseconds

        val scheduled = Scheduler.scheduleWaitAtMost(timeBetweenCalls) { () =>
            calls += Instant.now
            akka.pattern.after(computationTime, system.scheduler)(Future.successful(true))
        }

        waitForCalls(interval = timeBetweenCalls)
        scheduled ! PoisonPill

        val differences = calculateDifferences(calls)
        withClue(s"expecting all $differences to be <= $computationTime") {
            differences.forall(_ <= computationTime + schedulerSlack)
        }
    }
}
