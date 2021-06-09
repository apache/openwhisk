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

package org.apache.openwhisk.core.containerpool.v2.test

import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.FSM.{CurrentState, StateTimeout, SubscribeTransitionCallBack, Transition}
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.model
import akka.io.Tcp.Connect
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import com.ibm.etcd.api.{DeleteRangeResponse, KeyValue, PutResponse}
import com.ibm.etcd.client.{EtcdClient => Client}
import common.{LoggedFunction, StreamLogging, SynchronizedLoggedFunction}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, ActivationMessage}
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.containerpool.v2._
import org.apache.openwhisk.core.containerpool.{
  ContainerRemoved,
  Paused => _,
  Pausing => _,
  Removing => _,
  Running => _,
  Start => _,
  Uninitialized => _,
  _
}
import org.apache.openwhisk.core.database.{ArtifactStore, StaleParameter, UserContext}
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.types.AuthStore
import org.apache.openwhisk.core.entity.{ExecutableWhiskAction, _}
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.invoker.{Invoker, NamespaceBlacklist}
import org.apache.openwhisk.core.service.{GetLease, Lease, RegisterData, UnregisterData}
import org.apache.openwhisk.http.Messages
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Assertion, BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, _}

import scala.collection.mutable
import scala.collection.mutable.{Map => MutableMap}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
@RunWith(classOf[JUnitRunner])
class FunctionPullingContainerProxyTests
    extends TestKit(ActorSystem("FunctionPullingContainerProxy"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging {

  override def afterAll: Unit = {
    client.close()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  implicit val ece: ExecutionContextExecutor = system.dispatcher

  val timeout = 20.seconds
  val longTimeout = timeout * 2

  val log = logging

  val defaultUserMemory: ByteSize = 1024.MB

  val invocationNamespace = EntityName("invocationSpace")

  val schedulerHost = "127.17.0.1"

  val rpcPort = 13001

  val neverMatchNamespace = EntityName("neverMatchNamespace")

  val uuid = UUID()

  val poolConfig =
    ContainerPoolConfig(
      2.MB,
      0.5,
      false,
      FiniteDuration(2, TimeUnit.SECONDS),
      FiniteDuration(1, TimeUnit.MINUTES),
      None,
      100,
      3,
      false,
      1.second)

  val timeoutConfig = ContainerProxyTimeoutConfig(5.seconds, 5.seconds)

  val messageTransId = TransactionId(TransactionId.testing.meta.id)

  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)

  val memoryLimit = 256.MB

  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

  val fqn = FullyQualifiedEntityName(action.namespace, action.name, Some(action.version))

  val message = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    action.rev,
    Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret()), Set.empty),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = None)

  val entityStore = WhiskEntityStore.datastore()

  val invokerHealthManager = TestProbe()

  val testContainerId = ContainerId("testcontainerId")

  val testLease = Lease(60, 10)

  def keepAliveService: ActorRef =
    system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case GetLease =>
          Thread.sleep(1000)
          sender() ! testLease
      }
    }))

  def healthchecksConfig(enabled: Boolean = false) = ContainerProxyHealthCheckConfig(enabled, 100.milliseconds, 2)

  val client: Client = {
    val hostAndPorts = "172.17.0.1:2379"
    Client.forEndpoints(hostAndPorts).withPlainText().build()
  }

  class MockEtcdClient() extends EtcdClient(client)(ece) {
    val etcdStore = MutableMap[String, String]()
    var putFlag = false
    override def put(key: String, value: String): Future[PutResponse] = {
      etcdStore.update(key, value)
      putFlag = true
      Future.successful(
        PutResponse.newBuilder().setPrevKv(KeyValue.newBuilder().setKey(key).setValue(value).build()).build())
    }

    override def del(key: String): Future[DeleteRangeResponse] = {
      etcdStore.remove(key)
      val res = DeleteRangeResponse.getDefaultInstance
      Future.successful(
        DeleteRangeResponse.newBuilder().setPrevKvs(0, KeyValue.newBuilder().setKey(key).build()).build())
    }
  }

  val initInterval = {
    val now = messageTransId.meta.start.plusMillis(50) // this is the queue time for cold start
    Interval(now, now.plusMillis(100))
  }

  val runInterval = {
    val now = initInterval.end.plusMillis(75) // delay between init and run
    Interval(now, now.plusMillis(200))
  }

  val errorInterval = {
    val now = initInterval.end.plusMillis(75) // delay between init and run
    Interval(now, now.plusMillis(150))
  }

  /** Creates a client and a factory returning this ref of the client. */
  def testClient
    : (TestProbe,
       (ActorRefFactory, String, FullyQualifiedEntityName, DocRevision, String, Int, ContainerId) => ActorRef) = {
    val client = TestProbe()
    val factory =
      (_: ActorRefFactory, _: String, _: FullyQualifiedEntityName, _: DocRevision, _: String, _: Int, _: ContainerId) =>
        client.ref
    (client, factory)
  }

  /** get WhiskAction*/
  def getWhiskAction(response: Future[WhiskAction]) = LoggedFunction {
    (_: ArtifactStore[WhiskEntity], _: DocId, _: DocRevision, _: Boolean) =>
      response
  }

  /** Creates an inspectable factory */
  def createFactory(response: Future[Container]) = LoggedFunction {
    (_: TransactionId, _: String, _: ImageName, _: Boolean, _: ByteSize, _: Int, _: Option[ExecutableWhiskAction]) =>
      response
  }

  trait LoggedAcker extends ActiveAck {
    def calls =
      mutable.Buffer[(TransactionId, WhiskActivation, Boolean, ControllerInstanceId, UUID, AcknowledegmentMessage)]()

    def verifyAnnotations(activation: WhiskActivation, a: ExecutableWhiskAction) = {
      activation.annotations.get("limits") shouldBe Some(a.limits.toJson)
      activation.annotations.get("path") shouldBe Some(a.fullyQualifiedName(false).toString.toJson)
      activation.annotations.get("kind") shouldBe Some(a.exec.kind.toJson)
    }
  }

  /** Creates an inspectable version of the ack method, which records all calls in a buffer */
  def createAcker(a: ExecutableWhiskAction = action) = new LoggedAcker {
    val acker = LoggedFunction {
      (_: TransactionId, _: WhiskActivation, _: Boolean, _: ControllerInstanceId, _: UUID, _: AcknowledegmentMessage) =>
        Future.successful(())
    }

    override def calls = acker.calls

    override def apply(tid: TransactionId,
                       activation: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      verifyAnnotations(activation, a)
      acker(tid, activation, blockingInvoke, controllerInstance, userId, acknowledegment)
    }
  }

  /** Creates an synchronized inspectable version of the ack method, which records all calls in a buffer */
  def createAckerForNamespaceBlacklist(a: ExecutableWhiskAction = action,
                                       mockNamespaceBlacklist: MockNamespaceBlacklist) = new LoggedAcker {
    val acker = SynchronizedLoggedFunction {
      (_: TransactionId, _: WhiskActivation, _: Boolean, _: ControllerInstanceId, _: UUID, _: AcknowledegmentMessage) =>
        Future.successful(())
    }

    override def calls = acker.calls

    override def verifyAnnotations(activation: WhiskActivation, a: ExecutableWhiskAction): Assertion = {
      activation.annotations.get("path") shouldBe Some(a.fullyQualifiedName(false).toString.toJson)
    }

    override def apply(tid: TransactionId,
                       activation: WhiskActivation,
                       blockingInvoke: Boolean,
                       controllerInstance: ControllerInstanceId,
                       userId: UUID,
                       acknowledegment: AcknowledegmentMessage): Future[Any] = {
      verifyAnnotations(activation, a)
      acker(tid, activation, blockingInvoke, controllerInstance, userId, acknowledegment)
    }
  }

  /** Registers the transition callback and expects the first message */
  def registerCallback(c: ActorRef, probe: TestProbe) = {
    c ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(c, Uninitialized))
  }

  def createStore = LoggedFunction {
    (transid: TransactionId, activation: WhiskActivation, isBlocking: Boolean, context: UserContext) =>
      Future.successful(())
  }

  def getLiveContainerCount(count: Long) = LoggedFunction { (_: String, _: FullyQualifiedEntityName, _: DocRevision) =>
    Future.successful(count)
  }

  def getWarmedContainerLimit(limit: Future[(Int, FiniteDuration)]) = LoggedFunction { (_: String) =>
    limit
  }

  class LoggedCollector(response: Future[ActivationLogs], invokeCallback: () => Unit) extends Invoker.LogsCollector {
    val collector = LoggedFunction {
      (transid: TransactionId,
       user: Identity,
       activation: WhiskActivation,
       container: Container,
       action: ExecutableWhiskAction) =>
        response
    }

    def calls = collector.calls

    override def apply(transid: TransactionId,
                       user: Identity,
                       activation: WhiskActivation,
                       container: Container,
                       action: ExecutableWhiskAction) = {
      invokeCallback()
      collector(transid, user, activation, container, action)
    }
  }

  def createCollector(response: Future[ActivationLogs] = Future.successful(ActivationLogs()),
                      invokeCallback: () => Unit = () => ()) =
    new LoggedCollector(response, invokeCallback)

  /** Expect a NeedWork message with prewarmed data */
  def expectPreWarmed(kind: String, probe: TestProbe) = probe.expectMsgPF() {
    case ReadyToWork(PreWarmData(_, kind, memoryLimit, _)) => true
  }

  /** Expect a Initialized message with prewarmed data */
  def expectInitialized(probe: TestProbe) = probe.expectMsgPF() {
    case Initialized(InitializedData(_, _, _, _)) => true
  }

  /** Pre-warms the given state-machine, assumes good cases */
  def preWarm(machine: ActorRef, probe: TestProbe) = {
    machine ! Start(exec, memoryLimit)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingContainer))
    expectPreWarmed(exec.kind, probe)
    probe.expectMsg(Transition(machine, CreatingContainer, ContainerCreated))
  }

  behavior of "FunctionPullingContainerProxy"

  it should "create a prewarm container" in within(timeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val probe = TestProbe()

    val (_, clientFactory) = testClient

    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            createAcker(),
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, Some("myname"), userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    factory.calls should have size 1
    val (tid, name, _, _, memory, _, _) = factory.calls(0)
    tid shouldBe TransactionId.invokerWarmup
    name should fullyMatch regex """wskmyname\d+_\d+_prewarm_actionKind"""
    memory shouldBe memoryLimit
  }

  it should "run actions to a started prewarm container with get activationMessage successfully" in within(timeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    preWarm(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, transid)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "run actions to a started prewarm container with get no activationMessage" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    preWarm(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, RetryRequestActivation)
    client.expectMsg(RequestActivation())
    client.send(machine, StateTimeout)
    client.send(machine, RetryRequestActivation)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))
    client.expectMsg(StopClientProxy)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "run actions to a cold start container with get activationMessage successfully" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, Some("myname"), userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    val (tid, name, _, _, memory, _, _) = factory.calls(0)
    tid shouldBe TransactionId.invokerColdstart
    name should fullyMatch regex """wskmyname\d+_\d+_actionSpace_actionName"""
    memory shouldBe memoryLimit

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "run actions to a cold start container with get no activationMessage" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, RetryRequestActivation)
    client.expectMsg(RequestActivation())
    client.send(machine, StateTimeout)
    client.send(machine, RetryRequestActivation)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))
    client.expectMsg(StopClientProxy)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "not run activations and destory the prewarm container when get client failed" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))

    def clientFactory(f: ActorRefFactory,
                      invocationNamespace: String,
                      fqn: FullyQualifiedEntityName,
                      d: DocRevision,
                      schedulerHost: String,
                      rpcPort: Int,
                      c: ContainerId): ActorRef = {
      throw new Exception("failed to create activation client")
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    probe.expectMsg(ContainerRemoved(true))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "not run activations and don't create cold start container when get client failed" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))

    def clientFactory(f: ActorRefFactory,
                      invocationNamespace: String,
                      fqn: FullyQualifiedEntityName,
                      d: DocRevision,
                      schedulerHost: String,
                      rpcPort: Int,
                      c: ContainerId): ActorRef = {
      throw new Exception("failed to create activation client")
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    probe.expectMsg(ContainerRemoved(true))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "destroy container proxy when the client closed in CreatingClient" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientClosed)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, CreatingClient, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "destroy container proxy when the client closed in ContainerCreated" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, ClientClosed)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "destroy container proxy when the client closed in Running" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, ClientClosed)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls.length shouldBe 2
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 2
      store.calls.length shouldBe 2
    }
  }

  it should "destroy container proxy when stopping due to timeout" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(2)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    machine ! StateTimeout
    client.expectMsg(StopClientProxy)
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Paused, Removing))
    client.send(machine, ClientClosed)

    probe expectTerminated machine

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "destroy container proxy even if there is no message from the client when stopping due to timeout" in within(
    timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(2)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    client.send(machine, StateTimeout)
    client.expectMsg(StopClientProxy)
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Paused, Removing))

    probe expectTerminated (machine, timeout)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "destroy container proxy even Even if there is 1 container, if timeout in keep" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    client.send(machine, StateTimeout)
    client.send(machine, v2.Remove)
    client.expectMsg(StopClientProxy)
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Paused, Removing))

    probe expectTerminated (machine, timeout)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "Keep the container if live count is less than warmed container keeping count configuration" in within(
    timeout) {
    stream.reset()
    val warmedContainerTimeout = 10.seconds
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((2, warmedContainerTimeout)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    machine ! StateTimeout
    probe.expectNoMessage(warmedContainerTimeout)
    probe.expectMsgAllOf(warmedContainerTimeout, ContainerRemoved(true), Transition(machine, Paused, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "Remove the container if live count is greater than warmed container keeping count configuration" in within(
    timeout) {
    stream.reset()
    val warmedContainerTimeout = 10.seconds
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(2)
    val limit = getWarmedContainerLimit(Future.successful((1, warmedContainerTimeout)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    machine ! StateTimeout
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Paused, Removing))
    probe expectTerminated (machine, timeout)
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }
  }

  it should "pause itself when timeout and recover when got a new Initialize" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient
    val instanceId = InvokerInstanceId(0, userMemory = defaultUserMemory)

    val pool = TestProbe()
    val probe = TestProbe()
    val machine =
      pool.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            instanceId,
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    probe watch machine

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())
    dataManagementService.expectMsg(
      RegisterData(
        ContainerKeys.existingContainers(
          invocationNamespace.asString,
          fqn,
          DocRevision.empty,
          Some(instanceId),
          Some(testContainerId)),
        ""))

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(pool)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    machine ! StateTimeout
    client.send(machine, RetryRequestActivation)
    probe.expectMsg(Transition(machine, Running, Pausing))
    pool.expectMsgType[ContainerIsPaused]
    dataManagementService.expectMsgAllOf(
      RegisterData(
        ContainerKeys
          .warmedContainers(invocationNamespace.asString, fqn, DocRevision.empty, instanceId, testContainerId),
        ""),
      UnregisterData(
        ContainerKeys.existingContainers(
          invocationNamespace.asString,
          fqn,
          DocRevision.empty,
          Some(instanceId),
          Some(testContainerId))))
    probe.expectMsg(Transition(machine, Pausing, Paused))

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    dataManagementService.expectMsgAllOf(
      UnregisterData(
        ContainerKeys
          .warmedContainers(invocationNamespace.asString, fqn, DocRevision.empty, instanceId, testContainerId)),
      RegisterData(
        ContainerKeys.existingContainers(
          invocationNamespace.asString,
          fqn,
          DocRevision.empty,
          Some(instanceId),
          Some(testContainerId)),
        ""))

    probe.expectMsg(Transition(machine, Paused, Running))
    pool.expectMsgType[Resumed]

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
    }

  }

  it should "not collect logs if the log-limit is set to 0" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(noLogsAction.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(noLogsAction)
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, noLogsAction, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount should be > 1
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 0
      acker.calls.length should be > 1
      store.calls.length should be > 1
    }
  }

  it should "complete the transaction and abort if container creation fails" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val exception = new Exception()
    val container = new TestContainer
    val factory = createFactory(Future.failed(exception))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (_, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsgAllOf(
      Transition(machine, Uninitialized, CreatingClient),
      ContainerCreationFailed(exception),
      ContainerRemoved(true))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 0
      store.calls.length shouldBe 0
    }
  }

  it should "complete the transaction and destroy the prewarm container on a failed init" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              maxConcurrent: Int,
                              entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        Future.failed(InitializationError(initInterval, ActivationResponse.developerError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0 // should not run the action
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      val activation = acker.calls(0)._2
      activation.response shouldBe ActivationResponse.developerError("boom")
      activation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      store.calls.length shouldBe 1
    }
  }

  it should "complete the transaction and destroy the cold start container on a failed init" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              maxConcurrent: Int,
                              entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        Future.failed(InitializationError(initInterval, ActivationResponse.developerError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0 // should not run the action
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      val activation = acker.calls(0)._2
      activation.response shouldBe ActivationResponse.developerError("boom")
      activation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      store.calls.length shouldBe 1
    }
  }

  it should "complete the transaction and destroy the container on a failed run IFF failure was containerError" in within(
    timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer {
      override def run(parameters: JsObject,
                       environment: JsObject,
                       timeout: FiniteDuration,
                       concurrent: Int,
                       reschedule: Boolean)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        atomicRunCount.incrementAndGet()
        Future.successful((initInterval, ActivationResponse.developerError(("boom"))))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.developerError("boom")
      store.calls.length shouldBe 1
    }
  }

  it should "complete the transaction and reuse the container on a failed run IFF failure was applicationError" in within(
    timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer {
      override def run(parameters: JsObject,
                       environment: JsObject,
                       timeout: FiniteDuration,
                       concurrent: Int,
                       reschedule: Boolean)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        atomicRunCount.incrementAndGet()
        //every other run fails
        if (runCount % 2 == 0) {
          Future.successful((runInterval, ActivationResponse.success()))
        } else {
          Future.successful((errorInterval, ActivationResponse.applicationError(("boom"))))
        }
      }
    }

    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount should be > 1
      collector.calls.length should be > 1
      container.destroyCount shouldBe 0
      acker.calls.length should be > 1
      store.calls.length should be > 1

      val initErrorActivation = acker.calls(0)._2
      initErrorActivation.duration shouldBe Some((initInterval.duration + errorInterval.duration).toMillis)
      initErrorActivation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      initErrorActivation.annotations
        .get(WhiskActivation.waitTimeAnnotation)
        .get
        .convertTo[Int] shouldBe
        Interval(message.transid.meta.start, initInterval.start).duration.toMillis

      val runOnlyActivation = acker.calls(1)._2
      runOnlyActivation.duration shouldBe Some(runInterval.duration.toMillis)
      runOnlyActivation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      runOnlyActivation.annotations.get(WhiskActivation.waitTimeAnnotation).get.convertTo[Int] shouldBe {
        Interval(message.transid.meta.start, runInterval.start).duration.toMillis
      }
    }
  }

  it should "complete the transaction and destroy the container if log reading failed" in {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val partialLogs = Vector("this log line made it", Messages.logFailure)
    val collector = createCollector(Future.failed(LogCollectingException(ActivationLogs(partialLogs))))
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls.length shouldBe 1
      store.calls(0)._2.logs shouldBe ActivationLogs(partialLogs)
    }
  }

  it should "complete the transaction and destroy the container if log reading failed terminally" in {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector(Future.failed(new Exception))
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls.length shouldBe 1
      store.calls(0)._2.logs shouldBe ActivationLogs(Vector(Messages.logFailure))
    }
  }

  it should "save the container id to etcd when don't destroy the container" in within(timeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val instance = InvokerInstanceId(0, userMemory = defaultUserMemory)
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            instance,
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
      dataManagementService.expectMsg(RegisterData(
        s"${ContainerKeys.existingContainers(invocationNamespace.asString, fqn, DocRevision.empty, Some(instance), Some(testContainerId))}",
        ""))
    }
  }

  it should "save the container id to etcd first and delete it when destroy the container" in within(timeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val instance = InvokerInstanceId(0, userMemory = defaultUserMemory)
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(2)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            instance,
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    dataManagementService.expectMsg(RegisterData(
      s"${ContainerKeys.existingContainers(invocationNamespace.asString, fqn, DocRevision.empty, Some(instance), Some(testContainerId))}",
      ""))

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, StateTimeout)
    client.send(machine, RetryRequestActivation)

    probe.expectMsg(Transition(machine, Running, Pausing))
    probe.expectMsgType[ContainerIsPaused]
    probe.expectMsg(Transition(machine, Pausing, Paused))

    client.send(machine, StateTimeout)
    client.expectMsg(StopClientProxy)
    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Paused, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls.length shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 1
      dataManagementService.expectMsg(UnregisterData(
        s"${ContainerKeys.existingContainers(invocationNamespace.asString, fqn, DocRevision.empty, Some(instance), Some(testContainerId))}"))
    }
  }

  it should "not destroy itself when time out happens but a new activation message comes" in within(timeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val instance = InvokerInstanceId(0, userMemory = defaultUserMemory)
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            instance,
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, Uninitialized, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    dataManagementService.expectMsg(RegisterData(
      s"${ContainerKeys.existingContainers(invocationNamespace.asString, fqn, DocRevision.empty, Some(instance), Some(testContainerId))}",
      ""))

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, StateTimeout) // make container time out
    client.send(machine, message)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls.length shouldBe 2
      container.destroyCount shouldBe 0
      acker.calls.length shouldBe 2
      store.calls.length shouldBe 2
      dataManagementService.expectNoMessage()
    }
  }

  it should "get the latest NamespaceBlacklist when NamespaceBlacklist is updated in db" in within(timeout) {
    stream.reset()
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val mockNamespaceBlacklist: MockNamespaceBlacklist = new MockNamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAckerForNamespaceBlacklist(mockNamespaceBlacklist = mockNamespaceBlacklist)
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            mockNamespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    mockNamespaceBlacklist.refreshBlacklist()
    //the namespace:invocationSpace will be added to namespaceBlackboxlist
    mockNamespaceBlacklist.refreshBlacklist()
    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, ClientCreated, Removing))

    stream.toString should include(s"namespace invocationSpace was blocked in containerProxy")
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls.length shouldBe 0
      container.destroyCount shouldBe 1
      acker.calls.length shouldBe 1
      store.calls.length shouldBe 0
    }
  }

  it should "block further invocations after invocation space is added in the namespace blacklist" in within(timeout) {
    stream.reset()
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val mockNamespaceBlacklist: MockNamespaceBlacklist = new MockNamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAckerForNamespaceBlacklist(mockNamespaceBlacklist = mockNamespaceBlacklist)
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            mockNamespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))

    registerCallback(machine, probe)
    preWarm(machine, probe)

    //first refresh, the namespace:invocationSpace is not in namespaceBlacklist, so activations are executed successfully
    mockNamespaceBlacklist.refreshBlacklist()
    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    stream.toString should include(s"namespace ${message.user.namespace.name} is not in the namespaceBlacklist")

    //refresh again, the namespace:invocationSpace will be added to namespaceBlacklist
    stream.reset()
    Thread.sleep(1000)
    mockNamespaceBlacklist.refreshBlacklist()
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)

    probe.expectMsgAllOf(ContainerRemoved(true), Transition(machine, Running, Removing))
    client.expectMsg(StopClientProxy)
    stream.toString should include(s"namespace ${message.user.namespace.name} was blocked in containerProxy")

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount should be >= 1
      collector.calls.length should be >= 1
      container.destroyCount shouldBe 1
      acker.calls.length should be >= 1
      store.calls.length should be >= 1
    }
  }

  it should "not timeout when running long time action" in within(longTimeout) {
    implicit val transid: TransactionId = messageTransId
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val container = new TestContainer {
      override def run(parameters: JsObject,
                       environment: JsObject,
                       timeout: FiniteDuration,
                       concurrent: Int,
                       reschedule: Boolean)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        Thread.sleep((timeoutConfig.pauseGrace + 1.second).toMillis) // 6 sec actions
        super.run(parameters, environment, timeout, concurrent)
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)
    client.expectMsgPF(8.seconds) { // wait more than container running time(6 seconds)
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)
    client.expectMsgPF(8.seconds) { // wait more than container running time(6 seconds)
      case RequestActivation(Some(_), None) => true
    }

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount should be > 1
      collector.calls.length should be > 1
      container.destroyCount shouldBe 0
      acker.calls.length should be > 1
      store.calls.length should be > 1
    }
  }

  it should "start tcp ping to containers when action healthcheck enabled" in within(timeout) {
    implicit val transid: TransactionId = messageTransId

    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))
    val healthchecks = healthchecksConfig(true)

    val probe = TestProbe()
    val tcpProbe = TestProbe()

    val (client, clientFactory) = testClient

    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            createAcker(),
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, Some("myname"), userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig,
            healthchecks,
            tcp = Some(tcpProbe.ref)))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 12345)))
    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 12345)))
    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 12345)))

    //pings should repeat till the container goes into Running state
    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, transid)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    // Receive any unhandled messages
    tcpProbe.receiveWhile(3.seconds, 200.milliseconds, 10) {
      case Connect(_, None, Nil, None, false) =>
        true
    }

    tcpProbe.expectNoMessage(healthchecks.checkPeriod + 100.milliseconds)

    awaitAssert {
      factory.calls should have size 1
    }
  }

  it should "reschedule the job to the queue if /init fails connection on ClientCreated" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))

    // test container
    val initPromise = Promise[Interval]()
    val container = new TestContainer(initPromise = Some(initPromise))
    val factory = createFactory(Future.successful(container))

    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    // throw health error
    initPromise.failure(ContainerHealthError(messageTransId, "intentional failure"))

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    // message should be rescheduled
    val fqn = action.fullyQualifiedName(withVersion = true)
    val rev = action.rev
    client.expectMsg(RescheduleActivation(invocationNamespace.asString, fqn, rev, message))
  }

  it should "reschedule the job to the queue if /run fails connection on ClientCreated" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))

    // test container
    val runPromises = Seq(Promise[(Interval, ActivationResponse)](), Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(runPromises = runPromises)
    val factory = createFactory(Future.successful(container))

    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    // throw health error
    runPromises.head.failure(ContainerHealthError(messageTransId, "intentional failure"))

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    // message should be rescheduled
    val fqn = action.fullyQualifiedName(withVersion = true)
    val rev = action.rev
    client.expectMsg(ContainerWarmed)
    client.expectMsg(RescheduleActivation(invocationNamespace.asString, fqn, rev, message))
  }

  it should "reschedule the job to the queue if /run fails connection on Running" in within(timeout) {
    val authStore = mock[ArtifactWhiskAuthStore]
    val namespaceBlacklist: NamespaceBlacklist = new NamespaceBlacklist(authStore)
    val get = getWhiskAction(Future(action.toWhiskAction))
    val dataManagementService = TestProbe()

    val acker = createAcker()
    val store = createStore
    val collector = createCollector()
    val counter = getLiveContainerCount(1)
    val limit = getWarmedContainerLimit(Future.successful((1, 10.seconds)))

    // test container
    val runPromises = Seq(Promise[(Interval, ActivationResponse)](), Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(runPromises = runPromises)
    val factory = createFactory(Future.successful(container))

    val (client, clientFactory) = testClient

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        FunctionPullingContainerProxy
          .props(
            factory,
            entityStore,
            namespaceBlacklist,
            get,
            dataManagementService.ref,
            clientFactory,
            acker,
            store,
            collector,
            counter,
            limit,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            invokerHealthManager.ref,
            poolConfig,
            timeoutConfig))
    registerCallback(machine, probe)
    preWarm(machine, probe)

    // pass first request to become Running state
    runPromises(0).success(runInterval, ActivationResponse.success())

    // throw health error
    runPromises(1).failure(ContainerHealthError(messageTransId, "intentional failure"))

    machine ! Initialize(invocationNamespace.asString, action, schedulerHost, rpcPort, messageTransId)
    probe.expectMsg(Transition(machine, ContainerCreated, CreatingClient))
    client.expectMsg(StartClient)
    client.send(machine, ClientCreationCompleted())

    probe.expectMsg(Transition(machine, CreatingClient, ClientCreated))
    expectInitialized(probe)
    client.expectMsg(RequestActivation())
    client.send(machine, message)

    probe.expectMsg(Transition(machine, ClientCreated, Running))
    client.expectMsg(ContainerWarmed)

    client.expectMsgPF() {
      case RequestActivation(Some(_), None) => true
    }
    client.send(machine, message)

    // message should be rescheduled
    val fqn = action.fullyQualifiedName(withVersion = true)
    val rev = action.rev
    client.expectMsg(RescheduleActivation(invocationNamespace.asString, fqn, rev, message))
  }

  /**
   * Implements all the good cases of a perfect run to facilitate error case overriding.
   */
  class TestContainer(initPromise: Option[Promise[Interval]] = None,
                      runPromises: Seq[Promise[(Interval, ActivationResponse)]] = Seq.empty,
                      apiKeyMustBePresent: Boolean = true)
      extends Container {
    protected val id = testContainerId
    protected[core] val addr = ContainerAddress("0.0.0.0", 12345)
    protected implicit val logging: Logging = log
    protected implicit val ec: ExecutionContext = system.dispatcher
    override implicit protected val as: ActorSystem = system
    var destroyCount = 0
    var initializeCount = 0
    val atomicRunCount = new AtomicInteger(0) //need atomic tracking since we will test concurrent runs
    var atomicLogsCount = new AtomicInteger(0)

    def runCount = atomicRunCount.get()

    override def destroy()(implicit transid: TransactionId): Future[Unit] = {
      destroyCount += 1
      super.destroy()
    }
    override def initialize(initializer: JsObject,
                            timeout: FiniteDuration,
                            maxConcurrent: Int,
                            entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
      initializeCount += 1
      val envField = "env"

      (initializer.fields - envField) shouldBe action.containerInitializer().fields - envField

      timeout shouldBe action.limits.timeout.duration

      val initializeEnv = initializer.fields(envField).asJsObject

      initializeEnv.fields("__OW_NAMESPACE") shouldBe invocationNamespace.name.toJson
      initializeEnv.fields("__OW_ACTION_NAME") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      initializeEnv.fields("__OW_ACTION_VERSION") shouldBe message.action.version.toJson
      initializeEnv.fields("__OW_ACTIVATION_ID") shouldBe message.activationId.toJson
      initializeEnv.fields("__OW_TRANSACTION_ID") shouldBe transid.id.toJson

      val convertedAuthKey = message.user.authkey.toEnvironment.fields.map(f => ("__OW_" + f._1.toUpperCase(), f._2))
      val authEnvironment = initializeEnv.fields.filterKeys(convertedAuthKey.contains)
      convertedAuthKey shouldBe authEnvironment

      val deadline = Instant.ofEpochMilli(initializeEnv.fields("__OW_DEADLINE").convertTo[String].toLong)
      val maxDeadline = Instant.now.plusMillis(timeout.toMillis)

      // The deadline should be in the future but must be smaller than or equal
      // a freshly computed deadline, as they get computed slightly after each other
      deadline should (be <= maxDeadline and be >= Instant.now)

      initPromise.map(_.future).getOrElse(Future.successful(initInterval))
    }

    override def run(
      parameters: JsObject,
      environment: JsObject,
      timeout: FiniteDuration,
      concurrent: Int,
      reschedule: Boolean = false)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
      val runCount = atomicRunCount.incrementAndGet()
      environment.fields("namespace") shouldBe invocationNamespace.name.toJson
      environment.fields("action_name") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      environment.fields("action_version") shouldBe message.action.version.toJson
      environment.fields("activation_id") shouldBe message.activationId.toJson
      environment.fields("transaction_id") shouldBe transid.id.toJson
      val authEnvironment = environment.fields.filterKeys(message.user.authkey.toEnvironment.fields.contains).toMap
      message.user.authkey.toEnvironment shouldBe authEnvironment.toJson.asJsObject
      val deadline = Instant.ofEpochMilli(environment.fields("deadline").convertTo[String].toLong)
      val maxDeadline = Instant.now.plusMillis(timeout.toMillis)

      // The deadline should be in the future but must be smaller than or equal
      // a freshly computed deadline, as they get computed slightly after each other
      deadline should (be <= maxDeadline and be >= Instant.now)

      //return the future for this run (if runPromises no empty), or a default response
      runPromises
        .lift(runCount - 1)
        .map(_.future)
        .getOrElse(Future.successful((runInterval, ActivationResponse.success())))
    }

    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
      atomicLogsCount.incrementAndGet()
      Source.empty
    }
  }

  abstract class ArtifactWhiskAuthStore extends ArtifactStore[WhiskAuth] {
    override protected[core] implicit val executionContext: ExecutionContext = ece
    override implicit val logging: Logging = log

    override protected[core] def put(d: WhiskAuth)(implicit transid: TransactionId): Future[DocInfo] = ???

    override protected[core] def del(doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] = ???

    override protected[core] def get[A <: WhiskAuth](doc: DocInfo,
                                                     attachmentHandler: Option[(A, Attachments.Attached) => A])(
      implicit transid: TransactionId,
      ma: Manifest[A]): Future[A] = ???

    override protected[core] def query(table: String,
                                       startKey: List[Any],
                                       endKey: List[Any],
                                       skip: Int,
                                       limit: Int,
                                       includeDocs: Boolean,
                                       descending: Boolean,
                                       reduce: Boolean,
                                       stale: StaleParameter)(implicit transid: TransactionId): Future[List[JsObject]] =
      ???

    override protected[core] def count(table: String,
                                       startKey: List[Any],
                                       endKey: List[Any],
                                       skip: Int,
                                       stale: StaleParameter)(implicit transid: TransactionId): Future[Long] = ???

    override protected[core] def putAndAttach[A <: WhiskAuth](d: A,
                                                              update: (A, Attachments.Attached) => A,
                                                              contentType: model.ContentType,
                                                              docStream: Source[ByteString, _],
                                                              oldAttachment: Option[Attachments.Attached])(
      implicit transid: TransactionId): Future[(DocInfo, Attachments.Attached)] = ???

    override protected[core] def readAttachment[T](
      doc: DocInfo,
      attached: Attachments.Attached,
      sink: Sink[ByteString, Future[T]])(implicit transid: TransactionId): Future[T] = ???

    override protected[core] def deleteAttachments[T](doc: DocInfo)(implicit transid: TransactionId): Future[Boolean] =
      ???

    override def shutdown(): Unit = ???
  }

  class MockNamespaceBlacklist(authStore: AuthStore) extends NamespaceBlacklist(authStore) {

    var count = 0
    var blacklist: Set[String] = Set.empty

    override def isBlacklisted(identity: Identity): Boolean = {
      blacklist.contains(identity.namespace.name.asString)
    }

    override def refreshBlacklist()(implicit ec: ExecutionContext, tid: TransactionId): Future[Set[String]] = {
      count += 1
      if (count == 1) {
        //neverMatchNamespace is in the namespaceBlacklist
        blacklist = Set(neverMatchNamespace.name)
        Future.successful(blacklist)
      } else {
        //invocationNamespace is not in the namespaceBlacklist
        blacklist = Set(invocationNamespace.name)
        Future.successful(blacklist)
      }
    }
  }
}
