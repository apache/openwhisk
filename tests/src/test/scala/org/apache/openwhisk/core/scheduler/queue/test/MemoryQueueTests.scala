package org.apache.openwhisk.core.scheduler.queue.test

import java.time.Instant
import java.util.concurrent.Executor
import java.{lang, util}

import akka.actor.ActorRef
import akka.actor.FSM.{CurrentState, StateTimeout, SubscribeTransitionCallBack, Transition}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActor, TestFSMRef, TestKit, TestProbe}
import akka.util.Timeout
import com.google.protobuf.ByteString
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.api._
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.WatchUpdate
import com.ibm.etcd.client.{EtcdClient => Client}
import com.sksamuel.elastic4s.http.ElasticClient
import common.StreamLogging
import org.apache.openwhisk.common.{GracefulShutdown, TransactionId}
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.database.NoDocumentException
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.{existingContainers, inProgressContainer}
import org.apache.openwhisk.core.etcd._
import org.apache.openwhisk.core.scheduler.grpc.{GetActivation, ActivationResponse => GetActivationResponse}
import org.apache.openwhisk.core.scheduler.message.{ContainerCreation, FailedCreationJob, SuccessfulCreationJob}
import org.apache.openwhisk.core.scheduler.queue.MemoryQueue.checkToDropStaleActivation
import org.apache.openwhisk.core.scheduler.queue._
import org.apache.openwhisk.core.service._
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import spray.json.{JsObject, JsString}

import scala.collection.immutable.Queue
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}

