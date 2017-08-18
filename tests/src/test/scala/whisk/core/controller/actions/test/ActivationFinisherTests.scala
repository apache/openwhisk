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

package whisk.core.controller.actions.test

import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.WskActorSystem
import spray.json._
import whisk.common.TransactionId
import whisk.core.controller.actions.ActivationFinisher
import whisk.core.entity._
import whisk.core.entity.ActivationResponse
import whisk.core.entity.size.SizeInt
import whisk.core.database.NoDocumentException
import akka.testkit.TestProbe
import whisk.common.Scheduler
import akka.actor.PoisonPill

@RunWith(classOf[JUnitRunner])
class ActivationFinisherTests
    extends FlatSpec
    with BeforeAndAfterEach
    with Matchers
    with WskActorSystem
    with StreamLogging {

  implicit val tid = TransactionId.testing

  val activation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId(),
    start = Instant.now(),
    end = Instant.now(),
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(2)))),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  var activationLookupCounter = 0
  @volatile var activationResult: Option[Throwable] = None

  def activationLookup(): Future[WhiskActivation] = {
    activationLookupCounter += 1
    activationResult.map(Future.failed(_)).getOrElse(Future.successful(activation))
  }

  override def beforeEach() = {
    activationLookupCounter = 0
    activationResult = None
  }

  behavior of "activation finisher"
  override lazy val printstream = Console.out
  val slowPoll = 200.milliseconds
  val fastPoll = Seq()

  it should "poll until promise is completed" in {
    activationResult = Some(NoDocumentException(""))
    val (promise, poller, finisher) = ActivationFinisher.props(activationLookup, slowPoll, fastPoll)

    val testProbePoller = TestProbe()
    val testProbeFinisher = TestProbe()
    testProbePoller.watch(poller)
    testProbeFinisher.watch(finisher)

    val slowPollWorkWindow = (slowPoll * 3) + (slowPoll / 2)
    Thread.sleep(slowPollWorkWindow.toMillis)
    activationLookupCounter should (be >= 2 and be <= 3)

    // should terminate the parent finisher and child poller on completion
    promise.trySuccess(Right(activation))

    testProbePoller.expectTerminated(poller, 1.second)
    testProbeFinisher.expectTerminated(finisher, 1.second)
  }

  it should "complete promise from poller" in {
    val (promise, poller, finisher) = ActivationFinisher.props(activationLookup, slowPoll, fastPoll)

    val testProbePoller = TestProbe()
    val testProbeFinisher = TestProbe()
    testProbePoller.watch(poller)
    testProbeFinisher.watch(finisher)

    val slowPollWorkWindow = (slowPoll * 2) + (slowPoll / 1)
    Thread.sleep(slowPollWorkWindow.toMillis)
    activationLookupCounter should be(1)

    testProbePoller.expectTerminated(poller, 1.second)
    testProbeFinisher.expectTerminated(finisher, 1.second)

    promise shouldBe 'completed
  }

  it should "finish when receiving corresponding message" in {
    activationResult = Some(NoDocumentException(""))
    val (promise, poller, finisher) = ActivationFinisher.props(activationLookup, slowPoll, fastPoll)

    val testProbePoller = TestProbe()
    val testProbeFinisher = TestProbe()
    testProbePoller.watch(poller)
    testProbeFinisher.watch(finisher)

    val slowPollWorkWindow = (slowPoll * 2) + (slowPoll / 1)
    Thread.sleep(slowPollWorkWindow.toMillis)
    activationLookupCounter should (be >= 1 and be <= 2)

    // should terminate the parent finisher and child poller once message is received
    finisher ! ActivationFinisher.Finish(Right(activation))

    testProbePoller.expectTerminated(poller, 1.second)
    testProbeFinisher.expectTerminated(finisher, 1.second)

    promise shouldBe 'completed
  }

  it should "poll pre-emptively" in {
    activationResult = Some(NoDocumentException(""))
    val slowPoll = 600.milliseconds
    val fastPoll = Seq(100.milliseconds, 200.milliseconds)
    val (promise, poller, finisher) = ActivationFinisher.props(activationLookup, slowPoll, fastPoll)

    val testProbePoller = TestProbe()
    val testProbeFinisher = TestProbe()
    testProbePoller.watch(poller)
    testProbeFinisher.watch(finisher)

    Thread.sleep(500.milliseconds.toMillis)
    activationLookupCounter should be(0)

    // should cause polls
    finisher ! Scheduler.WorkOnceNow
    Thread.sleep(500.milliseconds.toMillis)
    activationLookupCounter should be(3)

    finisher ! PoisonPill

    testProbePoller.expectTerminated(poller, 1.second)
    testProbeFinisher.expectTerminated(finisher, 1.second)
  }

}
