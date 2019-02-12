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

package org.apache.openwhisk.core.connector.test

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable.Buffer
import scala.concurrent.Future
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.actor.PoisonPill
import akka.actor.Props
import akka.testkit.TestKit
import common.StreamLogging
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.connector.MessageFeed._
import org.apache.openwhisk.utils.retry

@RunWith(classOf[JUnitRunner])
class MessageFeedTests
    extends FlatSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with StreamLogging {

  val system = ActorSystem("MessageFeedTestSystem")
  val actorsToDestroyAfterEach: Buffer[ActorRef] = Buffer.empty

  override def afterEach() = {
    actorsToDestroyAfterEach.foreach { _ ! PoisonPill }
    actorsToDestroyAfterEach.clear()
  }

  override def afterAll() = TestKit.shutdownActorSystem(system)

  case class Connector(autoStart: Boolean = true) extends TestKit(system) {
    val peekCount = new AtomicInteger()

    val consumer = new TestConnector("feedtest", 4, true) {
      override def peek(duration: FiniteDuration, retry: Int = 0) = {
        peekCount.incrementAndGet()
        super.peek(duration)
      }
    }

    val sentCount = new AtomicInteger()

    def fill(n: Int) = {
      val msgs = (1 to n).map { _ =>
        new Message {
          override def serialize = {
            sentCount.incrementAndGet().toString
          }
          override def toString = {
            s"message${sentCount.get}"
          }
        }
      }
      consumer.send(msgs)
    }

    val receivedCount = new AtomicInteger()

    def handler(bytes: Array[Byte]): Future[Unit] = {
      Future.successful(receivedCount.incrementAndGet())
    }

    val fsm = childActorOf(
      Props(new MessageFeed("test", logging, consumer, consumer.maxPeek, 200.milliseconds, handler, autoStart)))

    actorsToDestroyAfterEach += (fsm, testActor)

    def monitorTransitionsAndStart() = {
      fsm ! SubscribeTransitionCallBack(testActor)
      expectMsg(CurrentState(fsm, Idle))
      fsm ! Ready
      expectMsg(Transition(fsm, Idle, FillingPipeline))
      this
    }
  }

  def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

  it should "wait for ready before accepting messages" in {
    val connector = Connector(autoStart = false)
    connector.fsm ! SubscribeTransitionCallBack(connector.testActor)

    // start idle
    connector.expectMsg(CurrentState(connector.fsm, Idle))

    // stay until received ready
    connector.fsm ! FSM.StateTimeout // should be ignored
    connector.fsm ! Processed // should be ignored
    Thread.sleep(500.milliseconds.toMillis)
    connector.peekCount.get shouldBe 0

    // start filling
    connector.fsm ! Ready
    connector.expectMsg(Transition(connector.fsm, Idle, FillingPipeline))
    retry(connector.peekCount.get should be > 0)
  }

  it should "auto start and start polling for messages" in {
    val connector = Connector(autoStart = true)
    // automatically start filling
    retry(connector.peekCount.get should be > 0, 5, Some(200.milliseconds))
  }

  it should "stop polling for messages when the pipeline is full" in {
    val connector = Connector(autoStart = false).monitorTransitionsAndStart()
    // push enough to cause pipeline to exceed fill mark
    connector.fill(connector.consumer.maxPeek * 2 + 1)
    retry(connector.peekCount.get should be > 0)
    retry(connector.receivedCount.get shouldBe connector.consumer.maxPeek, 10, Some(200.milliseconds))

    val peeks = connector.peekCount.get
    connector.expectMsg(Transition(connector.fsm, FillingPipeline, DrainingPipeline))

    connector.peekCount.get shouldBe peeks
    connector.expectNoMessage(500.milliseconds)
  }

  it should "transition from drain to fill mode" in {
    val connector = Connector(autoStart = false).monitorTransitionsAndStart()
    println(connector.fsm.toString())
    // push enough to cause pipeline to exceed fill mark
    val sendCount = connector.consumer.maxPeek * 2 + 2
    connector.fill(sendCount)
    retry(connector.peekCount.get should be > 0)
    retry(connector.receivedCount.get shouldBe connector.consumer.maxPeek, 10, Some(200.milliseconds))

    val peeks = connector.peekCount.get
    connector.expectMsg(Transition(connector.fsm, FillingPipeline, DrainingPipeline))

    // stay in drain mode, no more peeking
    timeout(connector.fsm) // should be ignored
    connector.expectNoMessage(500.milliseconds)
    connector.peekCount.get shouldBe peeks // no new reads

    // expecting overflow of 2 in the queue, which is true if all expected messages were sent
    retry(connector.sentCount.get shouldBe sendCount, 5, Some(200.milliseconds))

    // drain one, should stay in draining state
    connector.fsm ! Processed
    connector.expectNoMessage(500.milliseconds)
    connector.peekCount.get shouldBe peeks // no new reads

    // back to fill mode
    connector.fsm ! Processed
    connector.expectMsg(Transition(connector.fsm, DrainingPipeline, FillingPipeline))
    retry(connector.peekCount.get should be >= (peeks + 1))

    // should send back to drain mode
    connector.fill(1)
    connector.expectMsg(Transition(connector.fsm, FillingPipeline, DrainingPipeline))

    connector.expectNoMessage(500.milliseconds)
  }
}
