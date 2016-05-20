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

    val minimumTimeBetweenCalls = 50 milliseconds
    val callsToProduce = 5

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
    def waitForCalls() = Thread.sleep((callsToProduce + 1) * minimumTimeBetweenCalls toMillis)

    "Scheduler" should "be killable by sending it a poison pill" in {
        var callCount = 0
        val scheduled = Scheduler.scheduleWaitAtLeast(minimumTimeBetweenCalls) { () =>
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

        val scheduled = Scheduler.scheduleWaitAtLeast(minimumTimeBetweenCalls) { () =>
            calls += Instant.now
            Future successful true
        }

        waitForCalls()
        scheduled ! PoisonPill

        val differences = calculateDifferences(calls)
        all(differences) should be > minimumTimeBetweenCalls
    }

    it should "stop the scheduler if an uncaught exception is thrown by the passed closure" in {
        var callCount = 0
        val scheduled = Scheduler.scheduleWaitAtLeast(minimumTimeBetweenCalls) { () =>
            callCount += 1
            throw new Exception
            Future successful true
        }

        waitForCalls()

        callCount shouldBe 1
    }

    it should "not stop the scheduler if the future from the closure is failed" in {
        var callCount = 0

        val scheduled = Scheduler.scheduleWaitAtLeast(minimumTimeBetweenCalls) { () =>
            callCount += 1
            Future failed new Exception
        }

        waitForCalls()
        scheduled ! PoisonPill

        callCount shouldBe callsToProduce
    }
}
