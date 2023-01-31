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

import akka.actor.ActorRef
import akka.actor.FSM.{CurrentState, StateTimeout, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActor, TestFSMRef, TestProbe}
import com.sksamuel.elastic4s.http.{search => _}
import org.apache.openwhisk.common.GracefulShutdown
import org.apache.openwhisk.common.time.{Clock, SystemClock}
import org.apache.openwhisk.core.connector.ContainerCreationError.{NonExecutableActionError, WhiskError}
import org.apache.openwhisk.core.connector.ContainerCreationMessage
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.scheduler.grpc.ActivationResponse
import org.apache.openwhisk.core.scheduler.message.{
  ContainerCreation,
  ContainerDeletion,
  FailedCreationJob,
  SuccessfulCreationJob
}
import org.apache.openwhisk.core.scheduler.queue.MemoryQueue.checkToDropStaleActivation
import org.apache.openwhisk.core.scheduler.queue._
import org.apache.openwhisk.core.service._
import org.apache.openwhisk.http.Messages.{namespaceLimitUnderZero, tooManyConcurrentRequests}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import spray.json.{JsObject, JsString}

import java.time.Instant
import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration, MILLISECONDS}
import scala.language.postfixOps

class FakeClock extends Clock {
  var instant: Instant = Instant.now()
  def now() = instant
  def set(now: Instant): Unit = {
    instant = now
  }
  def plusSeconds(secondsToAdd: Long): Unit = {
    instant = instant.plusSeconds(secondsToAdd)
  }
}