@RunWith(classOf[JUnitRunner])
class MemoryQueueTests
    extends MemoryQueueTestsFixture
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with BeforeAndAfter
    with StreamLogging {

  override def beforeEach(): Unit = {
    super.beforeEach()
    NamespaceContainerCount.instances.clear()
    ackedMessageCount = 0
    storedMessageCount = 0
  }
  override def afterAll(): Unit = {
    logLines.foreach(println)
    client.close()
    NamespaceContainerCount.instances.clear()
    TestKit.shutdownActorSystem(system)
  }

  implicit val askTimeout: Timeout = Timeout(5 seconds)

  action.revision(revision)
  val memory = action.limits.memory.megabytes.MB

  val testContainerId = "fakeContainerId"

  val testQueueCreationMessage = CreateQueue(testInvocationNamespace, fqn, revision, actionMetadata)

  val client: Client = {
    val hostAndPorts = "172.17.0.1:2379"
    Client.forEndpoints(hostAndPorts).withPlainText().build()
  }

  def registerCallback(c: ActorRef) = {
    c ! SubscribeTransitionCallBack(testActor)
    expectMsg(CurrentState(c, Uninitialized))
  }

  val mockWatch = new Watch {
    override def close(): Unit = {}

    override def addListener(listener: Runnable, executor: Executor): Unit = {}

    override def cancel(mayInterruptIfRunning: Boolean): Boolean = true

    override def isCancelled: Boolean = true

    override def isDone: Boolean = true

    override def get(): lang.Boolean = true

    override def get(timeout: Long, unit: TimeUnit): lang.Boolean = true
  }

  class mockWatchUpdate extends WatchUpdate {
    private var eventLists: util.List[Event] = new util.ArrayList[Event]()
    override def getHeader: ResponseHeader = ???

    def addEvents(event: Event): WatchUpdate = {
      eventLists.add(event)
      this
    }

    override def getEvents: util.List[Event] = eventLists
  }

  behavior of "MemoryQueue"

  it should "register the endpoint when initializing" in {
    val mockEtcdClient = mock[EtcdClient]
    val prove = TestProbe()
    val watcher = TestProbe()

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
          prove.ref,
          watcher.ref,
          TestProbe().ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    registerCallback(fsm)

    fsm ! Start
    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))
    prove.expectMsg(RegisterInitialData(namespaceThrottlingKey, false.toString, false))
    prove.expectMsg(RegisterData(actionThrottlingKey, false.toString, false))
    fsm ! testInitialDataStorageResult

    expectMsg(Transition(fsm, Uninitialized, Running))
    fsm.stop()
  }

  it should "go to Flushing state if any error happens when the queue is depreacted" in {
    val mockEtcdClient = mock[EtcdClient]
    val prove = TestProbe()
    val watcher = TestProbe()
    val containerManager = TestProbe()

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
          prove.ref,
          watcher.ref,
          containerManager.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    registerCallback(fsm)

    fsm ! Start
    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))
    prove.expectMsg(RegisterInitialData(namespaceThrottlingKey, false.toString, false))
    prove.expectMsg(RegisterData(actionThrottlingKey, false.toString, false))

    expectMsg(Transition(fsm, Uninitialized, Running))

    fsm ! failedInitialDataStorageResult
    expectMsg(Transition(fsm, Running, Removed))

    fsm.stop()
  }

  it should "go to the Running state without storing any data if it receives VersionUpdated" in {
    val mockEtcdClient = mock[EtcdClient]
    val prove = TestProbe()
    val watcher = TestProbe()
    val dataManagementService = TestProbe()

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
          dataManagementService.ref,
          watcher.ref,
          prove.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))

    registerCallback(fsm)

    fsm ! VersionUpdated
    dataManagementService.expectNoMessage()
    expectMsg(Transition(fsm, Uninitialized, Running))

    fsm.stop()
  }

  it should "remove the queue when timeout occurs" in {
    val mockEtcdClient = mock[EtcdClient]
    val parent = TestProbe()
    val dataManagementService = TestProbe()
    val schedulerActor = TestProbe().ref
    val droppingActor = TestProbe().ref

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
          dataManagementService.ref,
          TestProbe().ref,
          TestProbe().ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref,
        "MemoryQueue")

    registerCallback(fsm)

    val probe = TestProbe()
    val probe2 = TestProbe()
    probe watch schedulerActor
    probe watch droppingActor
    probe2 watch fsm

    // do not remove itself when there are still existing containers
    fsm.underlyingActor.containers = Set("1")
    fsm.setState(Running, RunningData(schedulerActor, droppingActor))
    expectMsg(Transition(fsm, Uninitialized, Running))
    fsm ! StateTimeout
    probe.expectNoMessage()
    parent.expectNoMessage()
    probe2.expectNoMessage()
    dataManagementService.expectNoMessage()

    // change the existing containers count to 0, the StateTimeout should work
    fsm.underlyingActor.containers = Set.empty[String]
    fsm ! StateTimeout
    probe.expectTerminated(schedulerActor)
    probe.expectTerminated(droppingActor)
    parent.expectNoMessage()
    expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout
    parent.expectMsg(QueueRemoved(testInvocationNamespace, fqn.toDocId.asDocInfo(revision), Some(leaderKey)))
    expectMsg(Transition(fsm, Idle, Removed))
    fsm ! QueueRemovedCompleted
    dataManagementService.expectMsg(UnregisterData(leaderKey))
    dataManagementService.expectMsg(UnregisterData(namespaceThrottlingKey))
    dataManagementService.expectMsg(UnregisterData(actionThrottlingKey))
    probe2.expectTerminated(fsm)

    fsm.stop()
  }

  it should "back to Running state when got new ActivationMessage when in Idle State" in {
    val mockEtcdClient = mock[EtcdClient]
    val prove = TestProbe()
    val watcher = TestProbe()
    val dataManagementService = TestProbe()
    val tid = TransactionId(TransactionId.generateTid())

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
          dataManagementService.ref,
          watcher.ref,
          prove.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))

    registerCallback(fsm)
    val queueRef = fsm.underlyingActor

    fsm ! Start
    expectMsg(Transition(fsm, Uninitialized, Running))

    fsm.underlyingActor.creationIds = Set.empty[String]
    fsm ! StateTimeout
    expectMsg(Transition(fsm, Running, Idle))

    queueRef.queue.length shouldBe 0
    fsm ! message
    queueRef.queue.length shouldBe 1
    expectMsg(Transition(fsm, Idle, Running))

    (fsm ? GetActivation(tid, fqn, testContainerId, false, None))
      .mapTo[GetActivationResponse]
      .futureValue shouldBe GetActivationResponse(Right(message))

    queueRef.queue.length shouldBe 0
    fsm.underlyingActor.containers = Set.empty[String]
    fsm.underlyingActor.creationIds = Set.empty[String]
    fsm ! StateTimeout
    expectMsg(Transition(fsm, Running, Idle))
    (fsm ? GetActivation(tid, fqn, testContainerId, false, None))
      .mapTo[GetActivationResponse]
      .futureValue shouldBe GetActivationResponse(Left(NoActivationMessage()))

    fsm.stop()
  }

  it should "back to Running state when got new ActivationMessage when in Removed State" in {
    val mockEtcdClient = mock[EtcdClient]
    val prove = TestProbe()
    val watcher = TestProbe()
    val dataManagementService = TestProbe()
    val parent = TestProbe()

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
          dataManagementService.ref,
          watcher.ref,
          prove.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref)

    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerKey, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        inProgressContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)),
      WatchEndpoint(
        existingContainerPrefixKeyByNamespace,
        "",
        true,
        watcherNameForNamespace,
        Set(PutEvent, DeleteEvent)))

    registerCallback(fsm)
    val queueRef = fsm.underlyingActor

    fsm ! Start
    expectMsg(Transition(fsm, Uninitialized, Running))

    fsm.underlyingActor.creationIds = Set.empty[String]
    fsm ! StateTimeout
    expectMsg(Transition(fsm, Running, Idle))

    fsm ! StateTimeout
    expectMsg(Transition(fsm, Idle, Removed))
    queueRef.queue.length shouldBe 0
    fsm ! message
    parent.expectMsg(queueRemovedMsg)
    parent.expectMsg(message)

    expectNoMessage()

    fsm.stop()
  }

  it should "store the received ActivationMessage in the queue" in {
    val mockEtcdClient = new MockEtcdClient(client, isLeader = true)
    val prove = TestProbe()

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
          prove.ref,
          prove.ref,
          prove.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))
    val queueRef = fsm.underlyingActor

    fsm.setState(Running, RunningData(prove.ref, prove.ref))
    queueRef.queue.length shouldBe 0

    fsm ! message

    queueRef.queue.length shouldBe 1

    fsm.stop()

  }

  it should "send a ActivationMessage in response to GetActivation" in {
    val mockEtcdClient = mock[EtcdClient]
    val probe = TestProbe()
    val tid = TransactionId(TransactionId.generateTid())

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
          probe.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))
    fsm.setState(Running, RunningData(TestProbe().ref, TestProbe().ref))

    fsm ! message

    (fsm ? GetActivation(tid, fqn, testContainerId, false, None))
      .mapTo[GetActivationResponse]
      .futureValue shouldBe GetActivationResponse(Right(message))

    fsm.stop()
  }

  it should "send NoActivationMessage in case there is no message in the queue" in {
    val mockEtcdClient = mock[EtcdClient]
    val probe = TestProbe()
    val tid = TransactionId(TransactionId.generateTid())

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
          probe.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    fsm.setState(Running, RunningData(probe.ref, probe.ref))

    fsm ! GetActivation(tid, fqn, testContainerId, false, None)
    // will poll for 1 seconds to return NoActivationMessage, so set the max wait time to 2 seconds here
    expectMsg(2.seconds, GetActivationResponse(Left(NoActivationMessage())))

    fsm.stop()
  }

  it should "poll for the ActivationMessage in case there is no message in the queue" in {
    val mockEtcdClient = mock[EtcdClient]
    val probe = TestProbe()
    val tid = TransactionId(TransactionId.generateTid())

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
          probe.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    fsm.setState(Running, RunningData(probe.ref, probe.ref))

    fsm ! GetActivation(tid, fqn, testContainerId, false, None)
    fsm ! GetActivation(tid, fqn, testContainerId, false, None)
    fsm ! GetActivation(tid, fqn, testContainerId, false, None)
    fsm ! GetActivation(tid, fqn, testContainerId, false, None)
    fsm ! message
    fsm ! message
    fsm ! message

    // will get three activation response, and one NoActivationMessage
    expectMsg(GetActivationResponse(Right(message)))
    expectMsg(GetActivationResponse(Right(message)))
    expectMsg(GetActivationResponse(Right(message)))
    expectMsg(2.seconds, GetActivationResponse(Left(NoActivationMessage())))
    fsm.stop()
  }

  it should "send response to request according to the order of container id and warmed flag" in {
    val mockEtcdClient = mock[EtcdClient]
    val probe = TestProbe()
    val tid = TransactionId(TransactionId.generateTid())

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
          probe.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig))

    fsm.setState(Running, RunningData(probe.ref, probe.ref))

    val sender1 = TestProbe()
    val sender2 = TestProbe()
    val sender3 = TestProbe()
    val sender4 = TestProbe()
    fsm.tell(GetActivation(tid, fqn, "1", false, None), sender1.ref)
    fsm.tell(GetActivation(tid, fqn, "2", false, None), sender2.ref)
    fsm.tell(GetActivation(tid, fqn, "3", false, None), sender3.ref)
    fsm.tell(GetActivation(tid, fqn, "4", false, None), sender4.ref)
    fsm ! message
    fsm ! message
    fsm ! message

    // sender 2-4 will get a message while sender 1 will get a NoActivationMessage
    sender4.expectMsg(GetActivationResponse(Right(message)))
    sender3.expectMsg(GetActivationResponse(Right(message)))
    sender2.expectMsg(GetActivationResponse(Right(message)))
    sender1.expectMsg(2.seconds, GetActivationResponse(Left(NoActivationMessage())))

    // container "1" is warmed one
    fsm.tell(GetActivation(tid, fqn, "1", true, None), sender1.ref)
    fsm.tell(GetActivation(tid, fqn, "2", false, None), sender2.ref)
    fsm.tell(GetActivation(tid, fqn, "3", false, None), sender3.ref)
    fsm.tell(GetActivation(tid, fqn, "4", false, None), sender4.ref)
    fsm ! message
    fsm ! message
    fsm ! message

    // sender 1, 3, 4 will get a message while sender 2 will get a NoActivationMessage
    sender1.expectMsg(GetActivationResponse(Right(message)))
    sender3.expectMsg(GetActivationResponse(Right(message)))
    sender4.expectMsg(GetActivationResponse(Right(message)))
    sender2.expectMsg(2.seconds, GetActivationResponse(Left(NoActivationMessage())))
    fsm.stop()
  }

  it should "send a container creation request to ContainerManager at initialization time" in {
    val mockEtcdClient = mock[EtcdClient]
    val containerManger = TestProbe()
    val probe = TestProbe()
    val decisionMaker = TestProbe()

    // This pilot must conform to SchedulingDecisionMaker
    // Since a queue tries to add the initial container at startup, this pilot just makes a decision accordingly.
    decisionMaker.setAutoPilot((sender: ActorRef, msg) => {
      msg match {
        case msg: QueueSnapshot =>
          sender ! DecisionResults(AddContainer, 1)
      }
      TestActor.KeepRunning
    })

    val schedulerHost = endpoints.host
    val rpcPort = endpoints.rpcPort

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
          probe.ref,
          probe.ref,
          containerManger.ref,
          decisionMaker.ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        probe.ref,
        "MemoryQueue")

    fsm ! Start
    containerManger.expectMsgPF(10 seconds) {
      case ContainerCreation(
          List(
            ContainerCreationMessage(
              _,
              `testInvocationNamespace`,
              `fqn`,
              _,
              `actionMetadata`,
              `schedulerId`,
              `schedulerHost`,
              `rpcPort`,
              _,
              _)),
          `memory`,
          `testInvocationNamespace`) =>
        true
    }

    fsm.stop()
  }

  it should "complete error activation while received FailedCreationJob and the error is not a whisk error(unrecoverable)" in {
    val mockEtcdClient = mock[EtcdClient]
    val testProbe = TestProbe()
    val parent = TestProbe()
    val expectedCount = 3

    val probe = TestProbe()

    val newAck = new ActiveAck {
      override def apply(tid: TransactionId,
                         activationResult: WhiskActivation,
                         blockingInvoke: Boolean,
                         controllerInstance: ControllerInstanceId,
                         userId: UUID,
                         acknowledegment: AcknowledegmentMessage): Future[Any] = {
        probe.ref ! activationResult.response
        Future.successful({})
      }
    }

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
          testProbe.ref,
          testProbe.ref,
          testProbe.ref,
          TestProbe().ref,
          schedulerId,
          newAck,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref,
        "MemoryQueue")

    fsm ! SubscribeTransitionCallBack(parent.ref)
    parent.expectMsg(CurrentState(fsm, Uninitialized))
    parent watch fsm

    fsm ! Start

    parent.expectMsg(Transition(fsm, Uninitialized, Running))

    (1 to expectedCount).foreach(_ => fsm ! message)
    fsm ! FailedCreationJob(
      testCreationId,
      message.user.namespace.name.asString,
      message.action,
      message.revision,
      ContainerCreationError.NonExecutableActionError,
      "nonExecutbleAction error")
    parent.expectMsg(Transition(fsm, Running, Flushing))
    (1 to expectedCount).foreach(_ => probe.expectMsg(ActivationResponse.developerError("nonExecutbleAction error")))

    parent.expectMsg(
      queueConfig.stopGrace + 5.seconds,
      QueueRemoved(testInvocationNamespace, fqn.toDocId.asDocInfo(action.rev), Some(leaderKey)))
    parent.expectMsg(Transition(fsm, Flushing, Removed))
    fsm ! QueueRemovedCompleted
    parent.expectTerminated(fsm)

    fsm.stop()
  }

  it should "complete error activation after timeout while received FailedCreationJob and the error is a whisk error(recoverable)" in {
    val mockEtcdClient = mock[EtcdClient]
    val testProbe = TestProbe()
    val decisionMaker = TestProbe()
    decisionMaker.ignoreMsg { case _: QueueSnapshot => true }
    val parent = TestProbe()
    val expectedCount = 3

    val probe = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val queueConfig = QueueConfig(5 seconds, 10 seconds, 10 seconds, 5 seconds, 10, 180000, 0.9, 10)

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
          testProbe.ref,
          testProbe.ref,
          testProbe.ref,
          decisionMaker.ref,
          schedulerId,
          ack,
          store,
          (s: String) => { Future.successful(10000) }, // avoid exceed user limit
          checkToDropStaleActivation,
          queueConfig),
        parent.ref,
        "MemoryQueue")

    fsm ! SubscribeTransitionCallBack(parent.ref)
    parent.expectMsg(CurrentState(fsm, Uninitialized))

    fsm ! Start

    parent.expectMsg(Transition(fsm, Uninitialized, Running))

    (1 to expectedCount).foreach(_ => fsm ! message)
    fsm ! FailedCreationJob(
      testCreationId,
      message.user.namespace.name.asString,
      message.action,
      message.revision,
      ContainerCreationError.NoAvailableInvokersError,
      "no available invokers")

    awaitAssert({
      ackedMessageCount shouldBe 3
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("no available invokers")))
      storedMessageCount shouldBe 3
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("no available invokers")))
    }, 5.seconds)

    parent.expectMsg(Transition(fsm, Running, Flushing))

    // should goto Running
    fsm ! SuccessfulCreationJob(testCreationId, message.user.namespace.name.asString, message.action, message.revision)
    (1 to expectedCount).foreach(_ => fsm ! message)
    parent.expectMsg(Transition(fsm, Flushing, Running))
    probe.expectNoMessage(2.seconds)

    // should goto WaitForFlush again as existing is always 0
    fsm ! FailedCreationJob(
      testCreationId,
      message.user.namespace.name.asString,
      message.action,
      message.revision,
      ContainerCreationError.ResourceNotEnoughError,
      "resource not enough")
    parent.expectMsg(Transition(fsm, Running, Flushing))
    (1 to expectedCount).foreach(_ => fsm ! message)

    // wait for event `FlushPulse`, and then all existing activations will be flushed
    Thread.sleep(flushGrace.toMillis + 3.seconds.toMillis)

    awaitAssert({
      ackedMessageCount shouldBe 9
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("resource not enough")))
      storedMessageCount shouldBe 9
      lastAckedActivationResult.response.result shouldBe Some(JsObject("error" -> JsString("resource not enough")))
    }, 5.seconds)

    // should goto Running
    fsm ! SuccessfulCreationJob(testCreationId, message.user.namespace.name.asString, message.action, message.revision)
    (1 to expectedCount).foreach(_ => fsm ! message)
    parent.expectMsg(Transition(fsm, Flushing, Running))
    probe.expectNoMessage(2.seconds)
    fsm.stop()

  }

  it should "stop scheduling if the namespace does not exist" in {
    val mockEtcdClient = mock[EtcdClient]
    val getZeroLimit = (_: String) => { Future.failed(NoDocumentException("namespace does not exist")) }
    val testProbe = TestProbe()
    val parent = TestProbe()

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
          testProbe.ref,
          testProbe.ref,
          testProbe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getZeroLimit,
          checkToDropStaleActivation,
          queueConfig),
        parent.ref,
        "MemoryQueue")
    val probe = TestProbe()
    probe watch fsm

    fsm ! SubscribeTransitionCallBack(parent.ref)
    fsm ! Start

    parent.expectMsg(10 seconds, CurrentState(fsm, Uninitialized))
    parent.expectMsg(10 seconds, Transition(fsm, Uninitialized, Running))

    Thread.sleep(idleGrace.toMillis)

    parent expectMsg QueueRemoved(testInvocationNamespace, fqn.toDocId.asDocInfo(revision), None)
    parent.expectMsg(10 seconds, Transition(fsm, Running, Removing))

    fsm ! QueueRemovedCompleted
    parent.expectMsg(10 seconds, Transition(fsm, Removing, Removed))

    probe.expectTerminated(fsm)
  }

  it should "throttle the namespace when the limit is already reached" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val parent = TestProbe()

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef(
      {
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
          dataManagementService.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig)
      },
      probe.ref,
      "MemoryQueue")

    fsm ! SubscribeTransitionCallBack(parent.ref)
    parent.expectMsg(CurrentState(fsm, Uninitialized))

    fsm ! Start
    dataManagementService.expectMsg(RegisterInitialData(namespaceThrottlingKey, false.toString, false))
    dataManagementService.expectMsg(RegisterData(actionThrottlingKey, false.toString, false))

    parent.expectMsg(10 seconds, Transition(fsm, Uninitialized, Running))

    fsm ! EnableNamespaceThrottling(dropMsg = true)
    parent.expectMsg(10 seconds, Transition(fsm, Running, Flushing))
    dataManagementService.expectMsg(RegisterData(namespaceThrottlingKey, true.toString, false))

    fsm.stop()
  }

  it should "disable namespace throttling when the capacity become available" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val decisionMaker = TestProbe()

    // This test pilot mimic the decision maker who disable the namespace throttling when there is enough capacity.
    decisionMaker.setAutoPilot((sender: ActorRef, msg) => {
      msg match {
        case QueueSnapshot(_, _, _, _, _, _, _, _, _, _, NamespaceThrottled, _) =>
          sender ! DisableNamespaceThrottling

        case _ =>
        //do nothing
      }
      TestActor.KeepRunning
    })

    // it always induces the throttling
    val getUserLimit = (_: String) => { Future.successful(4) }

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef({
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
        dataManagementService.ref,
        probe.ref,
        probe.ref,
        decisionMaker.ref,
        schedulerId,
        ack,
        store,
        getUserLimit,
        checkToDropStaleActivation,
        queueConfig)
    })

    registerCallback(fsm)

    fsm ! Start

    expectMsg(10 seconds, Transition(fsm, Uninitialized, Running))

    fsm.setState(NamespaceThrottled, ThrottledData(probe.ref, probe.ref))
    expectMsg(10 seconds, Transition(fsm, Running, NamespaceThrottled))
    expectMsg(10 seconds, Transition(fsm, NamespaceThrottled, Running))

    dataManagementService.expectMsg(RegisterInitialData(namespaceThrottlingKey, false.toString, false))

    fsm.stop()
  }

  it should "throttle the action when the number of messages reaches the limit" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = TestProbe()
    val probe = TestProbe()

    // it always induces the throttling
    val getZeroLimit = (_: String) => { Future.successful(2) }

    val queueConfig = QueueConfig(5 seconds, 10 seconds, 10 seconds, 5 seconds, 1, 5000, 0.9, 10)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef {
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
        dataManagementService.ref,
        probe.ref,
        probe.ref,
        TestProbe().ref,
        schedulerId,
        ack,
        store,
        getZeroLimit,
        checkToDropStaleActivation,
        queueConfig)
    }

    registerCallback(fsm)

    fsm.setState(Running, RunningData(probe.ref, probe.ref))
    expectMsg(10 seconds, Transition(fsm, Uninitialized, Running))
    fsm ! message

    dataManagementService.expectMsg(RegisterData(actionThrottlingKey, true.toString, false))

    expectMsg(10 seconds, Transition(fsm, Running, ActionThrottled))

    fsm.stop()
  }

  it should "disable action throttling when the number of messages is under throttling fraction" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val parent = TestProbe()

    val queueConfig = QueueConfig(5 seconds, 10 seconds, 10 seconds, 5 seconds, 10, 5000, 0.9, 10)
    val msgRetentionSize = queueConfig.maxRetentionSize

    val tid = TransactionId(TransactionId.generateTid())

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef {
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
        dataManagementService.ref,
        probe.ref,
        probe.ref,
        TestProbe().ref,
        schedulerId,
        ack,
        store,
        getUserLimit,
        checkToDropStaleActivation,
        queueConfig)
    }

    fsm ! SubscribeTransitionCallBack(parent.ref)
    parent.expectMsg(CurrentState(fsm, Uninitialized))

    fsm.setState(Running, RunningData(probe.ref, probe.ref))
    parent.expectMsg(Transition(fsm, Uninitialized, Running))

    (1 to msgRetentionSize).foreach { _ =>
      fsm ! message
    }

    parent.expectMsg(Transition(fsm, Running, ActionThrottled))
    dataManagementService.expectMsg(RegisterData(actionThrottlingKey, true.toString, false))

    fsm ! GetActivation(tid, fqn, testContainerId, false, None)

    //receive one activation message
    parent.expectMsg(Transition(fsm, ActionThrottled, Running))
    dataManagementService.expectMsg(RegisterData(actionThrottlingKey, false.toString, false))

    fsm.stop()
  }

  it should "update the number of containers based on Watch event" in {
    val mockEtcdClient = new MockEtcdClient(client, true)
    val probe = TestProbe()
    val watcher = system.actorOf(WatcherService.props(mockEtcdClient))
    val testSchedulingDecisionMaker = system.actorOf(SchedulingDecisionMaker.props(testInvocationNamespace, fqn))

    val mockFunction = (_: String) => {
      Future.successful(4)
    }

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef {
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
        probe.ref,
        watcher,
        probe.ref,
        testSchedulingDecisionMaker,
        schedulerId,
        ack,
        store,
        mockFunction,
        checkToDropStaleActivation,
        queueConfig)
    }

    fsm.setState(Uninitialized)
    fsm ! Start

    val memoryQueue = fsm.underlyingActor
    val newFqn = fqn.copy(version = Some(SemVer(0, 0, 2)))
    val newRevision = DocRevision("2-testRev")

    memoryQueue.containers.size shouldBe 0
    memoryQueue.creationIds.size shouldBe 0
    memoryQueue.namespaceContainerCount.existingContainerNumByNamespace shouldBe 0
    memoryQueue.namespaceContainerCount.inProgressContainerNumByNamespace shouldBe 0

    val testInvoker = InvokerInstanceId(0, userMemory = 1024.MB)

    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(testInvocationNamespace, fqn, revision, schedulerId, CreationId("testId1")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      existingContainers(
        testInvocationNamespace,
        fqn,
        revision,
        Some(testInvoker),
        Some(ContainerId("test-containerId1"))),
      "test-value")

    // container with other version should not be counted
    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(testInvocationNamespace, newFqn, newRevision, schedulerId, CreationId("testId2")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      existingContainers(
        testInvocationNamespace,
        newFqn,
        newRevision,
        Some(testInvoker),
        Some(ContainerId("test-containerId2"))),
      "test-value")

    Thread.sleep(1000)
    memoryQueue.containers.size shouldBe 1
    memoryQueue.creationIds.size shouldBe 1
    memoryQueue.namespaceContainerCount.existingContainerNumByNamespace shouldBe 2
    memoryQueue.namespaceContainerCount.inProgressContainerNumByNamespace shouldBe 2

    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(testInvocationNamespace, fqn, revision, schedulerId, CreationId("testId3")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      existingContainers(
        testInvocationNamespace,
        fqn,
        revision,
        Some(testInvoker),
        Some(ContainerId("test-containerId3"))),
      "test-value")

    // container with other version should not be counted
    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(testInvocationNamespace, newFqn, newRevision, schedulerId, CreationId("testId4")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      existingContainers(
        testInvocationNamespace,
        newFqn,
        newRevision,
        Some(testInvoker),
        Some(ContainerId("test-containerId4"))),
      "test-value")

    Thread.sleep(1000)
    memoryQueue.containers.size shouldBe 2
    memoryQueue.creationIds.size shouldBe 2
    memoryQueue.namespaceContainerCount.existingContainerNumByNamespace shouldBe 4
    memoryQueue.namespaceContainerCount.inProgressContainerNumByNamespace shouldBe 4

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(testInvocationNamespace, fqn, revision, schedulerId, CreationId("testId1")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(testInvocationNamespace, fqn, revision, schedulerId, CreationId("testId3")),
      "test-value")

    // container with other version should not be counted
    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(testInvocationNamespace, newFqn, newRevision, schedulerId, CreationId("testId2")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(testInvocationNamespace, newFqn, newRevision, schedulerId, CreationId("testId4")),
      "test-value")

    Thread.sleep(1000)
    memoryQueue.containers.size shouldBe 2
    memoryQueue.creationIds.size shouldBe 0
    memoryQueue.namespaceContainerCount.inProgressContainerNumByNamespace shouldBe 0
    memoryQueue.namespaceContainerCount.existingContainerNumByNamespace shouldBe 4

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      existingContainers(
        testInvocationNamespace,
        fqn,
        revision,
        Some(testInvoker),
        Some(ContainerId("test-containerId1"))),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      existingContainers(
        testInvocationNamespace,
        fqn,
        revision,
        Some(testInvoker),
        Some(ContainerId("test-containerId3"))),
      "test-value")

    // container with other version should not be counted
    mockEtcdClient.publishEvents(
      EventType.DELETE,
      existingContainers(
        testInvocationNamespace,
        newFqn,
        newRevision,
        Some(testInvoker),
        Some(ContainerId("test-containerId2"))),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      existingContainers(
        testInvocationNamespace,
        newFqn,
        newRevision,
        Some(testInvoker),
        Some(ContainerId("test-containerId4"))),
      "test-value")

    memoryQueue.creationIds.size shouldBe 0

    Thread.sleep(1000)
    memoryQueue.containers.size shouldBe 0
    memoryQueue.creationIds.size shouldBe 1 //if there is no container, the queue tries to create one container
    memoryQueue.namespaceContainerCount.inProgressContainerNumByNamespace shouldBe 0
    memoryQueue.namespaceContainerCount.existingContainerNumByNamespace shouldBe 0
  }

  private def getData(states: List[MemoryQueueState]) = {
    val schedulingActors = List.fill(states.size)(TestProbe())
    val droppingActors = List.fill(states.size)(TestProbe())
    val data =
      (schedulingActors zip droppingActors)
        .map {
          case (schedulingActor, droppingActor) =>
            RunningData(schedulingActor.ref, droppingActor.ref)
        }
    (schedulingActors, droppingActors, data)
  }
  it should "clean up throttling data when it stops gracefully" in {
    val mockEtcdClient = mock[EtcdClient]

    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val actorProbe = TestProbe()
    val states = List(Running, ActionThrottled, NamespaceThrottled, Flushing)
    val (schedulingActors, droppingActors, data) = getData(states)

    val fsmList = (1 to states.size).map { index =>
      expectDurationChecking(mockEsClient, testInvocationNamespace)
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
          dataManagementService.ref,
          probe.ref,
          probe.ref,
          TestProbe().ref,
          schedulerId,
          ack,
          store,
          getUserLimit,
          checkToDropStaleActivation,
          queueConfig),
        probe.ref,
        s"MemoryQueue$index")
    }.toList

    schedulingActors foreach (actorProbe watch _.ref)
    droppingActors foreach (actorProbe watch _.ref)

    fsmList zip states zip data foreach {
      case ((fsm, state), datum) =>
        fsm.setState(state, datum)
    }

    fsmList zip data foreach {
      case (fsm, RunningData(schedulingActor, droppingActor)) =>
        fsm ! GracefulShutdown

        inAnyOrder {
          dataManagementService.expectMsg(UnregisterData(leaderKey))
          dataManagementService.expectMsg(UnregisterData(namespaceThrottlingKey))
          dataManagementService.expectMsg(UnregisterData(actionThrottlingKey))
        }
    }

    fsmList foreach { _.stop() }
  }

  behavior of "drop function"

  val completeErrorActivation = (msg: ActivationMessage, reason: String, isWhiskError: Boolean) => {
    Future.successful({})
  }

  it should "drop the old activation from the queue" in {
    var queue = Queue.empty[TimeSeriesActivationEntry]

    val now = Instant.now
    val records = List(
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 1000), message),
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 2000), message),
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 3000), message),
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 10000), message),
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 20000), message),
      TimeSeriesActivationEntry(Instant.ofEpochMilli(now.toEpochMilli + 30000), message),
    )

    records.foreach(record => queue = queue.enqueue(record))

    Thread.sleep(5000)

    queue = MemoryQueue.dropOld(queue, java.time.Duration.ofMillis(1000), completeErrorActivation)

    queue.size shouldBe 3
  }

  it should "not raise any exception with empty queue" in {
    var queue = Queue.empty[TimeSeriesActivationEntry]

    noException should be thrownBy {
      queue = MemoryQueue.dropOld(queue, java.time.Duration.ofMillis(1000), completeErrorActivation)
    }
  }

  behavior of "duration checker"

  it should "check the duration once" in {
    val mockEtcdClient = mock[EtcdClient]

    val dataManagementService = TestProbe()
    val probe = TestProbe()

    val mockEsClient = mock[ElasticClient]
    val durationChecker = new ElasticSearchDurationChecker(mockEsClient, durationCheckWindow)

    expectDurationChecking(mockEsClient, testInvocationNamespace)

    val fsm = TestFSMRef(
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
        dataManagementService.ref,
        probe.ref,
        probe.ref,
        TestProbe().ref,
        schedulerId,
        ack,
        store,
        getUserLimit,
        checkToDropStaleActivation,
        queueConfig),
      probe.ref)

    fsm ! Start

    Thread.sleep(1000)
  }

  class MockWatcher extends Watch {
    var isClosed = false

    override def close(): Unit = {
      isClosed = true
    }

    override def addListener(listener: Runnable, executor: Executor): Unit = {}

    override def cancel(mayInterruptIfRunning: Boolean): Boolean = true

    override def isCancelled: Boolean = true

    override def isDone: Boolean = true

    override def get(): lang.Boolean = true

    override def get(timeout: Long, unit: TimeUnit): lang.Boolean = true
  }

  class MockEtcdClient(client: Client, isLeader: Boolean, leaseNotFound: Boolean = false, failedCount: Int = 1)
      extends EtcdClient(client)(ece) {
    var count = 0
    var storedValues = List.empty[(String, String, Long, Long)]
    var dataMap = Map[String, String]()

    override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] = {
      if (isLeader) {
        storedValues = (key, value.toString, cmpVersion, leaseId) :: storedValues
      }
      Future.successful(TxnResponse.newBuilder().setSucceeded(isLeader).build())
    }

    /*
     * this method count the number of entries whose key starts with the given prefix
     */
    override def getCount(prefixKey: String): Future[Long] = {
      Future.successful { dataMap.count(data => data._1.startsWith(prefixKey)) }
    }

    var watchCallbackMap = Map[String, WatchUpdate => Unit]()

    override def keepAliveOnce(leaseId: Long): Future[LeaseKeepAliveResponse] =
      Future.successful(LeaseKeepAliveResponse.newBuilder().setID(leaseId).build())

    /*
     * this method adds one callback for the given key in watchCallbackMap.
     *
     * Note: Currently it only supports prefix-based watch.
     */
    override def watchAllKeys(next: WatchUpdate => Unit, error: Throwable => Unit, completed: () => Unit): Watch = {

      watchCallbackMap += "" -> next
      new Watch {
        override def close(): Unit = {}

        override def addListener(listener: Runnable, executor: Executor): Unit = {}

        override def cancel(mayInterruptIfRunning: Boolean): Boolean = true

        override def isCancelled: Boolean = true

        override def isDone: Boolean = true

        override def get(): lang.Boolean = true

        override def get(timeout: Long, unit: TimeUnit): lang.Boolean = true
      }
    }

    /*
     * This method stores the data in dataMap to simulate etcd.put()
     * After then, it calls the registered watch callback for the given key
     * So we don't need to call put() to simulate watch API.
     * Expected order of calls is 1. watch(), 2.publishEvents(). Data will be stored in dataMap and
     * callbacks in the callbackMap for the given prefix will be called by publishEvents()
     *
     * Note: watch callback is currently registered based on prefix only.
     */
    def publishEvents(eventType: EventType, key: String, value: String): Unit = {
      val eType = eventType match {
        case EventType.PUT =>
          dataMap += key -> value
          EventType.PUT

        case EventType.DELETE =>
          dataMap -= key
          EventType.DELETE

        case EventType.UNRECOGNIZED => Event.EventType.UNRECOGNIZED
      }
      val event = Event
        .newBuilder()
        .setType(eType)
        .setPrevKv(
          KeyValue
            .newBuilder()
            .setKey(ByteString.copyFromUtf8(key))
            .setValue(ByteString.copyFromUtf8(value))
            .build())
        .setKv(
          KeyValue
            .newBuilder()
            .setKey(ByteString.copyFromUtf8(key))
            .setValue(ByteString.copyFromUtf8(value))
            .build())
        .build()

      // find the callbacks which has the proper prefix for the given key
      watchCallbackMap.filter(callback => key.startsWith(callback._1)).foreach { callback =>
        callback._2(new mockWatchUpdate().addEvents(event))
      }
    }
  }
}
