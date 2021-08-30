package org.apache.openwhisk.core.scheduler.queue.test

import akka.actor.ActorRef
import akka.actor.FSM.{CurrentState, StateTimeout, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActor, TestFSMRef, TestProbe}
import com.sksamuel.elastic4s.http.{search => _}
import org.apache.openwhisk.common.GracefulShutdown
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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

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
  }

  behavior of "MemoryQueueFlow"

  // this is 1. normal case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "normally be created and handle an activation and became idle an finally removed" in {
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
          config,
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

    Thread.sleep(idleGrace.toMillis)

    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)

    expectDataCleanUp(watcher, dataMgmtService)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 1-2. normal case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "became Idle and Running again if a message arrives" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

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
          config,
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

    Thread.sleep(idleGrace.toMillis)

    probe.expectMsg(Transition(fsm, Running, Idle))

    fsm ! messages(1)

    probe.expectMsg(Transition(fsm, Idle, Running))

    Thread.sleep(idleGrace.toMillis)
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

    Thread.sleep(idleGrace.toMillis)

    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 2. NamespaceThrottled case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "go to the Flushing state dropping messages when it can't create an initial container" in {
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
          config,
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
    probe.expectMsg(Transition(fsm, Running, Flushing))

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(tooManyConcurrentRequests)))

      // all activations are dropped with an error
      fsm.underlyingActor.queue.size shouldBe 0
    }, 5.seconds)

    Thread.sleep(flushGrace.toMillis)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 3. NamespaceThrottled case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "go to the NamespaceThrottled state without dropping messages and get back to the Running container" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

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
          config,
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

    Thread.sleep(idleGrace.toMillis)

    // all subsequent procedures are same with the Running case
    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 4. ActionThrottled case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "go to the ActionThrottled state when there are too many stale activations including transition to NamespaceThrottling" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

    // max retention size is 10 and throttling fraction is 0.8
    // queue will be action throttled at 10 messages and disabled action throttling at 8 messages
    val queueConfig = QueueConfig(5 seconds, 10 seconds, 10 seconds, 5 seconds, 10, 5000, 0.8, 10)

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
          config,
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
    Thread.sleep(idleGrace.toMillis)
    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)
    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted
    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 5. Paused case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be Flushing when the limit is 0 and restarted back to Running state when the limit is increased" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

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
          config,
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

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    probe.expectMsg(Transition(fsm, Running, Flushing))

    awaitAssert({
      // in the paused state, all incoming messages should be dropped immediately
      fsm ! messages(1)
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 0
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

    fsm ! messages(2)

    // one container is created
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace += 1

    // only one message is handled
    container.send(fsm, getActivation(true, "testContainerId1"))
    container.expectMsg(ActivationResponse(Right(messages(2))))

    // deleting the container from containers set
    container.send(fsm, getActivation(false, "testContainerId1"))
    fsm.underlyingActor.namespaceContainerCount.existingContainerNumByNamespace -= 1

    // normal termination process
    Thread.sleep(idleGrace.toMillis)

    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 5-2. Paused case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be Flushing when the limit is 0 and be terminated without recovering" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val probe = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

    // generate 2 activations
    val messages = getActivationMessages(3)

    val getUserLimit = (_: String) => Future.successful(0)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          config,
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
    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    probe.expectMsg(Transition(fsm, Running, Flushing))

    // in the paused state, all incoming messages should be dropped immediately
    fsm ! messages(1)

    awaitAssert({
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString(namespaceLimitUnderZero)))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    // normal termination process
    Thread.sleep(flushGrace.toMillis * 2)

    // In this case data clean up happens first.
    expectDataCleanUp(watcher, dataMgmtService)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    parent.expectMsg(queueRemovedMsg)
    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 6. Waiting case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be the Flushing state when a whisk error happens" in {
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
          config,
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

    awaitAssert({
      ackedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      storedMessageCount shouldBe 2
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    Thread.sleep(flushGrace.toMillis * 2)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 6-2. Waiting case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be the Flushing state when a whisk error happens and be recovered when a container is created" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))
    val probe = TestProbe()
    val container = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm =
      TestFSMRef(
        new MemoryQueue(
          mockEtcdClient,
          durationChecker,
          fqn,
          mockMessaging(),
          config,
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

    fsm ! message
    // any id is fine because it would be overridden
    var creationId = CreationId.generate()

    containerManager.expectMsgPF() {
      case ContainerCreation(List(ContainerCreationMessage(_, _, _, _, _, _, _, _, _, id)), _, _) =>
        creationId = id
    }
    // Failed to create a container
    fsm ! FailedCreationJob(creationId, testInvocationNamespace, fqn, revision, WhiskError, "whisk error")

    awaitAssert({
      ackedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      storedMessageCount shouldBe 1
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("whisk error")))
      fsm.underlyingActor.queue.length shouldBe 0
    }, 5.seconds)

    probe.expectMsg(Transition(fsm, Running, Flushing))

    // Failed to create a container
    fsm ! SuccessfulCreationJob(creationId, testInvocationNamespace, fqn, revision)

    probe.expectMsg(Transition(fsm, Flushing, Running))

    fsm ! message

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(message)))

    // deleting the container from containers set
    container.send(fsm, getActivation(false))
    container.expectMsg(ActivationResponse(Left(NoActivationMessage())))

    Thread.sleep(idleGrace.toMillis)

    probe.expectMsg(Transition(fsm, Running, Idle))

    Thread.sleep(stopGrace.toMillis)

    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Idle, Removed))

    fsm ! QueueRemovedCompleted

    expectDataCleanUp(watcher, dataMgmtService)

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 7. Flushing case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be the Flushing state when a developer error happens" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))
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
          config,
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

    Thread.sleep(flushGrace.toMillis * 2)

    expectDataCleanUp(watcher, dataMgmtService)
    parent.expectMsg(queueRemovedMsg)
    probe.expectMsg(Transition(fsm, Flushing, Removed))

    fsm ! QueueRemovedCompleted

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 8. GracefulShuttingDown case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be gracefully terminated when it receives a GracefulShutDown message" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val watcher = TestProbe()
    val dataMgmtService = TestProbe()
    val containerManager = TestProbe()
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))
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
          config,
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

    parent.expectMsg(queueRemovedMsg)

    probe.expectMsg(Transition(fsm, Running, Removing))

    // a newly arrived message should be properly handled
    fsm ! messages(1)
    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(1))))

    fsm ! messages(2)

    fsm ! QueueRemovedCompleted

    // if there is a message, it should not terminate
    Thread.sleep(gracefulShutdownTimeout.toMillis)

    container.send(fsm, getActivation())
    container.expectMsg(ActivationResponse(Right(messages(2))))

    Thread.sleep(gracefulShutdownTimeout.toMillis)

    // it doesn't need to check if all containers are timeout as same version of a new queue will be created in another scheduler.

    watcher.expectMsgAllOf(
      UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
      UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

    probe.expectMsg(Transition(fsm, Removing, Removed))

    probe.expectTerminated(fsm, 10.seconds)
  }

  // this is 10. deprecated case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be deprecated when a new queue supersedes it." in {
    // GracefulShuttingDown is not applicable
    val allStates = List(Running, Idle, Flushing, ActionThrottled, NamespaceThrottled, Removing, Removed)

    allStates.foreach { state =>
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
            config,
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

      fsm ! QueueRemovedCompleted

      probe.expectTerminated(fsm, 10.seconds)
    }
  }

  // this is 10-2. deprecated case in https://yobi.navercorp.com/Lambda-dev/posts/240?referrerId=-2099518320#1612223450057
  it should "be deprecated and stops even if the queue manager could not respond." in {
    // GracefulShuttingDown is not applicable
    val allStates = List(Running, Idle, Flushing, ActionThrottled, NamespaceThrottled, Removing, Removed)

    allStates.foreach { state =>
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
            config,
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
      Thread.sleep(stopGrace.toMillis)

      // the queue is supposed to send queueRemovedMsg once again and stops itself.
      parent.expectMsg(queueRemovedMsg)
      probe.expectTerminated(fsm, 10.seconds)
    }
  }

  // this is to guarantee StopScheduling is handled in all states
  it should "handle StopScheduling in any states." in {
    val allStates =
      List(Running, Idle, ActionThrottled, NamespaceThrottled, Flushing, Removing, Removed)

    allStates.foreach { state =>
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
            config,
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
        // queue will be gracefully shutdown.
        case Removing =>
          // queue should not be terminated as there is an activation
          Thread.sleep(gracefulShutdownTimeout.toMillis)

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          // queue should not be terminated as there is an activation
          Thread.sleep(gracefulShutdownTimeout.toMillis)

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

        case _ =>
          // queue is stale and will be removed
          parent.expectMsg(staleQueueRemovedMsg)
          probe.expectMsg(Transition(fsm, state, Removing))

          fsm ! QueueRemovedCompleted

          // queue should not be terminated as there is an activation
          Thread.sleep(gracefulShutdownTimeout.toMillis)

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          Thread.sleep(gracefulShutdownTimeout.toMillis)

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
            config,
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
          Thread.sleep(gracefulShutdownTimeout.toMillis)

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          // queue should not be terminated as there is an activation
          Thread.sleep(gracefulShutdownTimeout.toMillis)

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

          // queue is stale and will be removed
          parent.expectMsg(queueRemovedMsg)

          probe.expectMsg(Transition(fsm, state, Removed))

          fsm ! QueueRemovedCompleted
          probe.expectTerminated(fsm, 10.seconds)

        case _ =>
          // queue is stale and will be removed
          parent.expectMsg(queueRemovedMsg)
          probe.expectMsg(Transition(fsm, state, Removing))

          fsm ! QueueRemovedCompleted

          // queue should not be terminated as there is an activation
          Thread.sleep(gracefulShutdownTimeout.toMillis)

          container.send(fsm, getActivation())
          container.expectMsg(ActivationResponse(Right(message)))

          Thread.sleep(gracefulShutdownTimeout.toMillis)

          watcher.expectMsgAllOf(
            UnwatchEndpoint(inProgressContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(existingContainerKey, isPrefix = true, watcherName),
            UnwatchEndpoint(leaderKey, isPrefix = false, watcherName))

          probe.expectMsg(Transition(fsm, Removing, Removed))
          probe.expectTerminated(fsm, 10.seconds)
      }
    }
  }

  def registerCallback(probe: TestProbe, c: ActorRef) = {
    c ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(c, Uninitialized))
  }

}
