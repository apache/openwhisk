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

package org.apache.openwhisk.core.containerpool.test

import java.net.InetSocketAddress
import java.time.Instant

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, ActorSystem, FSM}
import akka.stream.scaladsl.Source
import akka.testkit.{CallingThreadDispatcher, ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import common.{LoggedFunction, StreamLogging, SynchronizedLoggedFunction, WhiskProperties}
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.io.Tcp.{Close, CommandFailed, Connect, Connected}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ack.ActiveAck
import org.apache.openwhisk.core.connector.{
  AcknowledegmentMessage,
  ActivationMessage,
  CombinedCompletionAndResultMessage,
  CompletionMessage,
  ResultMessage
}
import org.apache.openwhisk.core.containerpool.WarmingData
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity.ActivationResponse.ContainerResponse
import org.apache.openwhisk.core.invoker.Invoker

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

@RunWith(classOf[JUnitRunner])
class ContainerProxyTests
    extends TestKit(ActorSystem("ContainerProxys"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with StreamLogging {

  override def afterAll = TestKit.shutdownActorSystem(system)

  val timeout = 5.seconds
  val pauseGrace = timeout + 1.minute
  val log = logging
  val defaultUserMemory: ByteSize = 1024.MB

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val memoryLimit = 256.MB

  val invocationNamespace = EntityName("invocationSpace")
  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency", "false")).exists(_.toBoolean)
  val testConcurrencyLimit = if (concurrencyEnabled) ConcurrencyLimit(2) else ConcurrencyLimit(1)
  val concurrentAction = ExecutableWhiskAction(
    EntityPath("actionSpace"),
    EntityName("actionName"),
    exec,
    limits = ActionLimits(concurrency = testConcurrencyLimit))

  // create a transaction id to set the start time and control queue time
  val messageTransId = TransactionId(TransactionId.testing.meta.id)

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

  val uuid = UUID()

  val activationArguments = JsObject("ENV_VAR" -> "env".toJson, "param" -> "param".toJson)

  val message = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    action.rev,
    Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = Some(activationArguments),
    initArgs = Set("ENV_VAR"),
    lockedArgs = Map.empty)

  /*
   * Helpers for assertions and actor lifecycles
   */
  /** Imitates a StateTimeout in the FSM */
  def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

  /** Registers the transition callback and expects the first message */
  def registerCallback(c: ActorRef) = {
    c ! SubscribeTransitionCallBack(testActor)
    expectMsg(CurrentState(c, Uninitialized))
  }

  /** Pre-warms the given state-machine, assumes good cases */
  def preWarm(machine: ActorRef) = {
    machine ! Start(exec, memoryLimit)
    expectMsg(Transition(machine, Uninitialized, Starting))
    expectPreWarmed(exec.kind)
    expectMsg(Transition(machine, Starting, Started))
  }

  /** Run the common action on the state-machine, assumes good cases */
  def run(machine: ActorRef, currentState: ContainerState) = {
    machine ! Run(action, message)
    expectMsg(Transition(machine, currentState, Running))
    expectWarmed(invocationNamespace.name, action)
    expectMsg(Transition(machine, Running, Ready))
  }

  /** Expect a NeedWork message with prewarmed data */
  def expectPreWarmed(kind: String) = expectMsgPF() {
    case NeedWork(PreWarmedData(_, kind, memoryLimit, _, _)) => true
  }

  /** Expect a NeedWork message with warmed data */
  def expectWarmed(namespace: String, action: ExecutableWhiskAction) = {
    val test = EntityName(namespace)
    expectMsgPF() {
      case a @ NeedWork(WarmedData(_, `test`, `action`, _, _, _)) => //matched, otherwise will fail
    }
  }

  /** Expect the container to pause successfully */
  def expectPause(machine: ActorRef) = {
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))
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
      (_: TransactionId,
       activation: WhiskActivation,
       _: Boolean,
       _: ControllerInstanceId,
       _: UUID,
       _: AcknowledegmentMessage) =>
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
  def createSyncAcker(a: ExecutableWhiskAction = action) = new LoggedAcker {
    val acker = SynchronizedLoggedFunction {
      (_: TransactionId,
       activation: WhiskActivation,
       _: Boolean,
       _: ControllerInstanceId,
       _: UUID,
       _: AcknowledegmentMessage) =>
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

  /** Creates an inspectable factory */
  def createFactory(response: Future[Container]) = LoggedFunction {
    (_: TransactionId, _: String, _: ImageName, _: Boolean, _: ByteSize, _: Int, _: Option[ExecutableWhiskAction]) =>
      response
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

  def createStore = LoggedFunction {
    (transid: TransactionId, activation: WhiskActivation, isBlockingActivation: Boolean, context: UserContext) =>
      Future.successful(())
  }
  def createSyncStore = SynchronizedLoggedFunction {
    (transid: TransactionId, activation: WhiskActivation, isBlockingActivation: Boolean, context: UserContext) =>
      Future.successful(())
  }
  val poolConfig = ContainerPoolConfig(2.MB, 0.5, false, 2.second, 1.minute, None, 100, 3, false, 1.second)
  def healthchecksConfig(enabled: Boolean = false) = ContainerProxyHealthCheckConfig(enabled, 100.milliseconds, 2)
  val filterEnvVar = (k: String) => Character.isUpperCase(k.charAt(0))

  behavior of "ContainerProxy"

  it should "partition activation arguments into environment variables and main arguments" in {
    ContainerProxy.partitionArguments(None, Set.empty) should be(Map.empty, JsObject.empty)
    ContainerProxy.partitionArguments(Some(JsObject.empty), Set("a")) should be(Map.empty, JsObject.empty)

    val content = JsObject("a" -> "A".toJson, "b" -> "B".toJson, "C" -> "c".toJson, "D" -> "d".toJson)
    val (env, args) = ContainerProxy.partitionArguments(Some(content), Set("C", "D"))
    env should be {
      content.fields.filter(k => filterEnvVar(k._1))
    }

    args should be {
      JsObject(content.fields.filterNot(k => filterEnvVar(k._1)))
    }
  }

  it should "unlock arguments" in {
    val k128 = "ra1V6AfOYAv0jCzEdufIFA=="
    val coder = ParameterEncryption(ParameterStorageConfig("aes-128", aes128 = Some(k128)))
    val locker = Some(coder.encryptor("aes-128"))

    val param = Parameters("a", "abc").lock(locker).merge(Some(JsObject("b" -> JsString("xyz"))))
    param.get.compactPrint should not include "abc"
    ContainerProxy.unlockArguments(param, Map("a" -> "aes-128"), coder) shouldBe Some {
      JsObject("a" -> JsString("abc"), "b" -> JsString("xyz"))
    }
  }

  /*
   * SUCCESSFUL CASES
   */
  it should "create a container given a Start message" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val store = createStore
    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            createAcker(),
            store,
            createCollector(),
            InvokerInstanceId(0, Some("myname"), userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    factory.calls should have size 1
    val (tid, name, _, _, memory, cpuShares, _) = factory.calls(0)
    tid shouldBe TransactionId.invokerWarmup
    name should fullyMatch regex """wskmyname\d+_\d+_prewarm_actionKind"""
    memory shouldBe memoryLimit
  }

  it should "run a container which has been started before, write an active ack, write to the store, pause and remove the container" in within(
    timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)

    preWarm(machine)
    run(machine, Started)

    // Timeout causes the container to pause
    timeout(machine)
    expectPause(machine)

    // Another pause causes the container to be removed
    timeout(machine)
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Paused, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  it should "run an action and continue with a next run without pausing the container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    run(machine, Started)
    // Note that there are no intermediate state changes
    run(machine, Ready)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val (initRunActivation, runOnlyActivation) = {
        // false is sorted before true
        val sorted = acker.calls.sortBy(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isEmpty)
        (sorted.head._2, sorted(1)._2)
      }

      initRunActivation.annotations.get(WhiskActivation.initTimeAnnotation) should not be empty
      initRunActivation.duration shouldBe Some((initInterval.duration + runInterval.duration).toMillis)
      initRunActivation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      initRunActivation.annotations
        .get(WhiskActivation.waitTimeAnnotation)
        .get
        .convertTo[Int] shouldBe
        Interval(message.transid.meta.start, initInterval.start).duration.toMillis

      runOnlyActivation.duration shouldBe Some(runInterval.duration.toMillis)
      runOnlyActivation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      runOnlyActivation.annotations.get(WhiskActivation.waitTimeAnnotation).get.convertTo[Int] shouldBe {
        Interval(message.transid.meta.start, runInterval.start).duration.toMillis
      }
    }
  }

  it should "run an action after pausing the container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    run(machine, Started)
    timeout(machine)
    expectPause(machine)
    run(machine, Paused)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }

  it should "successfully run on an uninitialized container" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      acker.calls should have size 1
      store.calls should have size 1
      acker
        .calls(0)
        ._2
        .annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }

  it should "not collect logs if the log-limit is set to 0" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))

    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(noLogsAction)
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)

    machine ! Run(noLogsAction, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectWarmed(invocationNamespace.name, noLogsAction)
    expectMsg(Transition(machine, Running, Ready))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 0
      acker.calls should have size 1
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[CompletionMessage]
    }
  }

  it should "resend a failed Run when it is first Run after Ready state" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))
    val runPromises = Seq(Promise[(Interval, ActivationResponse)](), Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(runPromises = runPromises)
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(noLogsAction)
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)

    machine ! Run(noLogsAction, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    //run the first successfully
    runPromises(0).success(runInterval, ActivationResponse.success())
    expectWarmed(invocationNamespace.name, noLogsAction)
    expectMsg(Transition(machine, Running, Ready))

    val failingRun = Run(noLogsAction, message)
    val runAfterFail = Run(noLogsAction, message)
    //should fail and retry
    machine ! failingRun
    machine ! runAfterFail //will be buffered first, and then retried
    expectMsg(Transition(machine, Ready, Running))
    //run the second as failure
    runPromises(1).failure(ContainerHealthError(messageTransId, "intentional failure"))
    //on failure, buffered are resent first
    expectMsg(runAfterFail)
    //resend the first run to parent, and start removal process
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(failingRun)
    expectNoMessage(100.milliseconds)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 0
      acker.calls should have size 1
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[CompletionMessage]
    }
  }

  it should "start tcp ping to containers when action healthcheck enabled" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))
    val container = new TestContainer()
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(noLogsAction)
    val store = createStore
    val collector = createCollector()
    val tcpProbe = TestProbe()
    val healthchecks = healthchecksConfig(true)
    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecks,
            pauseGrace = pauseGrace,
            tcp = Some(tcpProbe.ref)))
    registerCallback(machine)
    preWarm(machine)

    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 8080)))
    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 8080)))
    tcpProbe.expectMsg(Connect(new InetSocketAddress("0.0.0.0", 8080)))
    //pings should repeat till the container goes into Running state
    run(machine, Started)
    tcpProbe.expectNoMessage(healthchecks.checkPeriod + 100.milliseconds)

    awaitAssert {
      factory.calls should have size 1
    }
  }

  it should "respond with CombinedCompletionAndResultMessage for blocking invocation with no logs" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))
    val blockingMessage = message.copy(blocking = true)
    val (factory, container, acker, store, collector, machine) = createServices(noLogsAction)

    sendActivationMessage(machine, blockingMessage, noLogsAction)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1

      //For no log case log collector call should be zero
      collector.calls should have size 0

      //There would be only 1 call
      // First with CombinedCompletionAndResultMessage
      acker.calls should have size 1
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[CombinedCompletionAndResultMessage]
    }
  }

  it should "respond with ResultMessage and CompletionMessage for blocking invocation with logs" in within(timeout) {
    val blockingMessage = message.copy(blocking = true)
    val (factory, container, acker, store, collector, machine) = createServices(action)

    sendActivationMessage(machine, blockingMessage, action)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1

      //State related checks
      collector.calls should have size 1

      //There would be 2 calls
      // First with ResultMessage
      // Second with CompletionMessage.
      acker.calls should have size 2
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[ResultMessage]
      acker.calls.last._6 shouldBe a[CompletionMessage]
    }
  }

  it should "respond with only CompletionMessage for non blocking invocation with logs" in within(timeout) {
    val nonBlockingMessage = message.copy(blocking = false)
    val (factory, container, acker, store, collector, machine) = createServices(action)

    sendActivationMessage(machine, nonBlockingMessage, action)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1

      //For log case log collector call should be one
      collector.calls should have size 1

      //There would only be 1 call
      // First with CompletionMessage
      acker.calls should have size 1
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[CompletionMessage]
    }
  }

  it should "respond with only CompletionMessage for non blocking invocation with no logs" in within(timeout) {
    val noLogsAction = action.copy(limits = ActionLimits(logs = LogLimit(0.MB)))
    val nonBlockingMessage = message.copy(blocking = false)
    val (factory, container, acker, store, collector, machine) = createServices(noLogsAction)

    sendActivationMessage(machine, nonBlockingMessage, noLogsAction)

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1

      //For no log case log collector call should be zero
      collector.calls should have size 0

      //There would only be 1 call
      // First with CompletionMessage
      acker.calls should have size 1
      store.calls should have size 1
      acker.calls.head._6 shouldBe a[CompletionMessage]
    }
  }

  private def createServices(action: ExecutableWhiskAction) = {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker(action)
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    (factory, container, acker, store, collector, machine)
  }

  private def sendActivationMessage(machine: ActorRef, message: ActivationMessage, action: ExecutableWhiskAction) = {
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectWarmed(invocationNamespace.name, action)
    expectMsg(Transition(machine, Running, Ready))
  }

  //This tests concurrency from the ContainerPool perspective - where multiple Run messages may be sent to ContainerProxy
  //without waiting for the completion of the previous Run message (signaled by NeedWork message)
  //Multiple messages can only be handled after Warming.
  it should "stay in Running state if others are still running" in within(timeout) {
    assume(concurrencyEnabled)

    val initPromise = Promise[Interval]()
    val runPromises = Seq(
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)](),
      Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(Some(initPromise), runPromises)
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.success(initInterval)

    //complete the first run
    runPromises(0).success(runInterval, ActivationResponse.success())

    //room for 1 more, so expect NeedWork msg
    expectWarmed(invocationNamespace.name, concurrentAction) //when first completes

    //complete the second run
    runPromises(1).success(runInterval, ActivationResponse.success())

    //room for 1 more, so expect NeedWork msg
    expectWarmed(invocationNamespace.name, concurrentAction) //when second completes

    //go back to ready after first and second runs are complete
    expectMsg(Transition(machine, Running, Ready))

    machine ! Run(concurrentAction, message) //third in Ready state
    machine ! Run(concurrentAction, message) //fourth in Ready state
    machine ! Run(concurrentAction, message) //fifth in Ready state - will be queued
    machine ! Run(concurrentAction, message) //sixth in Ready state - will be queued

    //third message will go from Ready -> Running -> Ready (after fourth run)
    expectMsg(Transition(machine, Ready, Running))
    //expect no NeedWork since there are still running and queued messages
    expectNoMessage(500.milliseconds)

    //complete the third run (do not request new work yet)
    runPromises(2).success(runInterval, ActivationResponse.success())
    //expect no NeedWork since there are still queued messages
    expectNoMessage(500.milliseconds)

    //complete the fourth run -> dequeue the fifth run (do not request new work yet)
    runPromises(3).success(runInterval, ActivationResponse.success())

    //complete the fifth run (request new work, 1 active remain)
    runPromises(4).success(runInterval, ActivationResponse.success())

    //request new work since buffer is now empty AND activationCount < concurrent max
    expectWarmed(invocationNamespace.name, concurrentAction) //when fifth completes

    //complete the sixth run (request new work 0 active remain)
    runPromises(5).success(runInterval, ActivationResponse.success())

    // back to ready
    expectWarmed(invocationNamespace.name, concurrentAction) //when sixth completes
    expectMsg(Transition(machine, Running, Ready))

    //timeout + pause after getting back to Ready
    timeout(machine)
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 6
      container.atomicLogsCount.get() shouldBe 6
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 0
      acker.calls should have size 6

      store.calls should have size 6

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }

  }

  it should "not destroy on failure during Removing state when concurrent activations are in flight" in {
    assume(concurrencyEnabled)

    val initPromise = Promise[Interval]()
    val runPromises = Seq(Promise[(Interval, ActivationResponse)](), Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(Some(initPromise), runPromises)
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.success(initInterval)

    //fail the first run
    runPromises(0).success(runInterval, ActivationResponse.whiskError("intentional failure in test"))
    //fail the second run
    runPromises(1).success(runInterval, ActivationResponse.whiskError("intentional failure in test"))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(RescheduleJob)
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      container.atomicLogsCount.get() shouldBe 2
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }

  it should "not destroy on failure during Running state when concurrent activations are in flight" in {
    assume(concurrencyEnabled)

    val initPromise = Promise[Interval]()
    val runPromises = Seq(Promise[(Interval, ActivationResponse)](), Promise[(Interval, ActivationResponse)]())
    val container = new TestContainer(Some(initPromise), runPromises)
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.success(initInterval)

    //fail the first run
    runPromises(0).success(runInterval, ActivationResponse.whiskError("intentional failure in test"))
    //succeed the second run
    runPromises(1).success(runInterval, ActivationResponse.success())
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(RescheduleJob)
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      container.atomicLogsCount.get() shouldBe 2
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val initializedActivations =
        acker.calls.filter(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isDefined)
      initializedActivations should have size 1

      initializedActivations.head._2.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
    }
  }
  it should "terminate buffered concurrent activations when prewarm init fails with an error" in {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val initPromise = Promise[Interval]()
    val container = new TestContainer(Some(initPromise))
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.failure(
      InitializationError(
        initInterval,
        ActivationResponse
          .processInitResponseContent(Right(ContainerResponse(false, "some bad init response...")), logging)))

    expectMsg(ContainerRemoved(true))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0
      container.atomicLogsCount.get() shouldBe 1
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      //we should have 2 activations that are container error
      acker.calls.filter(_._2.response.isContainerError) should have size 2
    }
  }

  it should "terminate buffered concurrent activations when prewarm init fails unexpectedly" in {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val initPromise = Promise[Interval]()
    val container = new TestContainer(Some(initPromise))
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    preWarm(machine) //ends in Started state

    machine ! Run(concurrentAction, message) //first in Started state
    machine ! Run(concurrentAction, message) //second in Started or Running state

    //first message go from Started -> Running -> Ready, with 2 NeedWork messages (1 for init, 1 for run)
    //second message will be delayed until we get to Running state with WarmedData
    //   (and will produce 1 NeedWork message after run)
    expectMsg(Transition(machine, Started, Running))

    //complete the init
    initPromise.failure(new IllegalStateException("intentional failure during init test"))

    expectMsg(ContainerRemoved(true))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0
      container.atomicLogsCount.get() shouldBe 1
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      //we should have 2 activations that are whisk error
      acker.calls.filter(_._2.response.isWhiskError) should have size 2
    }
  }

  it should "terminate buffered concurrent activations when cold init fails with an error" in {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val initPromise = Promise[Interval]()
    val container = new TestContainer(Some(initPromise))
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    //no prewarming

    machine ! Run(concurrentAction, message) //first in Uninitialized state
    machine ! Run(concurrentAction, message) //second in Uninitialized or Running state

    expectMsg(Transition(machine, Uninitialized, Running))

    //complete the init
    initPromise.failure(
      InitializationError(
        initInterval,
        ActivationResponse
          .processInitResponseContent(Right(ContainerResponse(false, "some bad init response...")), logging)))

    expectMsg(ContainerRemoved(true))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0
      container.atomicLogsCount.get() shouldBe 1
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      //we should have 2 activations that are container error
      acker.calls.filter(_._2.response.isContainerError) should have size 2
    }
  }

  it should "terminate buffered concurrent activations when cold init fails unexpectedly" in {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val initPromise = Promise[Interval]()
    val container = new TestContainer(Some(initPromise))
    val factory = createFactory(Future.successful(container))
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => container.logs(0.MB, false)(TransactionId.testing))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    //no prewarming

    machine ! Run(concurrentAction, message) //first in Uninitialized state
    machine ! Run(concurrentAction, message) //second in Uninitialized or Running state

    expectMsg(Transition(machine, Uninitialized, Running))

    //complete the init
    initPromise.failure(new IllegalStateException("intentional failure during init test"))

    expectMsg(ContainerRemoved(true))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0
      container.atomicLogsCount.get() shouldBe 1
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      //we should have 2 activations that are whisk error
      acker.calls.filter(_._2.response.isWhiskError) should have size 2
    }
  }

  it should "terminate buffered concurrent activations when cold init fails to launch container" in {
    assume(Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean))

    val containerPromise = Promise[Container]
    val factory = createFactory(containerPromise.future)
    val acker = createSyncAcker(concurrentAction)
    val store = createSyncStore
    val collector =
      createCollector(Future.successful(ActivationLogs()), () => ())

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace)
          .withDispatcher(CallingThreadDispatcher.Id))
    registerCallback(machine)
    //no prewarming

    machine ! Run(concurrentAction, message) //first in Uninitialized state
    machine ! Run(concurrentAction, message) //second in Uninitialized or Running state

    //wait for buffering before failing the container
    containerPromise.failure(new Exception("simulating a container creation failure"))

    expectMsg(Transition(machine, Uninitialized, Running))

    expectMsg(ContainerRemoved(true))
    //go to Removing state when a failure happens while others are in flight
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      acker.calls should have size 2

      store.calls should have size 2

      //we should have 2 activations that are whisk error
      acker.calls.filter(_._2.response.isWhiskError) should have size 2
    }
  }

  it should "complete the transaction and reuse the container on a failed run IFF failure was applicationError" in within(
    timeout) {
    val container = new TestContainer {
      override def run(
        parameters: JsObject,
        environment: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
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

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = timeout))
    registerCallback(machine)
    preWarm(machine)

    //first one will fail
    run(machine, Started)

    // Note that there are no intermediate state changes
    //second one will succeed
    run(machine, Ready)

    timeout(machine) // times out Ready state so container suspends
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))
    //With exception of the error on first run, the assertions should be the same as in
    //         `run an action and continue with a next run without pausing the container`
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 2
      collector.calls should have size 2
      container.suspendCount shouldBe 1
      container.destroyCount shouldBe 0
      acker.calls should have size 2

      store.calls should have size 2

      // As the active acks are sent asynchronously, it is possible, that the activation with the init time is not the
      // first one in the buffer.
      val (initErrorActivation, runOnlyActivation) = {
        // false is sorted before true
        val sorted = acker.calls.sortBy(_._2.annotations.get(WhiskActivation.initTimeAnnotation).isEmpty)
        (sorted.head._2, sorted(1)._2)
      }

      initErrorActivation.annotations.get(WhiskActivation.initTimeAnnotation) should not be empty
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

      runOnlyActivation.duration shouldBe Some(runInterval.duration.toMillis)
      runOnlyActivation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      runOnlyActivation.annotations.get(WhiskActivation.waitTimeAnnotation).get.convertTo[Int] shouldBe {
        Interval(message.transid.meta.start, runInterval.start).duration.toMillis
      }
    }

  }

  /*
   * ERROR CASES
   */
  it should "complete the transaction and abort if container creation fails" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.failed(new Exception()))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved(true))
    expectMsg(Transition(machine, Running, Removing))
    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls should have size 0 // gather no logs
      container.destroyCount shouldBe 0 // no destroying possible as no container could be obtained
      acker.calls should have size 1
      val activation = acker.calls(0)._2
      activation.response should be a 'whiskError
      activation.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container on a failed init" in within(timeout) {
    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              concurrent: Int,
                              entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        Future.failed(InitializationError(initInterval, ActivationResponse.developerError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved(true)) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0 // should not run the action
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      val activation = acker.calls(0)._2
      activation.response shouldBe ActivationResponse.developerError("boom")
      activation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis

      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container on a failed run IFF failure was containerError" in within(
    timeout) {
    val container = new TestContainer {
      override def run(
        parameters: JsObject,
        environment: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        atomicRunCount.incrementAndGet()
        Future.successful((initInterval, ActivationResponse.developerError(("boom"))))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved(true)) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.developerError("boom")
      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container if log reading failed" in {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val partialLogs = Vector("this log line made it", Messages.logFailure)
    val collector =
      createCollector(Future.failed(LogCollectingException(ActivationLogs(partialLogs))))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved(true)) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls should have size 1
      store.calls(0)._2.logs shouldBe ActivationLogs(partialLogs)
    }
  }

  it should "complete the transaction and destroy the container if log reading failed terminally" in {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector(Future.failed(new Exception))

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved(true)) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      acker.calls(0)._2.response shouldBe ActivationResponse.success()
      store.calls should have size 1
      store.calls(0)._2.logs shouldBe ActivationLogs(Vector(Messages.logFailure))
    }
  }

  it should "resend the job to the parent if resuming a container fails" in within(timeout) {
    val container = new TestContainer {
      override def resume()(implicit transid: TransactionId) = {
        resumeCount += 1
        Future.failed(new RuntimeException())
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized) // first run an activation
    timeout(machine) // times out Ready state so container suspends
    expectPause(machine)

    val runMessage = Run(action, message)
    machine ! runMessage
    expectMsg(Transition(machine, Paused, Running))
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(runMessage)

    awaitAssert {
      factory.calls should have size 1
      container.runCount shouldBe 1
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      container.destroyCount shouldBe 1
    }
  }

  it should "resend the job to the parent if /run fails connection after Paused -> Running" in within(timeout) {
    val container = new TestContainer {
      override def run(
        parameters: JsObject,
        environment: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {

        if (reschedule) {
          throw ContainerHealthError(transid, "reconnect failed to xyz")
        }
        super.run(parameters, environment, timeout, concurrent, reschedule)
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized) // first run an activation
    timeout(machine) // times out Ready state so container suspends
    expectPause(machine)

    val runMessage = Run(action, message)
    machine ! runMessage
    expectMsg(Transition(machine, Paused, Running))
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(runMessage)

    awaitAssert {
      factory.calls should have size 1
      container.runCount shouldBe 1
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      container.destroyCount shouldBe 1
    }
  }

  it should "resend the job to the parent if /run fails connection after Ready -> Running" in within(timeout) {
    val container = new TestContainer {
      override def run(
        parameters: JsObject,
        environment: JsObject,
        timeout: FiniteDuration,
        concurrent: Int,
        reschedule: Boolean = false)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {

        if (reschedule) {
          throw ContainerHealthError(transid, "reconnect failed to xyz")
        }
        super.run(parameters, environment, timeout, concurrent, reschedule)
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized) // first run an activation
    //will be in Ready state now

    val runMessage = Run(action, message)
    machine ! runMessage
    expectMsg(Transition(machine, Ready, Running))
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Running, Removing))
    expectMsg(runMessage)

    awaitAssert {
      factory.calls should have size 1
      container.runCount shouldBe 1
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      container.destroyCount shouldBe 1
    }
  }

  it should "remove and replace a prewarm container if it fails healthcheck after startup" in within(timeout) {
    val container = new TestContainer
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(true),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    preWarm(machine)

    //expect failure after healthchecks fail
    expectMsg(ContainerRemoved(true))
    expectMsg(Transition(machine, Started, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 0
      container.runCount shouldBe 0
      collector.calls should have size 0
      container.suspendCount shouldBe 0
      container.resumeCount shouldBe 0
      acker.calls should have size 0
    }
  }
  it should "remove the container if suspend fails" in within(timeout) {
    val container = new TestContainer {
      override def suspend()(implicit transid: TransactionId) = {
        suspendCount += 1
        Future.failed(new RuntimeException())
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            createCollector(),
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)
    timeout(machine) // times out Ready state so container suspends
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(ContainerRemoved(true)) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Pausing, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.suspendCount shouldBe 1
      container.destroyCount shouldBe 1
    }
  }

  /*
   * DELAYED DELETION CASES
   */
  // this test represents a Remove message whenever you are in the "Running" state. Therefore, testing
  // a Remove while /init should suffice to guarantee test coverage here.
  it should "delay a deletion message until the transaction is completed successfully" in within(timeout) {
    val initPromise = Promise[Interval]
    val container = new TestContainer {
      override def initialize(initializer: JsObject,
                              timeout: FiniteDuration,
                              concurrent: Int,
                              entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        initPromise.future
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)

    // Start running the action
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))

    // Schedule the container to be removed
    machine ! Remove

    // Finish /init, note that /run and log-collecting happens nonetheless
    initPromise.success(Interval.zero)
    expectWarmed(invocationNamespace.name, action)
    expectMsg(Transition(machine, Running, Ready))

    // Remove the container after the transaction finished
    expectMsg(ContainerRemoved(true))
    expectMsg(Transition(machine, Ready, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 0 // skips pausing the container
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  // this tests a Run message in the "Removing" state. The contract between the pool and state-machine
  // is, that only one Run is to be sent until a "NeedWork" comes back. If we sent a NeedWork but no work is
  // there, we might run into the final timeout which will schedule a removal of the container. There is a
  // time window though, in which the pool doesn't know of that decision yet. We handle the collision by
  // sending the Run back to the pool so it can reschedule.
  it should "send back a Run message which got sent before the container decided to remove itself" in within(timeout) {
    val destroyPromise = Promise[Unit]
    val container = new TestContainer {
      override def destroy()(implicit transid: TransactionId): Future[Unit] = {
        destroyCount += 1
        destroyPromise.future
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)
    run(machine, Uninitialized)
    timeout(machine)
    expectPause(machine)
    timeout(machine)

    // We don't know of this timeout, so we schedule a run.
    machine ! Run(action, message)

    // State-machine shuts down nonetheless.
    expectMsg(RescheduleJob)
    expectMsg(Transition(machine, Paused, Removing))

    // Pool gets the message again.
    expectMsg(Run(action, message))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.suspendCount shouldBe 1
      container.resumeCount shouldBe 1
      container.destroyCount shouldBe 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }

  // This tests ensures the user api key is not present in the action context if not requested
  it should "omit api key from action run context" in within(timeout) {
    val container = new TestContainer(apiKeyMustBePresent = false)
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(
            factory,
            acker,
            store,
            collector,
            InvokerInstanceId(0, userMemory = defaultUserMemory),
            poolConfig,
            healthchecksConfig(),
            pauseGrace = pauseGrace))
    registerCallback(machine)

    preWarm(machine)

    val keyFalsyAnnotation = Parameters(Annotations.ProvideApiKeyAnnotationName, JsFalse)
    val actionWithFalsyKeyAnnotation =
      ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec, annotations = keyFalsyAnnotation)

    machine ! Run(actionWithFalsyKeyAnnotation, message)
    expectMsg(Transition(machine, Started, Running))
    expectWarmed(invocationNamespace.name, actionWithFalsyKeyAnnotation)
    expectMsg(Transition(machine, Running, Ready))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      acker.calls should have size 1
      store.calls should have size 1
    }
  }
  it should "reset the lastUse and increment the activationCount on nextRun()" in {
    //NoData/MemoryData/PrewarmedData always reset activation count to 1, and reset lastUse
    val noData = NoData()
    noData.nextRun(Run(action, message)) should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, 1) =>
    }

    val memData = MemoryData(action.limits.memory.megabytes.MB)
    memData.nextRun(Run(action, message)) should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, 1) =>
    }
    val pwData = PreWarmedData(new TestContainer(), action.exec.kind, action.limits.memory.megabytes.MB)
    pwData.nextRun(Run(action, message)) should matchPattern {
      case WarmingData(pwData.container, message.user.namespace.name, action, _, 1) =>
    }

    //WarmingData, WarmingColdData, and WarmedData increment counts and reset lastUse
    val timeDiffSeconds = 20
    val initialCount = 10
    //WarmingData
    val warmingData = WarmingData(
      pwData.container,
      message.user.namespace.name,
      action,
      Instant.now.minusSeconds(timeDiffSeconds),
      initialCount)
    val nextWarmingData = warmingData.nextRun(Run(action, message))
    val nextCount = warmingData.activeActivationCount + 1
    nextWarmingData should matchPattern {
      case WarmingData(pwData.container, message.user.namespace.name, action, _, nextCount) =>
    }
    warmingData.lastUsed.until(nextWarmingData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong

    //WarmingColdData
    val warmingColdData =
      WarmingColdData(message.user.namespace.name, action, Instant.now.minusSeconds(timeDiffSeconds), initialCount)
    val nextWarmingColdData = warmingColdData.nextRun(Run(action, message))
    nextWarmingColdData should matchPattern {
      case WarmingColdData(message.user.namespace.name, action, _, newCount) =>
    }
    warmingColdData.lastUsed.until(nextWarmingColdData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong

    //WarmedData
    val warmedData = WarmedData(
      pwData.container,
      message.user.namespace.name,
      action,
      Instant.now.minusSeconds(timeDiffSeconds),
      initialCount)
    val nextWarmedData = warmedData.nextRun(Run(action, message))
    nextWarmedData should matchPattern {
      case WarmedData(pwData.container, message.user.namespace.name, action, _, newCount, _) =>
    }
    warmedData.lastUsed.until(nextWarmedData.lastUsed, ChronoUnit.SECONDS) should be >= timeDiffSeconds.toLong
  }

  /**
   * Implements all the good cases of a perfect run to facilitate error case overriding.
   */
  class TestContainer(initPromise: Option[Promise[Interval]] = None,
                      runPromises: Seq[Promise[(Interval, ActivationResponse)]] = Seq.empty,
                      apiKeyMustBePresent: Boolean = true)
      extends Container {
    protected[core] val id = ContainerId("testcontainer")
    protected[core] val addr = ContainerAddress("0.0.0.0")
    protected implicit val logging: Logging = log
    protected implicit val ec: ExecutionContext = system.dispatcher
    override implicit protected val as: ActorSystem = system
    var suspendCount = 0
    var resumeCount = 0
    var destroyCount = 0
    var initializeCount = 0
    val atomicRunCount = new AtomicInteger(0) //need atomic tracking since we will test concurrent runs
    var atomicLogsCount = new AtomicInteger(0)

    def runCount = atomicRunCount.get()
    override def suspend()(implicit transid: TransactionId): Future[Unit] = {
      suspendCount += 1
      val s = super.suspend()
      Await.result(s, 5.seconds)
      //verify that httpconn is closed
      httpConnection should be(None)
      s
    }
    override def resume()(implicit transid: TransactionId): Future[Unit] = {
      resumeCount += 1
      val r = super.resume()
      Await.result(r, 5.seconds)
      //verify that httpconn is recreated
      httpConnection should be('defined)
      r
    }
    override def destroy()(implicit transid: TransactionId): Future[Unit] = {
      destroyCount += 1
      super.destroy()
    }
    override def initialize(initializer: JsObject,
                            timeout: FiniteDuration,
                            concurrent: Int,
                            entity: Option[WhiskAction] = None)(implicit transid: TransactionId): Future[Interval] = {
      initializeCount += 1

      val envField = "env"

      (initializer.fields - envField) shouldBe (action.containerInitializer {
        activationArguments.fields.filter(k => filterEnvVar(k._1))
      }.fields - envField)
      timeout shouldBe action.limits.timeout.duration

      val initializeEnv = initializer.fields(envField).asJsObject

      initializeEnv.fields("__OW_NAMESPACE") shouldBe invocationNamespace.name.toJson
      initializeEnv.fields("__OW_ACTION_NAME") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      initializeEnv.fields("__OW_ACTION_VERSION") shouldBe message.action.version.toJson
      initializeEnv.fields("__OW_ACTIVATION_ID") shouldBe message.activationId.toJson
      initializeEnv.fields("__OW_TRANSACTION_ID") shouldBe transid.id.toJson

      val convertedAuthKey = message.user.authkey.toEnvironment.fields.map(f => ("__OW_" + f._1.toUpperCase(), f._2))
      val authEnvironment = initializeEnv.fields.filterKeys(convertedAuthKey.contains).toMap
      if (apiKeyMustBePresent) {
        convertedAuthKey shouldBe authEnvironment
      } else {
        authEnvironment shouldBe empty
      }

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

      // the "init" arguments are not passed on run
      parameters shouldBe JsObject(activationArguments.fields.filter(k => !filterEnvVar(k._1)))

      val runCount = atomicRunCount.incrementAndGet()
      environment.fields("namespace") shouldBe invocationNamespace.name.toJson
      environment.fields("action_name") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      environment.fields("action_version") shouldBe message.action.version.toJson
      environment.fields("activation_id") shouldBe message.activationId.toJson
      environment.fields("transaction_id") shouldBe transid.id.toJson
      val authEnvironment = environment.fields.filterKeys(message.user.authkey.toEnvironment.fields.contains).toMap
      if (apiKeyMustBePresent) {
        message.user.authkey.toEnvironment shouldBe authEnvironment.toJson.asJsObject
      } else {
        authEnvironment shouldBe empty
      }

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
}
@RunWith(classOf[JUnitRunner])
class TCPPingClientTests extends TestKit(ActorSystem("TCPPingClient")) with Matchers with FlatSpecLike {
  val config = ContainerProxyHealthCheckConfig(true, 200.milliseconds, 2)
  val addr = new InetSocketAddress("1.2.3.4", 12345)
  val localAddr = new InetSocketAddress("localhost", 5432)

  behavior of "TCPPingClient"
  it should "start the ping on HealthPingEnabled(true) and stop on HealthPingEnabled(false)" in {
    val tcpProbe = TestProbe()
    val pingClient = system.actorOf(TCPPingClient.props(tcpProbe.ref, "1234", config, addr))
    pingClient ! HealthPingEnabled(true)
    tcpProbe.expectMsg(Connect(addr))
    //measure the delay between connections
    val start = System.currentTimeMillis()
    tcpProbe.expectMsg(Connect(addr))
    val delay = System.currentTimeMillis() - start
    delay should be > config.checkPeriod.toMillis - 25 //allow 25ms slop
    tcpProbe.expectMsg(Connect(addr))
    //make sure disable works
    pingClient ! HealthPingEnabled(false)
    //make sure no Connect msg for at least the check period
    tcpProbe.expectNoMessage(config.checkPeriod)
  }
  it should "send FailureMessage and cancel the ping on CommandFailed" in {
    val tcpProbe = TestProbe()
    val pingClient = system.actorOf(TCPPingClient.props(tcpProbe.ref, "1234", config, addr))
    val clientProbe = TestProbe()
    clientProbe watch pingClient
    pingClient ! HealthPingEnabled(true)
    val c = Connect(addr)
    //send config.maxFails CommandFailed messages
    (1 to config.maxFails).foreach { _ =>
      tcpProbe.expectMsg(c)
      pingClient ! CommandFailed(c)
    }
    //now we expect termination
    clientProbe.expectTerminated(pingClient)
  }
  it should "reset failedCount on Connected" in {
    val tcpProbe = TestProbe()
    val pingClient = system.actorOf(TCPPingClient.props(tcpProbe.ref, "1234", config, addr))
    val clientProbe = TestProbe()
    clientProbe watch pingClient
    pingClient ! HealthPingEnabled(true)
    val c = Connect(addr)
    //send maxFails-1 (should not fail)
    (1 to config.maxFails - 1).foreach { _ =>
      tcpProbe.expectMsg(c)
      pingClient ! CommandFailed(c)
    }
    tcpProbe.expectMsg(c)
    tcpProbe.send(pingClient, Connected(addr, localAddr))
    //counter should be reset
    tcpProbe.expectMsg(Close)
    //send maxFails (will fail, but counter is reset so we get maxFails tries)
    (1 to config.maxFails).foreach { _ =>
      tcpProbe.expectMsg(c)
      pingClient ! CommandFailed(c)
    }
    //now we expect termination
    clientProbe.expectTerminated(pingClient)
  }
}
