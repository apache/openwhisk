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
package limits

import java.time.Instant

import scala.collection.parallel.immutable.ParSeq
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import common.WhiskProperties

@RunWith(classOf[JUnitRunner])
class ThrottleTests
    extends FlatSpec
    with TestHelpers
    with WskTestHelpers
    with WskActorSystem
    with ScalaFutures
    with Matchers {

    implicit val testConfig = PatienceConfig(5.minutes)

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

    val throttleWindow = 1.minute
    val maximumInvokesPerMinute = WhiskProperties.getProperty("limits.actions.invokes.perMinute").toInt
    val maximumFiringsPerMinute = WhiskProperties.getProperty("limits.triggers.fires.perMinute").toInt
    val maximumConcurrentInvokes = WhiskProperties.getProperty("limits.actions.invokes.concurrent").toInt

    val rateMessage = "Too many requests from user"
    val concurrencyMessage = "The user has sent too many requests in a given amount of time."

    /**
     * Extracts the number of throttled results from a sequence of <code>RunResult</code>
     *
     * @param results the sequence of results
     * @param message the message to determine the type of throttling
     */
    def throttledActivations(results: List[RunResult], message: String) = results.count { result =>
        result.exitCode == TestUtils.THROTTLED && result.stderr.contains(message)
    }

    /**
     * Waits until all successful activations are finished. Used to prevent the testcases from
     * leaking activations.
     *
     * @param results the sequence of results from invocations or firings
     */
    def waitForActivations(results: ParSeq[RunResult]) = results.foreach { result =>
        if (result.exitCode == SUCCESS_EXIT) withActivation(wsk.activation, result, totalWait = 5.minutes)(identity)
    }

    /**
     * Settles throttles of 1 minute. Waits up to 1 minute depending on the time already waited.
     *
     * @param waitedAlready the time already gone after the last invoke or fire
     */
    def settleThrottles(waitedAlready: FiniteDuration) = {
        val timeToWait = (throttleWindow - waitedAlready).max(Duration.Zero)
        println(s"Waiting for ${timeToWait.toSeconds} seconds, already waited for ${waitedAlready.toSeconds} seconds")
        Thread.sleep(timeToWait.toMillis)
    }

    /**
     * Calculates the <code>Duration</code> between two <code>Instant</code>
     *
     * @param start the Instant something started
     * @param end the Instant something ended
     */
    def durationBetween(start: Instant, end: Instant) = Duration.fromNanos(java.time.Duration.between(start, end).toNanos)

    /**
     * Invokes the the given action with the given payload as fast as it can
     * until one of the invokes is throttled. Invokes the given amount of times
     * at maximum.
     *
     * @param name name of the action
     * @param payload payload parameter to pass
     * @param count maximum invocations to make
     */
    def untilThrottled(count: Int)(run: () => RunResult) = {
        val p = Promise[Unit]
        val results = List.fill(count)(Future {
            if (!p.isCompleted) {
                val rr = run()
                if (rr.exitCode == THROTTLED) p.trySuccess(())
                Some(rr)
            } else {
                None
            }
        })
        val finished = Future.sequence(results).futureValue.flatten
        println(s"Executed ${finished.length} requests, maximum was $count")
        finished
    }

    "Throttles" should "throttle multiple invokes of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkThrottleAction"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, defaultAction)
            }

            // invokes per minute * 2 because the current minute could advance which resets the throttle
            val results = untilThrottled(maximumInvokesPerMinute * 2 + 1) { () =>
                wsk.action.invoke(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterInvokes = Instant.now

            waitForActivations(results.par)
            throttledActivations(results, rateMessage) should be > 0

            val alreadyWaited = durationBetween(afterInvokes, Instant.now)
            settleThrottles(alreadyWaited)
    }

    it should "throttle multiple invokes of one trigger" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkThrottleTrigger"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) => trigger.create(name)
            }

            // invokes per minute * 2 because the current minute could advance which resets the throttle
            val results = untilThrottled(maximumInvokesPerMinute * 2 + 1) { () =>
                wsk.trigger.fire(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterFirings = Instant.now

            waitForActivations(results.par)
            throttledActivations(results, rateMessage) should be > 0

            val alreadyWaited = durationBetween(afterFirings, Instant.now)
            settleThrottles(alreadyWaited)
    }

    it should "throttle 'concurrent' invokes of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkThrottleAction"
            val timeoutAction = Some(TestUtils.getTestActionFilename("timeout.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, timeoutAction)
            }

            val slowInvokes = maximumConcurrentInvokes * 0.6
            val fastInvokes = maximumConcurrentInvokes * 0.4 + 1

            // Keep queue from draining with these
            val slowResults = untilThrottled(slowInvokes.toInt) { () =>
                wsk.action.invoke(name, Map("payload" -> 15.seconds.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }

            // Create queue length quickly, drain fast
            val fastResults = untilThrottled(fastInvokes.toInt) { () =>
                wsk.action.invoke(name, Map("payload" -> 10.milliseconds.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }

            // Sleep 5 seconds to let the background thread get the newest values (refreshes every 2 seconds)
            Thread.sleep(5.seconds.toMillis)

            // start 1 invoke less than the maximum per minute to avoid getting rate throttled
            val throttledInvokes = maximumInvokesPerMinute - slowInvokes.toInt - fastInvokes.toInt - 1
            val endResults = untilThrottled(throttledInvokes) { () =>
                wsk.action.invoke(name, Map("payload" -> 10.milliseconds.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterInvokes = Instant.now

            val combinedResults = slowResults ++ fastResults ++ endResults
            waitForActivations(combinedResults.par)
            throttledActivations(combinedResults, concurrencyMessage) should be > 0

            val alreadyWaited = durationBetween(afterInvokes, Instant.now)
            settleThrottles(alreadyWaited)
    }
}
