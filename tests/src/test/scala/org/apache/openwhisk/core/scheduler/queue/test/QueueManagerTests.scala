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

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicInteger
import akka.actor.{Actor, ActorIdentity, ActorRef, ActorRefFactory, ActorSystem, Identify, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.ibm.etcd.api.{KeyValue, RangeResponse}
import common.{LoggedFunction, StreamLogging}
import org.apache.openwhisk.common.{GracefulShutdown, TransactionId}
import org.apache.openwhisk.core.WarmUp.warmUpAction
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.test.TestConnector
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, ActivationMessage, GetState, StatusData}
import org.apache.openwhisk.core.database.{ArtifactStore, DocumentRevisionMismatchException, UserContext}
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdKV.QueueKeys
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdFollower, EtcdLeader}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.scheduler.grpc.test.CommonVariable
import org.apache.openwhisk.core.scheduler.grpc.{ActivationResponse, GetActivation}
import org.apache.openwhisk.core.scheduler.queue._
import org.apache.openwhisk.core.scheduler.{SchedulerEndpoints, SchedulerStates}
import org.apache.openwhisk.core.service._
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class QueueManagerTests
    extends TestKit(ActorSystem("QueueManager"))
    with CommonVariable
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with StreamLogging {

  override def afterAll: Unit = {
    QueuePool.clear()
    TestKit.shutdownActorSystem(system)
  }
  override def beforeEach = QueuePool.clear()
  implicit val askTimeout = Timeout(5 seconds)
  implicit val ec = system.dispatcher

  val entityStore = WhiskEntityStore.datastore()

  val schedulerId = SchedulerInstanceId("0")
  val testQueueCreationMessage =
    CreateQueue(testInvocationNamespace, testFQN, testDocRevision, testActionMetaData)

  val schedulerEndpoint = SchedulerEndpoints("127.0.0.1", 8080, 2552)
  val mockConsumer = new TestConnector(s"scheduler${schedulerId.asString}", 4, true)

  val messageTransId = TransactionId(TransactionId.testing.meta.id)
  val uuid = UUID()

  val action = ExecutableWhiskAction(testEntityPath, testEntityName, testExec)
  val testLeaderKey = QueueKeys.queue(testInvocationNamespace, action.fullyQualifiedName(false), true)

  val activationMessage = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    testDocRevision,
    Identity(
      Subject(),
      Namespace(EntityName(testInvocationNamespace), uuid),
      BasicAuthenticationAuthKey(uuid, Secret()),
      Set.empty),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = None)
  val statusData =
    StatusData(testInvocationNamespace, testFQN.asString, List.empty[ActivationId], "Running", "RunningData")

  // update start time for activation to ensure it's not stale
  def newActivation(start: Instant = Instant.now()): ActivationMessage = {
    activationMessage.copy(transid = TransactionId(messageTransId.meta.copy(start = start)))
  }

  val activationResponse = ActivationResponse(Right(activationMessage))

  val ack = new ActiveAck {
    override def apply(tid: TransactionId,
                       activationResult: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      Future.successful({})

    }
  }

  val store: (TransactionId, WhiskActivation, UserContext) => Future[Any] =
    (tid: TransactionId, activationResult: WhiskActivation, contest: UserContext) => Future.successful(())

  val testLeaseId = 60

  val childFactory =
    (system: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) =>
      system.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case GetActivation(_, _, _, _, _, _) =>
            sender ! ActivationResponse(Right(newActivation()))
          case GetState =>
            sender ! statusData
        }
      }))

  def convertToMetaData(action: WhiskAction): WhiskActionMetaData = {
    val exec = CodeExecMetaDataAsString(RuntimeManifest(action.exec.kind, ImageName("test")), entryPoint = Some("test"))
    WhiskActionMetaData(
      action.namespace,
      action.name,
      exec,
      action.parameters,
      action.limits,
      action.version,
      action.publish,
      action.annotations)
      .revision[WhiskActionMetaData](action.rev)
  }

  /**get WhiskActionMetaData*/
  def getWhiskActionMetaData(meta: Future[WhiskActionMetaData]) = LoggedFunction {
    (_: ArtifactStore[WhiskEntity], _: DocId, _: DocRevision, _: Boolean, _: Boolean) =>
      meta
  }

  val get = getWhiskActionMetaData(Future(convertToMetaData(action.toWhiskAction.revision(testDocRevision))))
  val failedGet = getWhiskActionMetaData(Future.failed(new Exception("error")))

  val watchEndpoint =
    WatchEndpoint(QueueKeys.queuePrefix, "", isPrefix = true, "queue-manager", Set(PutEvent, DeleteEvent))

  behavior of "QueueManager"

  it should "get the remote actor ref and send the message" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val testQueueManagerActorName = "QueueManagerActorSelectionTest"
    val watcher = TestProbe()

    system.actorOf(
      QueueManager
        .props(
          entityStore,
          get,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer),
      testQueueManagerActorName)

    watcher.expectMsg(watchEndpoint)
    val testQueueManagerPath = s"akka://QueueManager/user/${testQueueManagerActorName}"

    val selected = system.actorSelection(testQueueManagerPath)

    val ActorIdentity(_, Some(ref)) = (selected ? Identify(testQueueManagerPath)).mapTo[ActorIdentity].futureValue

    (ref ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)
  }

  it should "create a queue in response to a queue creation request" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)
  }

  it should "response queue creation request when failed to do election" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService(false)
    val watcher = TestProbe()

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)
  }

  it should "not create a queue if there is already a queue for the given fqn" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)
    dataManagementService.expectMsg(ElectLeader(testLeaderKey, schedulerEndpoint.serialize, queueManager))

    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

  }

  it should "only do leader election for one time if there are more than one create queue requests incoming" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    dataManagementService.ignoreMsg {
      case _: UpdateDataOnChange => true
    }
    val watcher = TestProbe()

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)

    val probe = TestProbe()
    queueManager.tell(testQueueCreationMessage, probe.ref)
    queueManager.tell(testQueueCreationMessage, probe.ref)
    queueManager.tell(testQueueCreationMessage, probe.ref)
    queueManager.tell(testQueueCreationMessage, probe.ref)

    // dataManagementService should only do one election
    dataManagementService.expectMsg(ElectLeader(testLeaderKey, schedulerEndpoint.serialize, queueManager))
    dataManagementService.expectNoMessage()

    // all four requests should get responses
    probe.expectMsg(CreateQueueResponse(testInvocationNamespace, testFQN, true))
    probe.expectMsg(CreateQueueResponse(testInvocationNamespace, testFQN, true))
    probe.expectMsg(CreateQueueResponse(testInvocationNamespace, testFQN, true))
    probe.expectMsg(CreateQueueResponse(testInvocationNamespace, testFQN, true))
  }

  private def getTestDataManagementService(success: Boolean = true) = {
    val dataManagementService = TestProbe()
    dataManagementService.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ElectLeader(key, value, _, _) =>
          if (success) {
            sender ! ElectionResult(Right(EtcdLeader(key, value, 10)))
          } else {
            sender ! ElectionResult(Left(EtcdFollower(key, value)))
          }
          TestActor.KeepRunning

        case _ =>
          TestActor.KeepRunning
    })
    dataManagementService
  }

  it should "forward msg to remote queue when queue exist on remote" in {
    stream.reset()
    val leaderKey = QueueKeys.queue(
      activationMessage.user.namespace.name.asString,
      activationMessage.action.copy(version = None),
      true)
    val mockEtcdClient = mock[EtcdClient]
    (mockEtcdClient
      .get(_: String))
      .expects(*)
      .returning(
        Future.successful(
          RangeResponse
            .newBuilder()
            .addKvs(KeyValue.newBuilder().setKey(leaderKey).setValue(schedulerEndpoint.serialize).build())
            .build()))
      .once()
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val probe = TestProbe()

    val childFactory =
      (_: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) => probe.ref

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))
    watcher.expectMsg(watchEndpoint)

    // got a message but no queue created on this scheduler
    // it should try to got leader key from etcd and forward this msg to remote queue, here is `schedulerEndpoints`
    queueManager ! newActivation()
    stream.toString should include(s"send activation to remote queue, key: $leaderKey")
    stream.toString should include(s"add a new actor selection to a map with key: $leaderKey")
    stream.reset()

    // got msg again, and it should get remote queue from memory instead of etcd
    val msg2 = newActivation().copy(activationId = ActivationId.generate())
    queueManager ! msg2
    stream.toString shouldNot include(s"send activation to remote queue, key: $leaderKey")
  }

  it should "create a new MemoryQueue when the revision matches with the one in a datastore" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val probe = TestProbe()

    val childFactory =
      (_: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) => probe.ref

    val newRevision = DocRevision("2-test-revision")
    val newFqn = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 2)))
    val newGet = getWhiskActionMetaData(
      Future(convertToMetaData(action.copy(version = SemVer(0, 0, 2)).toWhiskAction.revision(newRevision))))
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            newGet,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    //current queue's revision is `1-test-revision`
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    probe.expectMsg(Start)

    //the activationMessage's revision(2-test-revision) is newer than current queue's revision(1-test-revision)
    val activationMessage = ActivationMessage(
      messageTransId,
      newFqn,
      newRevision,
      Identity(
        Subject(),
        Namespace(EntityName(testInvocationNamespace), uuid),
        BasicAuthenticationAuthKey(uuid, Secret()),
        Set.empty),
      ActivationId.generate(),
      ControllerInstanceId("0"),
      blocking = false,
      content = None)

    queueManager ! activationMessage
    val msgs = (0 to 10).map(i => {
      activationMessage.copy(activationId = ActivationId.generate())
    })
    msgs.foreach(msg => queueManager ! msg) // even send multiple requests, we should only create new queue for once
    probe.expectMsg(StopSchedulingAsOutdated)
    probe.expectMsg(VersionUpdated)
    probe.expectMsg(activationMessage)
    msgs.foreach(msg => probe.expectMsg(msg))
  }

  it should "create a new MemoryQueue correctly when the action is updated again during updating the queue" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val probe = TestProbe()
    val queueWatcher = TestProbe()

    val childFactory =
      (_: ActorRefFactory,
       _: String,
       fqn: FullyQualifiedEntityName,
       revision: DocRevision,
       metadata: WhiskActionMetaData) => {
        queueWatcher.ref ! (fqn, revision)
        probe.ref
      }

    val newRevision = DocRevision("2-test-revision")
    val newFqn = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 2)))
    val finalFqn = newFqn.copy(version = Some(SemVer(0, 0, 3)))
    val finalRevision = DocRevision("3-test-revision")
    // simulate the case that action is updated again while fetch it from database
    def newGet(store: ArtifactStore[WhiskEntity], docId: DocId, docRevision: DocRevision, fromCache: Boolean, ignoreMissingAttachment: Boolean) = {
      if (docRevision == DocRevision.empty) {
        Future(convertToMetaData(action.copy(version = SemVer(0, 0, 3)).toWhiskAction.revision(finalRevision)))
      } else
        Future.failed(DocumentRevisionMismatchException("mismatch"))
    }
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            newGet,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    //current queue's revision is `1-test-revision`
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    queueWatcher.expectMsg((testFQN, testDocRevision))
    probe.expectMsg(Start)

    //the activationMessage's revision(2-test-revision) is newer than current queue's revision(1-test-revision)
    val activationMessage = ActivationMessage(
      messageTransId,
      newFqn,
      newRevision,
      Identity(
        Subject(),
        Namespace(EntityName(testInvocationNamespace), uuid),
        BasicAuthenticationAuthKey(uuid, Secret()),
        Set.empty),
      ActivationId.generate(),
      ControllerInstanceId("0"),
      blocking = false,
      content = None)

    queueManager ! activationMessage
    probe.expectMsg(StopSchedulingAsOutdated)
    queueWatcher.expectMsg((finalFqn, finalRevision))
    probe.expectMsg(VersionUpdated)
    probe.expectMsg(activationMessage.copy(action = finalFqn, revision = finalRevision))
  }

  it should "recreate the queue if it's removed by mistake while leader key is not removed from etcd" in {
    val mockEtcdClient = mock[EtcdClient]
    (mockEtcdClient
      .get(_: String))
      .expects(*)
      .returning(Future.successful {
        RangeResponse
          .newBuilder()
          .addKvs(KeyValue.newBuilder().setKey("test").setValue(schedulerEndpoint.serialize).build())
          .build()
      })
      .anyNumberOfTimes()
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val probe = TestProbe()

    val childFactory =
      (_: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) => probe.ref

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    //current queue's revision is `1-test-revision`
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    probe.expectMsg(Start)

    // simulate queue superseded, the queue will be removed but leader key won't be deleted
    queueManager ! QueueRemoved(
      testInvocationNamespace,
      testFQN.toDocId.asDocInfo(testDocRevision),
      Some(testLeaderKey))

    queueManager.!(activationMessage)(queueManager)
    val msg2 = activationMessage.copy(activationId = ActivationId.generate())
    queueManager.!(msg2)(queueManager) // even send two requests, we should only recreate one queue
    probe.expectMsg(Start)
    probe.expectMsg(activationMessage)
    probe.expectMsg(msg2)
  }

  it should "not skip outdated activation when the revision is older than the one in a datastore" in {
    stream.reset()
    val mockEtcdClient = mock[EtcdClient]
    (mockEtcdClient
      .get(_: String))
      .expects(*)
      .returning(
        Future.successful(
          RangeResponse
            .newBuilder()
            .addKvs(KeyValue.newBuilder().setKey("test").setValue(schedulerEndpoint.serialize).build())
            .build()))
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val probe = TestProbe()

    val childFactory =
      (_: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) => probe.ref

    val newRevision = DocRevision("2-test-revision")
    val get = getWhiskActionMetaData(Future(convertToMetaData(action.toWhiskAction.revision(newRevision))))

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    //current queue's revision is `2-test-revision`
    val testQueueCreationMessage =
      CreateQueue(testInvocationNamespace, testFQN, revision = newRevision, testActionMetaData)

    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    //the activationMessage's revision(1-test-revision) is older than current queue's revision(2-test-revision)
    queueManager ! newActivation()

    stream.toString should include(s"it will be replaced with the latest revision and invoked")
  }

  it should "retry to fetch queue data if etcd does not respond" in {
    val mockEtcdClient = stub[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    dataManagementService.ignoreMsg {
      case _: UpdateDataOnChange => true
    }
    val watcher = TestProbe()

    (mockEtcdClient.get _) when (*) returns (Future.failed(new Exception("failed to get for some reason")))

    val queueManager =
      TestActorRef(
        new QueueManager(
          entityStore,
          get,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer,
          QueueManagerConfig(maxRetriesToGetQueue = 2, maxSchedulingTime = 10 seconds)))

    queueManager ! newActivation()
    Thread.sleep(100)
    (mockEtcdClient.get _) verify (*) repeated (3)
  }

  it should "retry to fetch queue data if there is no data in the response" in {
    val mockEtcdClient = stub[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    dataManagementService.ignoreMsg {
      case _: UpdateDataOnChange => true
    }
    val watcher = TestProbe()

    val emptyResult = Future.successful(RangeResponse.newBuilder().build())
    (mockEtcdClient.get _) when (*) returns (emptyResult)

    val queueManager =
      TestActorRef(
        new QueueManager(
          entityStore,
          get,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer,
          QueueManagerConfig(maxRetriesToGetQueue = 2, maxSchedulingTime = 10 seconds)))

    queueManager ! newActivation()
    Thread.sleep(100)
    (mockEtcdClient.get _) verify (*) repeated (3)
  }

  it should "save queue endpoint in memory" in {
    stream.reset()

    val mockEtcdClient = stub[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    dataManagementService.ignoreMsg {
      case _: UpdateDataOnChange => true
    }
    val watcher = TestProbe()

    val emptyResult = Future.successful(RangeResponse.newBuilder().build())
    (mockEtcdClient.get _) when (*) returns (emptyResult)

    val queueManager =
      TestActorRef(
        new QueueManager(
          entityStore,
          get,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer,
          QueueManagerConfig(maxRetriesToGetQueue = 2, maxSchedulingTime = 10 seconds)))

    queueManager ! WatchEndpointInserted("queue", "queue/test-action/leader", schedulerEndpoint.serialize, true)
    stream.toString should include(s"Endpoint inserted, key: queue/test-action/leader, endpoints: ${schedulerEndpoint}")
    stream.reset()

    queueManager ! WatchEndpointInserted("queue", "queue/test-action/leader", "host with wrong format", true)
    stream.toString should include(s"Unexpected error")
    stream.toString should include(s"when put leaderKey: queue/test-action/leader")
    stream.reset()

    queueManager ! WatchEndpointRemoved("queue", "queue/test-action/leader", schedulerEndpoint.serialize, true)
    stream.toString should include(s"Endpoint removed for key: queue/test-action/leader")
  }

  it should "able to query queue status" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()
    val dataManagementService = getTestDataManagementService()
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    (queueManager ? QueueSize).mapTo[Int].futureValue shouldBe 1

    (queueManager ? GetState).mapTo[Future[List[StatusData]]].flatten.futureValue shouldBe List(statusData)
  }

  it should "drop the activation message that has not been scheduled for a long time" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()
    val probe = TestProbe()
    val dataManagementService = getTestDataManagementService()

    val ack = new ActiveAck {
      override def apply(tid: TransactionId,
                         activationResult: WhiskActivation,
                         blockingInvoke: Boolean,
                         controllerInstance: ControllerInstanceId,
                         userId: UUID,
                         acknowledegment: AcknowledegmentMessage): Future[Any] = {
        Future.successful(probe.ref ! acknowledegment.isSystemError)
      }
    }

    val oldNow = Instant.now(Clock.systemUTC()).minusMillis(11000)
    val oldActivationMessage = newActivation(oldNow)

    val queueManager =
      TestActorRef(
        new QueueManager(
          entityStore,
          failedGet,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer,
          QueueManagerConfig(maxRetriesToGetQueue = 2, maxSchedulingTime = 10 seconds)))

    // send old activation message
    queueManager ! oldActivationMessage

    // response should be whisk internal error
    probe.expectMsg(Some(true))

    stream.toString should include(s"[${activationMessage.activationId}] the activation message has not been scheduled")
  }

  it should "not drop the unscheduled activation message that has been processed within the scheduling time limit." in {
    val mockEtcdClient = mock[EtcdClient]
    (mockEtcdClient
      .get(_: String))
      .expects(*)
      .returning(
        Future.successful(
          RangeResponse
            .newBuilder()
            .addKvs(KeyValue.newBuilder().setKey("test").setValue(schedulerEndpoint.serialize).build())
            .build()))
    val watcher = TestProbe()
    val probe = TestProbe()
    val dataManagementService = getTestDataManagementService()

    val ack = new ActiveAck {
      override def apply(tid: TransactionId,
                         activationResult: WhiskActivation,
                         blockingInvoke: Boolean,
                         controllerInstance: ControllerInstanceId,
                         userId: UUID,
                         acknowledegment: AcknowledegmentMessage): Future[Any] = {
        Future.successful(probe.ref ! activationResult.activationId)
      }
    }

    val oldNow = Instant.now(Clock.systemUTC()).minusMillis(9000)
    val oldActivationMessage = newActivation(oldNow)

    val queueManager =
      TestActorRef(
        new QueueManager(
          entityStore,
          failedGet,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer,
          QueueManagerConfig(maxRetriesToGetQueue = 2, maxSchedulingTime = 10 seconds)))

    // send old activation message
    queueManager ! oldActivationMessage

    // ack is no expected
    probe.expectNoMessage(500.milliseconds)
  }

  it should "complete the error activation when the version of action is changed but fetch is failed" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()

    val probe = TestProbe()
    val consumer = TestProbe()
    val dataManagementService = getTestDataManagementService()
    val ack = new ActiveAck {
      override def apply(tid: TransactionId,
                         activationResult: WhiskActivation,
                         blockingInvoke: Boolean,
                         controllerInstance: ControllerInstanceId,
                         userId: UUID,
                         acknowledegment: AcknowledegmentMessage): Future[Any] = {
        Future.successful(probe.ref ! activationResult.activationId)
      }
    }
    val store: (TransactionId, WhiskActivation, UserContext) => Future[Any] =
      (_: TransactionId, activation: WhiskActivation, _: UserContext) =>
        Future.successful(probe.ref ! activation.activationId)

    val newFqn = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction), Some(SemVer(0, 0, 2)))
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            failedGet,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    queueManager.tell(
      UpdateMemoryQueue(testFQN.toDocId.asDocInfo(testDocRevision), newFqn, newActivation()),
      consumer.ref)

    probe.expectMsg(activationMessage.activationId)
    probe.expectMsg(activationMessage.activationId)
  }

  it should "remove the queue and consumer if it receives a QueueRemoved message" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()
    val dataManagementService = getTestDataManagementService()
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    queueManager ! QueueRemoved(
      testInvocationNamespace,
      testFQN.toDocId.asDocInfo(testDocRevision),
      Some(testLeaderKey))

    (queueManager ? QueueSize).mapTo[Int].futureValue shouldBe 0
  }

  it should "put the queue back to pool if it receives a QueueReactive message" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()
    val dataManagementService = getTestDataManagementService()
    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    (queueManager ? QueueSize).mapTo[Int].futureValue shouldBe 1

    queueManager ! QueueRemoved(
      testInvocationNamespace,
      testFQN.toDocId.asDocInfo(testDocRevision),
      Some(testLeaderKey))

    (queueManager ? QueueSize).mapTo[Int].futureValue shouldBe 0

    queueManager ! QueueReactivated(testInvocationNamespace, testFQN, testFQN.toDocId.asDocInfo(testDocRevision))

    (queueManager ? QueueSize).mapTo[Int].futureValue shouldBe 1
  }

  it should "put pool information to data management service" in {
    val mockEtcdClient = mock[EtcdClient]

    val watcher = TestProbe()
    val dataManagementService = TestProbe()
    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)

    dataManagementService.setAutoPilot((sender: ActorRef, msg: Any) =>
      msg match {
        case ElectLeader(key, value, _, _) =>
          sender ! ElectionResult(Right(EtcdLeader(key, value, 10)))
          TestActor.KeepRunning

        case UpdateDataOnChange(_, value) if value == SchedulerStates(schedulerId, 1, schedulerEndpoint).serialize =>
          counter1.getAndIncrement()
          TestActor.KeepRunning

        case UpdateDataOnChange(_, value) if value == SchedulerStates(schedulerId, 2, schedulerEndpoint).serialize =>
          counter2.getAndIncrement()
          TestActor.KeepRunning

        case UpdateDataOnChange(_, value) if value == SchedulerStates(schedulerId, 3, schedulerEndpoint).serialize =>
          counter3.getAndIncrement()
          TestActor.KeepRunning

        case _ =>
          TestActor.KeepRunning
    })

    val fqn2 = FullyQualifiedEntityName(EntityPath("hello1"), EntityName("action1"))
    val fqn3 = FullyQualifiedEntityName(EntityPath("hello2"), EntityName("action2"))

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    Thread.sleep(2000)

    (queueManager ? testQueueCreationMessage.copy(fqn = fqn2))
      .mapTo[CreateQueueResponse]
      .futureValue shouldBe CreateQueueResponse(testInvocationNamespace, fqn = fqn2, success = true)

    Thread.sleep(2000)

    (queueManager ? testQueueCreationMessage.copy(fqn = fqn3))
      .mapTo[CreateQueueResponse]
      .futureValue shouldBe CreateQueueResponse(testInvocationNamespace, fqn = fqn3, success = true)

    Thread.sleep(2000)

    counter1.get() should be > 0
    counter2.get() should be > 0
    counter3.get() should be > 0
  }

  it should "not create a queue if it is a warm-up action" in {
    val mockEtcdClient = mock[EtcdClient]
    val dataManagementService = getTestDataManagementService()
    val watcher = TestProbe()

    val warmUpActionMetaData =
      WhiskActionMetaData(warmUpAction.namespace.toPath, warmUpAction.name, testExecMetadata, version = semVer)

    val warmUpQueueCreationMessage =
      CreateQueue(warmUpAction.namespace.toString, warmUpAction, testDocRevision, warmUpActionMetaData)

    val queueManager =
      TestActorRef(
        QueueManager
          .props(
            entityStore,
            get,
            mockEtcdClient,
            schedulerEndpoint,
            schedulerId,
            dataManagementService.ref,
            watcher.ref,
            ack,
            store,
            childFactory,
            mockConsumer))

    watcher.expectMsg(watchEndpoint)

    (queueManager ? warmUpQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      warmUpAction.namespace.toString,
      warmUpAction,
      true)
  }

  behavior of "zero downtime deployment"

  it should "stop all memory queues and corresponding consumers when it receives graceful shutdown message" in {
    val mockEtcdClient = mock[EtcdClient]
    val watcher = TestProbe()
    val dataManagementService = getTestDataManagementService()
    val probe = TestProbe()
    val fqn2 = FullyQualifiedEntityName(EntityPath("hello1"), EntityName("action1"))
    val fqn3 = FullyQualifiedEntityName(EntityPath("hello2"), EntityName("action2"))
    val fqn4 = FullyQualifiedEntityName(EntityPath("hello3"), EntityName("action3"))
    val fqn5 = FullyQualifiedEntityName(EntityPath("hello4"), EntityName("action4"))
    val fqn6 = FullyQualifiedEntityName(EntityPath("hello5"), EntityName("action5"))

    // probe will watch all actors which are created by these factories
    val childFactory =
      (system: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: WhiskActionMetaData) => {
        system.actorOf(Props(new Actor() {
          override def receive: Receive = {
            case GetActivation(_, _, _, _, _, _) =>
              sender ! ActivationResponse(Right(newActivation()))

            case GracefulShutdown =>
              probe.ref ! GracefulShutdown
          }
        }))
      }

    val queueManager =
      TestActorRef(
        QueueManager.props(
          entityStore,
          get,
          mockEtcdClient,
          schedulerEndpoint,
          schedulerId,
          dataManagementService.ref,
          watcher.ref,
          ack,
          store,
          childFactory,
          mockConsumer))

    watcher.expectMsg(watchEndpoint)
    (queueManager ? testQueueCreationMessage).mapTo[CreateQueueResponse].futureValue shouldBe CreateQueueResponse(
      testInvocationNamespace,
      testFQN,
      true)

    (queueManager ? testQueueCreationMessage.copy(fqn = fqn2))
      .mapTo[CreateQueueResponse]
      .futureValue shouldBe CreateQueueResponse(testInvocationNamespace, fqn = fqn2, success = true)

    (queueManager ? testQueueCreationMessage.copy(fqn = fqn3))
      .mapTo[CreateQueueResponse]
      .futureValue shouldBe CreateQueueResponse(testInvocationNamespace, fqn = fqn3, success = true)

    queueManager ! GracefulShutdown

    probe.expectMsgAllOf(10.seconds, GracefulShutdown, GracefulShutdown, GracefulShutdown)

    // after shutdown, it can still create/update/recover a queue, and new queue should be shutdown immediately too
    (queueManager ? testQueueCreationMessage.copy(fqn = fqn4))
      .mapTo[CreateQueueResponse]
      .futureValue shouldBe CreateQueueResponse(testInvocationNamespace, fqn = fqn4, success = true)
    queueManager ! CreateNewQueue(activationMessage, fqn5, testActionMetaData)
    queueManager ! RecoverQueue(activationMessage, fqn6, testActionMetaData)

    probe.expectMsgAllOf(10.seconds, GracefulShutdown, GracefulShutdown, GracefulShutdown)

  }
}