@RunWith(classOf[JUnitRunner])
class MemoryQueueFlowTests
    extends MemoryQueueTestsFixture
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  override def beforeEach(): Unit = {
    super.beforeEach()
    ackedMessageCount = 0
    storedMessageCount = 0
  }

  override def afterEach(): Unit = {
    super.afterEach()
    logLines.foreach(println)
    stream.reset()
  }

  behavior of "MemoryQueueFlow"

  it should "normally be created and handle an activation and became idle an finally removed" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val decisionMaker = TestProbe()
    val probe = TestProbe()
    // no need to create more than 1 container for this test
    decisionMaker.setAutoPilot((sender: ActorRef, msg) => {
      msg match {
        case msg: QueueSnapshot if !msg.initialized =>
          logging.info(this, "add an initial container")
          sender ! DecisionResults(AddInitialContainer, 1)
          TestActor.KeepRunning

        case _ =>
          TestActor.KeepRunning
      }
    })
    val container = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          decisionMaker.ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! message

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(message)))

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // deleting the container from containers set
    container.send(fsm, getActivation(false))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the removed state
    fsm ! StateTimeout

    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "became Idle and Running again if a message arrives" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))

    val messages = getActivationMessages(2)
    val container = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! messages(0)

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(0))))

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // deleting the container from containers set
    container.send(fsm, getActivation(false))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! messages(1)

    probe.expectMsg(Transition(fsm, Idle, Running))

    fsm ! StateTimeout
    // since there is one message, it should not be Idle
    probe.expectNoMessage()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    fsm.underlyingActor.queue.length shouldBe 1

    container.send(fsm, getActivation(true))
    container.expectMsg(ActivationResponse(Right(messages(1))))

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // deleting the container from containers set
    container.send(fsm, getActivation(false))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the removed state
    fsm ! StateTimeout

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "go to the NamespaceThrottled state dropping messages when it can't create an initial container" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val decisionMaker = TestProbe()
    val probe = TestProbe()

    // this is the case where there is no capacity in a namespace and no container can be created.
    decisionMaker.setAutoPilot((sender: ActorRef, msg) => {
      msg match {
        case QueueSnapshot(_, _, _, _, _, _, _, _, _, _, Running, _) =>
          sender ! DecisionResults(EnableNamespaceThrottling(true), 0)
          TestActor.KeepRunning

        case _ =>
          // do nothing
          TestActor.KeepRunning
      }
    })

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          decisionMaker.ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! message

    dataMgmtService.expectMsg(RegisterData(namespaceThrottlingKey, true.toString, failoverEnabled = false))
    probe.expectMsg(Transition(fsm, Running, NamespaceThrottled))

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))

      // all activations are dropped with an error
      fsm.underlyingActor.queue.size shouldBe 0
    }, 5.seconds)

    fsm ! GracefulShutdown

    probe.expectMsg(Transition(fsm, NamespaceThrottled, Removing))

    // the queue is timed out in the Removing state
    fsm ! StateTimeout

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectMsg(Transition(fsm, Removing, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "go to the NamespaceThrottled state without dropping messages and get back to the Running container" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))

    val getUserLimit = (_: String) => Future.successful(1)
    val container = TestProbe()

    val messages = getActivationMessages(2)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    // send two messages to simulate the namespace-throttled case as it can't create more than 1 container
    fsm ! messages(0)
    fsm ! messages(1)

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // one container is created
    fsm.underlyingActor.namespaceContainerCount.inProgressContainerNumByNamespace = 1

    // only one message is handled
    container.send(fsm, getActivation(true, "testContainerId1"))
    container.expectMsg(ActivationResponse(Right(messages(0))))

    // namespace throttling is enabled
    dataMgmtService.expectMsg(RegisterData(namespaceThrottlingKey, true.toString, failoverEnabled = false))

    // since one message is not being handled but cannot create more container, it is namespace-throttled
    probe.expectMsg(Transition(fsm, Running, NamespaceThrottled))

    // deleting the container to secure the capacity
    container.send(fsm, getActivation(false, "testContainerId1"))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))
    fsm.underlyingActor.namespaceContainerCount.inProgressContainerNumByNamespace = 0

    // namespace throttling is disabled
    dataMgmtService.expectMsg(RegisterData(namespaceThrottlingKey, false.toString, failoverEnabled = false))

    probe.expectMsg(Transition(fsm, NamespaceThrottled, Running))

    // try container creation
    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    // a container is created
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // one message is handled
    container.send(fsm, getActivation(true, "testContainerId2"))
    container.expectMsg(ActivationResponse(Right(messages(1))))

    // deleting the container from containers set
    container.send(fsm, getActivation(false, "testContainerId2"))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    fsm ! StateTimeout

    // all subsequent procedures are same with the Running case
    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout

    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "go to the ActionThrottled state when there are too many stale activations including transition to NamespaceThrottling" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))

    // max retention size is 10 and throttling fraction is 0.8
    // queue will be action throttled at 10 messages and disabled action throttling at 8 messages
    val queueConfig = QueueConfig(5 seconds, 10 seconds, 10 seconds, 5 seconds, 10, 5000, 10000, 0.8, 10, false)

    // limit is 1
    val getUserLimit = (_: String) => Future.successful(1)
    val container = TestProbe()

    // generate 12 activations
    val messages = getActivationMessages(12)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    // send one messages to the queue to make it Running
    fsm ! message

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // one container is created
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace += 1

    // only one message is handled
    container.send(fsm, getActivation(true, "testContainerId1"))
    container.expectMsg(ActivationResponse(Right(message)))

    // send 10 messages to fsm to enable action throttling
    (0 to 9).foreach(fsm ! messages(_))

    dataMgmtService.expectMsg(RegisterData(actionThrottlingKey, true.toString, failoverEnabled = false))
    probe.expectMsg(Transition(fsm, Running, ActionThrottled))

    // if messages arrive in the ActionThrottled state, they are immediately dropped.
    fsm ! messages(10)

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
    }, 5.seconds)

    fsm ! messages(11)

    awaitAssert({
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
    }, 5.seconds)

    // handle 3 messages to disable the action throttling
    (0 to 2).foreach { index =>
      // only one message is handled
      container.send(fsm, getActivation(true, "testContainerId1"))
      container.expectMsg(ActivationResponse(Right(messages(index))))
    }

    // action throttling is disabled
    dataMgmtService.expectMsg(RegisterData(actionThrottlingKey, false.toString, failoverEnabled = false))
    probe.expectMsg(Transition(fsm, ActionThrottled, Running))

    // handle 8 messages to consume all messages
    (3 to 9).foreach { index =>
      // only one message is handled
      container.send(fsm, getActivation(true, "testContainerId1"))
      container.expectMsg(ActivationResponse(Right(messages(index))))
    }

    // sooner or later the namespace throttling is also enabled as it can't create more containers
    dataMgmtService.expectMsg(RegisterData(namespaceThrottlingKey, true.toString, failoverEnabled = false))

    probe.expectMsg(Transition(fsm, Running, NamespaceThrottled))

    // deleting the container to secure the capacity
    container.send(fsm, getActivation(false, "testContainerId1"))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace -= 1

    // namespace throttling is disabled
    dataMgmtService.expectMsg(10.seconds, RegisterData(namespaceThrottlingKey, false.toString, failoverEnabled = false))
    probe.expectMsg(Transition(fsm, NamespaceThrottled, Running))

    // normal termination process
    fsm ! StateTimeout
    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout
    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)

    fsm ! QueueRemovedCompleted
    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be Flushing when the limit is 0 and restarted back to Running state when the limit is increased" in {
    implicit val clock = new FakeClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))

    // generate 2 activations
    val messages = getActivationMessages(3)

    var limit = 0
    val getUserLimit = (_: String) => Future.successful(limit)

    val container = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    fsm ! messages(0)

    expectInitialData(watcher, dataMgmtService)
    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    clock.plusSeconds(FiniteDuration(retentionTimeout, MILLISECONDS).toSeconds)

    probe.expectMsg(Transition(fsm, Running, Flushing))
    // activation received in Flushing state won't be flushed immediately if Flushing state is caused by a whisk error
    fsm ! messages(1)
    fsm ! StateTimeout

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 1
    }, 5.seconds)
    // limit is increased by an operator
    limit = 10

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    // Queue is now working
    probe.expectMsg(Transition(fsm, Flushing, Running))

    // one container is created
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace += 1

    // only one message is handled
    container.send(fsm, getActivation(true, "testContainerId1"))
    container.expectMsg(ActivationResponse(Right(messages(1))))

    // deleting the container from containers set
    container.send(fsm, getActivation(false, "testContainerId1"))
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace -= 1

    // normal termination process
    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be Flushing when the limit is 0 and be terminated without recovering" in {
    implicit val clock = new FakeClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))

    // generate 2 activations
    val messages = getActivationMessages(2)

    val getUserLimit = (_: String) => Future.successful(0)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    fsm ! messages(0)
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    probe.expectMsg(Transition(fsm, Running, Flushing))
    fsm ! messages(1)

    // activation received in Flushing state won't be flushed immediately if Flushing state is caused by a whisk error
    clock.plusSeconds((queueConfig.maxRetentionMs) / 1000)
    fsm ! DropOld

    awaitAssert({
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    // In this case data clean up happens first.
    fsm ! StateTimeout
    expectDataCleanUp(watcher, dataMgmtService)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be the Flushing state when a whisk error happens" in {
    implicit val clock = new FakeClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val decisionMaker = TestProbe()

    // no need to create more than 1 container for this test
    decisionMaker.setAutoPilot((sender: ActorRef, msg) => {
      msg match {
        case msg: QueueSnapshot if !msg.initialized =>
          logging.info(this, "add an initial container")
          sender ! DecisionResults(AddInitialContainer, 1)
          TestActor.KeepRunning

        case _ =>
          TestActor.KeepRunning
      }
    })

    // generate 2 activations
    val messages = getActivationMessages(2)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          decisionMaker.ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! messages(0)

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }
    // Failed to create a container
    fsm ! FailedCreationJob(creationId, testInvocationNamespace, fqn, revision, WhiskError, "whisk error")

    probe.expectMsg(Transition(fsm, Running, Flushing))

    fsm ! messages(1)

    clock.plusSeconds(FiniteDuration(retentionTimeout, MILLISECONDS).toSeconds)
    fsm ! StateTimeout

    awaitAssert({
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      fsm.underlyingActor.queue.length shouldBe 0
    }, FiniteDuration(retentionTimeout, MILLISECONDS))

    clock.plusSeconds(flushGrace.toSeconds * 2)
    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Flushing, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be the Flushing state when a whisk error happens and be recovered when a container is created" in {
    implicit val clock = new FakeClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))
    val probe = TestProbe()
    val container = TestProbe()
    // generate 2 activations
    val messages = getActivationMessages(2)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! messages(0)

    // Failed to create a container
    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        fsm ! FailedCreationJob(id, testInvocationNamespace, fqn, revision, WhiskError, "whisk error")
    }

    clock.plusSeconds(FiniteDuration(retentionTimeout, MILLISECONDS).toSeconds)
    probe.expectMsg(Transition(fsm, Running, Flushing))
    fsm ! messages(1)

    // activation received in Flushing state won't be flushed immediately if Flushing state is caused by a whisk error
    fsm ! StateTimeout

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      fsm.underlyingActor.queue.length shouldBe 1
    }, FiniteDuration(retentionTimeout, MILLISECONDS))

    // Succeed to create a container
    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        fsm ! SuccessfulCreationJob(id, testInvocationNamespace, fqn, revision)
    }

    probe.expectMsg(Transition(fsm, Flushing, Running))

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(1))))

    // deleting the container from containers set
    container.send(fsm, getActivation(false))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    fsm.underlyingActor.creationIds = mutable.Set.empty[String]
    fsm ! StateTimeout
    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout

    probe.expectMsg(Transition(fsm, Idle, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be the Flushing state when a developer error happens" in {
    implicit val clock = new FakeClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))
    val probe = TestProbe()

    // generate 2 activations
    val messages = getActivationMessages(2)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! messages(0)

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }
    // Failed to create a container
    fsm ! FailedCreationJob(
      creationId,
      testInvocationNamespace,
      fqn,
      revision,
      NonExecutableActionError,
      "no executable action found")

    // drop all activations before transition to the Flushing state
    within(10.seconds) {
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(
        JsObject("error" -> JsString("no executable action found")))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(
        JsObject("error" -> JsString("no executable action found")))
      fsm.underlyingActor.queue.length shouldBe 0
    }

    probe.expectMsg(Transition(fsm, Running, Flushing))

    fsm ! messages(1)

    // new messages immediately dropped in the Flushing state
    within(10.seconds) {
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(
        JsObject("error" -> JsString("no executable action found")))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(
        JsObject("error" -> JsString("no executable action found")))
      fsm.underlyingActor.queue.length shouldBe 0
    }

    // simulate timeout 2 times
    clock.plusSeconds(flushGrace.toSeconds * 2)
    fsm ! StateTimeout
    fsm ! StateTimeout

    expectDataCleanUp(watcher, dataMgmtService)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be gracefully terminated when it receives a GracefulShutDown message" in {
    implicit val clock = SystemClock
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker =
      system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn, schedulingConfig))
    val probe = TestProbe()

    val messages = getActivationMessages(4)
    val container = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          schedulingConfig,
          testInvocationNamespace,
          revision,
          endpoints,
          actionMetadata,
          dataMgmtService.ref,
          watcher.ref,
          containerManager.ref,
          testSchedulingDecisionMaker,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    probe watch fsm
    registerCallback(probe, fsm)

    fsm ! Start
    expectInitialData(watcher, dataMgmtService)
    fsm ! testInitialDataStorageResult

    probe.expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! messages(0)

    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(0))))

    // deleting the ID from creationIds set
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    fsm ! GracefulShutdown

    // When it receives a graceful shutdown, it is supposed to delete etcd data first to create another queue in other schedulers.
    // But still this queue should handle existing messages so it does not stop scheduling actors.
    dataMgmtService.expectMsgAllOf(
      UnregisterData(leaderKey),
      UnregisterData(namespaceThrottlingKey),
      UnregisterData(actionThrottlingKey))

    probe.expectMsg(Transition(fsm, Running, Removing))

    // a newly arrived message should be properly handled
    fsm ! messages(1)
    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(1))))

    fsm ! messages(2)

    // if there is a message, it should not terminate
    fsm ! StateTimeout

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(2))))

    fsm ! StateTimeout

    // it doesn't need to check if all containers are timeout as same version of a new queue will be created in another scheduler.

    watcher.expectMsgAllOf(
      UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

    probe.expectMsg(Transition(fsm, Removing, Removed))

    // the queue is timed out again in the Removed state
    fsm ! StateTimeout
    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  it should "be deprecated when a new queue supersedes it." in {
    // GracefulShuttingDown is not applicable
    val allStates = List(Running, Idle, Flushing, ActionThrottled, NamespaceThrottled, Removing, Removed)

    allStates.foreach { state =>
      implicit val clock = SystemClock
      val mockEtcdClient = mock[EtcdClient]
      val parent = TestProbe()
      val watcher = TestProbe()
      val dataMgmtService = TestProbe()
      val containerManager = TestProbe()
      val decisionMaker = TestProbe()
      decisionMaker.ignoreMsg { case _: QueueSnapshot => true }
      val probe = TestProbe()

      println(s"start with $state")

      expectDurationChecking(mockEsClient, testInvocationNamespace)

      val fsm =
        TestFSMRef(
          new MemoryQueue(
            mockEtcdClient,
            durationChecker,
            fqn,
            mockMessaging(),
            schedulingConfig,
            testInvocationNamespace,
            revision,
            endpoints,
            actionMetadata,
            dataMgmtService.ref,
            watcher.ref,
            containerManager.ref,
            decisionMaker.ref,
            schedulerId,
            ack,
            store,
            getUserLimit,
            checkToDropStaleActivation,
            queueConfig),
          parent.ref)

      probe watch fsm
      registerCallback(probe, fsm)

      fsm ! Start
      expectInitialData(watcher, dataMgmtService)
      fsm ! testInitialDataStorageResult

      probe.expectMsg(Transition(fsm, Uninitialized, Running))

      fsm ! message

      fsm.setState(state)

      probe.expectMsg(Transition(fsm, Running, state))

      // queue endpoint is removed for some reason
      fsm ! WatchEndpointRemoved("watchKey", `leaderKey`, "watchValue", false)

      // try to restore it
      dataMgmtService.expectMsg(RegisterInitialData(leaderKey, "watchValue", failoverEnabled = false, Some(fsm)))

      // another queue is already running
      fsm ! InitialDataStorageResults(`leaderKey`, Left(AlreadyExist()))
      parent.expectMsg(queueRemovedMsg)
      parent.expectMsg(message)

      // clean up actors only because etcd data is being used by a new queue
      watcher.expectMsgAllOf(
        UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
        UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
        UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

      // move to the Deprecated state
      probe.expectMsg(Transition(fsm, state, Removed))

      // the queue is timed out again in the Removed state
      fsm ! StateTimeout
      parent.expectMsg(queueRemovedMsg)
      fsm ! QueueRemovedCompleted

      probe.expectTerminated(fsm, 10.seconds)
    }
  }

  it should "be deprecated and stops even if the queue manager could not respond." in {
    // GracefulShuttingDown is not applicable
    val allStates = List(Running, Idle, Flushing, ActionThrottled, NamespaceThrottled, Removing, Removed)

    allStates.foreach { state =>
      implicit val clock = SystemClock
      val mockEtcdClient = mock[EtcdClient]
      val parent = TestProbe()
      val watcher = TestProbe()
      val dataMgmtService = TestProbe()
      val containerManager = TestProbe()
      val decisionMaker = TestProbe()
      decisionMaker.ignoreMsg { case _: QueueSnapshot => true }
      val probe = TestProbe()

      println(s"start with $state")

      expectDurationChecking(mockEsClient, testInvocationNamespace)

      val fsm =
        TestFSMRef(
          new MemoryQueue(
            mockEtcdClient,
            durationChecker,
            fqn,
            mockMessaging(),
            schedulingConfig,
            testInvocationNamespace,
            revision,
            endpoints,
            actionMetadata,
            dataMgmtService.ref,
            watcher.ref,
            containerManager.ref,
            decisionMaker.ref,
            schedulerId,
            ack,
            store,
            getUserLimit,
            checkToDropStaleActivation,
            queueConfig),
          parent.ref)

      probe watch fsm
      registerCallback(probe, fsm)

      fsm ! Start
      expectInitialData(watcher, dataMgmtService)
      fsm ! testInitialDataStorageResult

      probe.expectMsg(Transition(fsm, Uninitialized, Running))

      fsm ! message

      fsm.setState(state)

      probe.expectMsg(Transition(fsm, Running, state))

      // queue endpoint is removed for some reason
      fsm ! WatchEndpointRemoved("watchKey", `leaderKey`, "watchValue", false)

      // try to restore it
      dataMgmtService.expectMsg(RegisterInitialData(leaderKey, "watchValue", failoverEnabled = false, Some(fsm)))

      // another queue is already running
      fsm ! InitialDataStorageResults(`leaderKey`, Left(AlreadyExist()))

      parent.expectMsg(queueRemovedMsg)
      parent.expectMsg(message)

      // clean up actors only because etcd data is being used by a new queue
      watcher.expectMsgAllOf(
        UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
        UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
        UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

      // move to the Deprecated state
      probe.expectMsg(Transition(fsm, state, Removed))

      // queue manager could not respond to the memory queue.
      fsm ! StateTimeout

      // the queue is supposed to send queueRemovedMsg once again and stops itself.
      parent.expectMsg(queueRemovedMsg)
      fsm ! QueueRemovedCompleted
      probe.expectTerminated(fsm, 10.seconds)
    }
  }

  // this is to guarantee StopScheduling is handled in all states
  it should "handle StopScheduling in any states." in {
    val testContainerId = "fakeContainerId"
    val allStates =
      List(Running, Idle, ActionThrottled, NamespaceThrottled, Flushing, Removing, Removed)

    allStates.foreach { state =>
      implicit val clock = SystemClock
      val mockEtcdClient = mock[EtcdClient]
      val parent = TestProbe()
      val watcher = TestProbe()
      val dataMgmtService = TestProbe()
      val containerManager = TestProbe()
      val decisionMaker = TestProbe()
      decisionMaker.ignoreMsg { case _: QueueSnapshot => true }
      val probe = TestProbe()
      val container = TestProbe()
      val schedulingActors = TestProbe()

      println(s"start with $state")

      expectDurationChecking(mockEsClient, testInvocationNamespace)

      val fsm =
        TestFSMRef(
          new MemoryQueue(
            mockEtcdClient,
            durationChecker,
            fqn,
            mockMessaging(),
            schedulingConfig,
            testInvocationNamespace,
            revision,
            endpoints,
            actionMetadata,
            dataMgmtService.ref,
            watcher.ref,
            containerManager.ref,
            decisionMaker.ref,
            schedulerId,
            ack,
            store,
            getUserLimit,
            checkToDropStaleActivation,
            queueConfig),
          parent.ref)

      probe watch fsm
      registerCallback(probe, fsm)

      fsm ! Start
      expectInitialData(watcher, dataMgmtService)
      fsm ! testInitialDataStorageResult

      probe.expectMsg(Transition(fsm, Uninitialized, Running))

      state match {
        case NamespaceThrottled | ActionThrottled =>
          fsm ! message
          fsm.setState(state, ThrottledData(schedulingActors.ref, schedulingActors.ref))

        case Flushing =>
          fsm ! message
          fsm.setState(state, FlushingData(schedulingActors.ref, schedulingActors.ref, WhiskError, "whisk error"))

        case Removing =>
          fsm.underlyingActor.containers = mutable.Set(testContainerId)
          fsm ! message
          fsm.setState(state, RemovingData(schedulingActors.ref, schedulingActors.ref, outdated = true))

        case Idle =>
          fsm ! StateTimeout

        case Removed =>
          fsm ! StateTimeout
          probe.expectMsg(Transition(fsm, Running, Idle))

          fsm ! StateTimeout
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectMsg(Transition(fsm, Idle, Removed))

        case _ =>
          fsm ! message
          fsm.setState(state)
      }

      if (state != Removed) {
        probe.expectMsg(Transition(fsm, Running, state))
      }

      fsm ! StopSchedulingAsOutdated

      state match {
        case Removing =>
          // still exist old container for old queue, fetch the queue by old container
          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))
          // has no old containers for old queue, so send the message to queueManager
          fsm.underlyingActor.containers = mutable.Set.empty[String]
          fsm.underlyingActor.queue =
            Queue.apply(TimeSeriesActivationEntry(Instant.ofEpochMilli(Instant.now.toEpochMilli + 1000), message))
          fsm ! StopSchedulingAsOutdated
          parent.expectMsg(message)

          // queue should be terminated after gracefulShutdownTimeout
          fsm ! StateTimeout

          // clean up actors only because etcd data is being used by a new queue
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          containerManager.expectMsg(ContainerDeletion(testInvocationNamespace, fqn, revision, actionMetadata))

          probe.expectMsg(Transition(fsm, Removing, Removed))
          probe.expectTerminated(fsm, 10.seconds)

        // queue will be removed soon, do nothing
        case Removed =>
          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)

        case Idle =>
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          // queue is stale and will be removed
          parent.expectMsg(staleQueueRemovedMsg)
          containerManager.expectMsg(ContainerDeletion(testInvocationNamespace, fqn, revision, actionMetadata))

          probe.expectMsg(Transition(fsm, state, Removed))

          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)

        case Flushing =>
          // queue is stale and will be removed
          parent.expectMsg(staleQueueRemovedMsg)
          probe.expectMsg(Transition(fsm, state, Removed))

          fsm ! QueueRemovedCompleted
          fsm ! StateTimeout

          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectTerminated(fsm, 10.seconds)

        case _ =>
          parent.expectMsg(staleQueueRemovedMsg)
          parent.expectMsg(message)
          // queue is stale and will be removed
          probe.expectMsg(Transition(fsm, state, Removing))

          fsm ! QueueRemovedCompleted

          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          containerManager.expectMsg(ContainerDeletion(testInvocationNamespace, fqn, revision, actionMetadata))

          // move to the Deprecated state
          probe.expectMsg(Transition(fsm, Removing, Removed))
          probe.expectTerminated(fsm, 10.seconds)
      }
    }
  }

  // this is to guarantee GracefulShutdown is handled in all states
  it should "handle GracefulShutdown in any states." in {
    val allStates =
      List(Running, Idle, ActionThrottled, NamespaceThrottled, Flushing, Removing, Removed)

    allStates.foreach { state =>
      implicit val clock = SystemClock
      val mockEtcdClient = mock[EtcdClient]
      val parent = TestProbe()
      val watcher = TestProbe()
      val dataMgmtService = TestProbe()
      val containerManager = TestProbe()
      val decisionMaker = TestProbe()
      decisionMaker.ignoreMsg { case _: QueueSnapshot => true }
      val probe = TestProbe()
      val container = TestProbe()
      val schedulingActors = TestProbe()

      println(s"start with $state")

      expectDurationChecking(mockEsClient, testInvocationNamespace)

      val fsm =
        TestFSMRef(
          new MemoryQueue(
            mockEtcdClient,
            durationChecker,
            fqn,
            mockMessaging(),
            schedulingConfig,
            testInvocationNamespace,
            revision,
            endpoints,
            actionMetadata,
            dataMgmtService.ref,
            watcher.ref,
            containerManager.ref,
            decisionMaker.ref,
            schedulerId,
            ack,
            store,
            getUserLimit,
            checkToDropStaleActivation,
            queueConfig),
          parent.ref)

      probe watch fsm
      registerCallback(probe, fsm)

      fsm ! Start
      expectInitialData(watcher, dataMgmtService)

      probe.expectMsg(Transition(fsm, Uninitialized, Running))

      fsm ! testInitialDataStorageResult

      state match {
        case NamespaceThrottled | ActionThrottled =>
          fsm ! message
          fsm.setState(state, ThrottledData(schedulingActors.ref, schedulingActors.ref))

        case Flushing =>
          fsm ! message
          fsm.setState(state, FlushingData(schedulingActors.ref, schedulingActors.ref, WhiskError, "whisk error"))

        case Removing =>
          fsm ! message
          fsm.setState(state, RemovingData(schedulingActors.ref, schedulingActors.ref, outdated = true))

        case Idle =>
          fsm ! StateTimeout

        case Removed =>
          fsm ! StateTimeout
          probe.expectMsg(Transition(fsm, Running, Idle))

          fsm ! StateTimeout
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectMsg(Transition(fsm, Idle, Removed))

        case _ =>
          fsm ! message
          fsm.setState(state)
      }

      if (state != Removed) {
        probe.expectMsg(Transition(fsm, Running, state))
      }

      fsm ! GracefulShutdown

      state match {
        // queue will be gracefully shutdown.
        case Removing =>
          // queue should not be terminated as there is an activation
          fsm ! StateTimeout

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          // queue should not be terminated as there is an activation
          fsm ! StateTimeout

          // clean up actors only because etcd data is being used by a new queue
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          // move to the Deprecated state
          probe.expectMsg(Transition(fsm, Removing, Removed))
          probe.expectTerminated(fsm, 10.seconds)

        // queue will be removed soon, do nothing
        case Removed =>
          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)

        case Idle | Flushing =>
          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectMsg(Transition(fsm, state, Removed))

          // the queue is timed out againd in the Removed state
          fsm ! StateTimeout

          // queue is stale and will be removed
          parent.expectMsg(queueRemovedMsg)
          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)

        case _ =>
          // queue is stale and will be removed

          probe.expectMsg(Transition(fsm, state, Removing))

          // queue should not be terminated as there is an activation
          fsm ! StateTimeout

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          fsm ! StateTimeout

          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectMsg(Transition(fsm, Removing, Removed))

          // the queue is timed out againd in the Removed state
          fsm ! StateTimeout
          parent.expectMsg(queueRemovedMsg)
          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)
      }
    }
  }

  def registerCallback(probe: TestProbe, c: ActorRef) = {
    c ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(c, Uninitialized))
  }

}
