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

package org.apache.openwhisk.core.scheduler.queue.test

import java.util.concurrent.atomic.AtomicInteger
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import common.StreamLogging
import org.apache.openwhisk.core.entity.{EntityName, EntityPath, FullyQualifiedEntityName, SemVer}
import org.apache.openwhisk.core.scheduler.SchedulingConfig
import org.apache.openwhisk.core.scheduler.queue._
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.junit.JUnitRunner
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class SchedulingDecisionMakerTests
    extends TestKit(ActorSystem("SchedulingDecisionMakerTests"))
    with AnyFlatSpecLike
    with ScalaFutures
    with Matchers
    with StreamLogging {

  behavior of "SchedulingDecisionMaker"

  implicit val ec = system.dispatcher

  val testNamespace = "test-namespace"
  val testAction = "test-action"
  val action = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 1)))

  val schedulingConfig = SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, false, 1.5)

  it should "decide pausing when the limit is less than equal to 0" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None,
      namespaceLimit = 0,
      actionLimit = 0, // limit is 0,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(Pausing, 0))
  }

  it should "skip decision if the state is already Flushing when the limit is <= 0" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None,
      namespaceLimit = 0,
      actionLimit = 0, // limit is 0
      maxActionConcurrency = 1,
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  it should "skip decision at any time if there is no message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))

    // For Throttled states, there will be always at least one message to disable the throttling
    val states = List(Running, Idle, Flushing, Removing, Removed)
    val testProbe = TestProbe()

    states.foreach { state =>
      val msg = QueueSnapshot(
        initialized = false,
        incomingMsgCount = new AtomicInteger(0),
        currentMsgCount = 0,
        existingContainerCount = 1,
        inProgressContainerCount = 2,
        staleActivationNum = 0,
        existingContainerCountInNamespace = 1,
        inProgressContainerCountInNamespace = 2,
        averageDuration = None, // No average duration available
        namespaceLimit = 10,
        actionLimit = 10,
        maxActionConcurrency = 1,
        stateName = state,
        recipient = testProbe.ref)

      decisionMaker ! msg

      testProbe.expectNoMessage()
    }
  }

  it should "skip decision at any time if there is no message even with avg duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val states = List(Running, Idle, Flushing, Removing, Removed)
    val testProbe = TestProbe()

    states.foreach { state =>
      val msg = QueueSnapshot(
        initialized = false,
        incomingMsgCount = new AtomicInteger(0),
        currentMsgCount = 0,
        existingContainerCount = 3,
        inProgressContainerCount = 5,
        staleActivationNum = 0,
        existingContainerCountInNamespace = 5,
        inProgressContainerCountInNamespace = 8,
        averageDuration = Some(1.0), // Some average duration available
        namespaceLimit = 20,
        actionLimit = 20,
        maxActionConcurrency = 1,
        stateName = state,
        recipient = testProbe.ref)

      decisionMaker ! msg

      testProbe.expectNoMessage()
    }
  }

  it should "enable namespace throttling with dropping msg when there is not enough capacity, no container, and namespace over-provision disabled" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0, // there is no container for this action
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 2,
      actionLimit = 2,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an initial container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = true), 0))
  }

  it should "enable namespace throttling without dropping msg when there is not enough capacity but are some containers and namespace over-provision disabled" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1, // there are some containers for this action
      inProgressContainerCount = 1,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 2, // this value includes the count of this action as well.
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create more containers
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 0))
  }

  it should "add one container when there is no container, and namespace over-provision has capacity" in {
    val schedulingConfigNamespaceOverProvisioning =
      SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, true, 1.5)
    val decisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfigNamespaceOverProvisioning))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0, // there is no container for this action
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 2,
      actionLimit = 2,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an initial container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "enable namespace throttling with dropping msg when there is no container, and namespace over-provision has no capacity" in {
    val schedulingConfigNamespaceOverProvisioning =
      SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, true, 1.0)
    val decisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfigNamespaceOverProvisioning))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0, // there is no container for this action
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 2,
      actionLimit = 2,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an initial container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = true), 0))
  }

  it should "disable namespace throttling when namespace over-provision has capacity again" in {
    val schedulingConfigNamespaceOverProvisioning =
      SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, true, 1.1)
    val decisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfigNamespaceOverProvisioning))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1, // there is one container for this action
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 2,
      actionLimit = 2,
      maxActionConcurrency = 1,
      stateName = NamespaceThrottled,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an initial container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(DisableNamespaceThrottling, 0))
  }

  it should "enable namespace throttling without dropping msg when there is a container, and namespace over-provision has no additional capacity" in {
    val schedulingConfigNamespaceOverProvisioning =
      SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, true, 1.0)
    val decisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfigNamespaceOverProvisioning))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 2,
      actionLimit = 2,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create an additional container so enable throttling and drop messages.
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 0))
  }

  it should "not enable namespace throttling when there is not enough capacity but are some containers and namespace over-provision is enabled with capacity" in {
    val schedulingConfigNamespaceOverProvisioning =
      SchedulingConfig(100.milliseconds, 100.milliseconds, 10.seconds, true, 1.5)
    val decisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfigNamespaceOverProvisioning))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1, // there are some containers for this action
      inProgressContainerCount = 1,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2, // but there are already 2 containers in this namespace
      inProgressContainerCountInNamespace = 2, // this value includes the count of this action as well.
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // this queue cannot create more containers
    testProbe.expectNoMessage()
  }

  it should "add an initial container if there is not any" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "disable the namespace throttling with adding an initial container when there is no container" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 1,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = NamespaceThrottled,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(DisableNamespaceThrottling, 0))
  }

  it should "disable the namespace throttling when there are some containers" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = false, // this queue is not initialized yet
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 0,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = NamespaceThrottled,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(DisableNamespaceThrottling, 0))
  }

  // this is an exceptional case
  it should "add an initial container if there is no container in the Running state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(1), // if there is no message, it won't add a container.
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  // this is an exceptional case
  it should "not add a container if there is no message even in the Running state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // if there is no message, it won't add a container.
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  // this can happen when the limit was 0 for some reason previously but it is increased after some time.
  it should "add one container if there is no container in the Paused state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // it should add a container even if there is no message this is because all messages are dropped in the Paused state
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "add one container if there is no container in the Waiting state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0), // it should add a container even if there is no message this is because all messages are dropped in the Waiting state
      currentMsgCount = 0,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Flushing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddInitialContainer, 1))
  }

  it should "add same number of containers with the number of stale messages if there are any" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add exclude the number of in-progress container when adding containers for stale messages when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  it should "add at most the same number with the limit when adding containers for stale messages when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 6,
      existingContainerCount = 1,
      inProgressContainerCount = 1,
      staleActivationNum = 6,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = None,
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 2))
  }

  it should "not add any container for stale messages if the increment is <= 0 when there when there is no available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 6,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 6,
      averageDuration = None,
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectNoMessage()
  }

  it should "add containers for stale messages based on duration when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 6,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 6, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(50), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 50 = 3
    testProbe.expectMsg(DecisionResults(AddContainer, 3))
  }

  it should "add containers for stale messages at most the number of messages when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 2, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 2 / 100 / 1000 = 20, but add only 2 containers for 2 messages
    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add containers for stale messages within the limit when there is available duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 10,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 10, // stale messages exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 10 / 100 / 1000 = 100, but because there is only 10 messages, it is supposed to be 10.
    // Finally it becomes it becomes 3 because there are not enough capacity.
    // limit - total containers in a namespace = 4 - 1 = 3
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 3))
  }

  it should "add containers based on duration if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(4),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectMsg(DecisionResults(AddContainer, 5))
  }

  it should "add containers based on duration within the capacity if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(4),
      currentMsgCount = 2,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 3 because there is not enough capacity
    // limit - containers in a namespace = 4 - 1 = 3
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(dropMsg = false), 3))
  }

  it should "not add container when expected TPS is bigger than available message if there is no stale message" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 5,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 5,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectNoMessage()
  }

  it should "add one container when there is no container and are some messages" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 0,
      inProgressContainerCount = 0,
      staleActivationNum = 0, // no stale message exist
      existingContainerCountInNamespace = 0,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None, // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  it should "add more containers when there are stale messages even in the GracefulShuttingDown state" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1, // some containers are running and in-progress
      inProgressContainerCount = 1,
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 1,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 4 / 100 / 1000 = 40, but because there is only 4 messages, it is supposed to be 4.
    // Finally it becomes it becomes 3 because there is not enough capacity.
    testProbe.expectMsg(DecisionResults(AddContainer, 3))
  }

  it should "add more containers when there are stale messages even in the GracefulShuttingDown state when there is no duration" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1,
      inProgressContainerCount = 2, // some containers are in progress
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 2,
      averageDuration = None, // the average duration does not exist
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 6 / 100 / 1000 = 60, but because there is only 6 messages, it is supposed to be 6.
    // Finally it becomes it becomes 5 because there is already one container.
    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add more containers when there are stale messages and non-stale messages and both message classes need more containers" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 5,
      existingContainerCount = 2,
      inProgressContainerCount = 0,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(1000), // the average duration exists
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    //should add two for the stale messages and one to increase tps of non-stale available messages
    testProbe.expectMsg(DecisionResults(AddContainer, 3))
  }

  it should "add more containers when there are stale messages and non-stale messages have needed tps" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 5,
      existingContainerCount = 2,
      inProgressContainerCount = 0,
      staleActivationNum = 2,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(50), // the average duration gives container throughput of 2
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 1,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    //should add one additional container for stale messages and non-stale messages still meet tps
    testProbe.expectMsg(DecisionResults(AddContainer, 1))
  }

  it should "enable namespace throttling while adding more container when there are stale messages even in the GracefulShuttingDown" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 1,
      inProgressContainerCount = 2, // some containers are in progress
      staleActivationNum = 4, // stale message exist
      existingContainerCountInNamespace = 1,
      inProgressContainerCountInNamespace = 2,
      averageDuration = None, // the average duration does not exist
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 1,
      stateName = Removing,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages / threshold / duration
    // 4 / 100 / 1000 = 40, but because there is only 4 messages, it is supposed to be 4.
    // it should subtract the in-progress number so it is supposed to be 2
    // but there is not enough capacity, it becomes 1
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(false), 1))
  }

  it should "correctly calculate demand is met when action concurrency >1 w/ average duration and no stale activations" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 4,
      existingContainerCount = 2,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(100.0),
      namespaceLimit = 4,
      actionLimit = 4,
      maxActionConcurrency = 2,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages is 4 with duration equaling the stale threshold and action concurrency of 2 so needed containers
    // should be exactly 2
    testProbe.expectNoMessage()
  }

  it should "add containers when action concurrency >1 w/ average duration and demand is not met" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 20,
      existingContainerCount = 2,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(50.0),
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 3,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages is 20 and throughput should be 100.0 / 50.0 * 3 = 6
    // existing container is 2 so can handle 12 messages, therefore need 2 more containers
    testProbe.expectMsg(DecisionResults(AddContainer, 2))
  }

  it should "add containers when action concurrency >1 w/ average duration and demand is not met and has stale activations" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 30,
      existingContainerCount = 2,
      inProgressContainerCount = 0,
      staleActivationNum = 10,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(50.0),
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 3,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // available messages is 30 and throughput should be 100.0 / 50.0 * 3 = 6
    // existing container is 2 so can handle 12 messages, therefore need 2 more containers for non-stale
    // stale has 10 activations so need another additional 2
    testProbe.expectMsg(DecisionResults(AddContainer, 4))
  }

  it should "add containers when action concurrency >1 when no average duration and there are stale activations" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 10,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 10,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = None,
      namespaceLimit = 10,
      actionLimit = 10,
      maxActionConcurrency = 3,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // stale messages are 10. want stale to be handled by first pass of requests from containers so
    // 10 / 3 = 4.0
    testProbe.expectMsg(DecisionResults(AddContainer, 4))
  }

  it should "add only up to the action container limit if less than the namespace limit" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 100,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 2,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(100.0),
      namespaceLimit = 10,
      actionLimit = 5,
      maxActionConcurrency = 3,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // one container already exists with an action limit of 5. Number of messages will exceed limit of containers
    // so use smaller of the two limits
    testProbe.expectMsg(DecisionResults(AddContainer, 4))
  }

  it should "add only up to the namespace limit total if existing containers in namespace prevents reaching action limit" in {
    val decisionMaker = system.actorOf(SchedulingDecisionMaker.props(testNamespace, action, schedulingConfig))
    val testProbe = TestProbe()

    // container
    val msg = QueueSnapshot(
      initialized = true,
      incomingMsgCount = new AtomicInteger(0),
      currentMsgCount = 100,
      existingContainerCount = 1,
      inProgressContainerCount = 0,
      staleActivationNum = 0,
      existingContainerCountInNamespace = 7,
      inProgressContainerCountInNamespace = 0,
      averageDuration = Some(100.0),
      namespaceLimit = 10,
      actionLimit = 5,
      maxActionConcurrency = 3,
      stateName = Running,
      recipient = testProbe.ref)

    decisionMaker ! msg

    // one container already exists with an action limit of 5. There are currently 7 containers in namespace
    // so can only add 3 more even if that only gives this action 4 containers when it has an action limit of 5
    testProbe.expectMsg(DecisionResults(EnableNamespaceThrottling(false), 3))
  }
}
