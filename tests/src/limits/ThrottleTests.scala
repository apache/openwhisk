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
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.WhiskProperties
import common.Wsk
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.WhiskConfig.actionInvokeConcurrentLimit
import whisk.core.WhiskConfig.actionInvokePerMinuteLimit
import whisk.core.WhiskConfig.invokerCoreShare
import whisk.core.WhiskConfig.invokerNumCore
import whisk.core.WhiskConfig.triggerFirePerMinuteLimit
import whisk.utils.ExecutionContextFactory

@RunWith(classOf[JUnitRunner])
class ThrottleTests
    extends FlatSpec
    with TestHelpers
    with WskTestHelpers
    with WskActorSystem
    with ScalaFutures
    with Matchers {

    // use an infinite thread pool so that activations do not wait to send the activation requests
    override implicit val executionContext = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()

    implicit val testConfig = PatienceConfig(5.minutes)
    implicit val wskprops = WskProps()
    val wsk = new Wsk(usePythonCLI = false)
    val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

    val throttleWindow = 1.minute
    val maximumInvokesPerMinute = WhiskProperties.getProperty(actionInvokePerMinuteLimit).toInt
    val maximumFiringsPerMinute = WhiskProperties.getProperty(triggerFirePerMinuteLimit).toInt
    val maximumConcurrentInvokes = WhiskProperties.getProperty(actionInvokeConcurrentLimit).toInt
    val invokerCores = WhiskProperties.getProperty(invokerNumCore).toLong
    val invokerShare = WhiskProperties.getProperty(invokerCoreShare).toLong

    val rateMessage = "Too many requests from user"
    val concurrencyMessage = "The user has sent too many requests in a given amount of time."

    /**
     * Extracts the number of throttled results from a sequence of <code>RunResult</code>
     *
     * @param results the sequence of results
     * @param message the message to determine the type of throttling
     */
    def throttledActivations(results: List[RunResult], message: String) = {
        val count = results.count { result =>
            result.exitCode == TestUtils.THROTTLED && result.stderr.contains(message)
        }
        println(s"number of throttled activations: $count out of ${results.length}")
        count
    }

    /**
     * Waits until all successful activations are finished. Used to prevent the testcases from
     * leaking activations.
     *
     * @param results the sequence of results from invocations or firings
     */
    def waitForActivations(results: ParSeq[RunResult]) = results.foreach { result =>
        if (result.exitCode == SUCCESS_EXIT) {
            //println("waiting for " + result.stdout.trim)
            withActivation(wsk.activation, result, totalWait = 5.minutes)(identity)
        }
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
     * Invokes the given action up to 'count' times until one of the invokes is throttled.
     *
     * @param count maximum invocations to make
     */
    def untilThrottled(count: Int, retries: Int = 3)(run: () => RunResult): List[RunResult] = {
        val p = Promise[Unit]

        val results = List.fill(count)(Future {
            if (!p.isCompleted) {
                val rr = run()
                if (rr.exitCode == THROTTLED) {
                    p.trySuccess(())
                }
                Some(rr)
            } else {
                println("already throttled, skipping additional runs")
                None
            }
        })

        val finished = Future.sequence(results).futureValue.flatten
        // some activations may need to be retried
        val failed = finished filter {
            rr => rr.exitCode != SUCCESS_EXIT && rr.exitCode != THROTTLED
        }

        println(s"Executed ${finished.length} requests, maximum was $count, need to retry ${failed.length} (retries left: $retries)")
        if (failed.isEmpty || retries <= 0) {
            finished
        } else {
            finished ++ untilThrottled(failed.length, retries - 1)(run)
        }
    }

    behavior of "Throttles"

    it should "throttle multiple activations of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkPerMinuteActionThrottle"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, defaultAction)
            }

            // invokes per minute * 2 because the current minute could advance which resets the throttle
            val results = untilThrottled(maximumInvokesPerMinute * 2 + 1) { () =>
                wsk.action.invoke(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterInvokes = Instant.now
            try {
                val throttledCount = throttledActivations(results, rateMessage)
                throttledCount should be <= (results.length - maximumInvokesPerMinute)
                throttledCount should be > 0
            } finally {
                waitForActivations(results.par)
                val alreadyWaited = durationBetween(afterInvokes, Instant.now)
                settleThrottles(alreadyWaited)
            }
    }

    it should "throttle multiple activations of one trigger" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "checkPerMinuteTriggerThrottle"
            assetHelper.withCleaner(wsk.trigger, name) {
                (trigger, _) => trigger.create(name)
            }

            // invokes per minute * 2 because the current minute could advance which resets the throttle
            val results = untilThrottled(maximumFiringsPerMinute * 2 + 1) { () =>
                wsk.trigger.fire(name, Map("payload" -> "testWord".toJson), expectedExitCode = DONTCARE_EXIT)
            }
            val afterFirings = Instant.now

            try {
                val throttledCount = throttledActivations(results, rateMessage)
                throttledCount should be <= (results.length - maximumFiringsPerMinute)
                throttledCount should be > 0
            } finally {
                // no need to wait for activations of triggers since they consume no resources
                // (because there is no rule attached in this test)
                val alreadyWaited = durationBetween(afterFirings, Instant.now)
                settleThrottles(alreadyWaited)
            }
    }

    it should "throttle 'concurrent' activations of one action" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            require(maximumConcurrentInvokes + 1 <= maximumInvokesPerMinute,
                "this test will not work if 'maximumConcurrentInvokes + 1' is not <= 'maximumInvokesPerMinute'")

            val invokeCapacity = (WhiskProperties.numberOfInvokers * invokerCores * invokerShare).toInt
            val concurrentInvokes = 2 * invokeCapacity

            val waitingInvokes = maximumConcurrentInvokes - concurrentInvokes
            assert(waitingInvokes >= 0)

            val name = "checkConcurrentActionThrottle"
            assetHelper.withCleaner(wsk.action, name) {
                val timeoutAction = Some(TestUtils.getTestActionFilename("timeout.js"))
                (action, _) => action.create(name, timeoutAction)
            }

            // compute number of activations to saturate invokers
            val saturatingActionTimeout = 16.seconds
            val fastActionTimeout = 100.milliseconds

            val beforeInvokes = Instant.now
            val saturatingInvokes = untilThrottled(concurrentInvokes) { () =>
                wsk.action.invoke(name, Map("payload" -> saturatingActionTimeout.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }

            // remaining invokes just need to be in the queue, their duration is not relevant so set as fast as possible
            val remainingInvokes = untilThrottled(waitingInvokes) { () =>
                wsk.action.invoke(name, Map("payload" -> fastActionTimeout.toMillis.toJson), expectedExitCode = DONTCARE_EXIT)
            }

            val allInvokes = saturatingInvokes ++ remainingInvokes

            try {
                withClue("should not already be throttled") {
                    throttledActivations(allInvokes, concurrencyMessage) shouldBe 0
                }

                // wait for book keepers to synch up and invoke one more action
                // which should be hit the limit now; synchronization is every 5 seconds
                println("waiting for throttle to kick in")
                Thread.sleep((2 * 5.seconds + 1.seconds).toMillis)

                println("invoking action that should be rejected")
                val result = wsk.action.invoke(name, Map("payload" -> fastActionTimeout.toMillis.toJson), expectedExitCode = THROTTLED)
                result.stderr should include(concurrencyMessage)
                println("throttled as expected")
            } finally {
                val wait = ((saturatingActionTimeout * concurrentInvokes) + (fastActionTimeout * waitingInvokes)) / invokeCapacity
                println(s"waiting for activations, may take up to ${wait.toSeconds} seconds")
                Thread.sleep(wait.toMillis)
                waitForActivations(allInvokes.par)

                val alreadyWaited = durationBetween(beforeInvokes, Instant.now)
                settleThrottles(alreadyWaited)
            }
    }
}
