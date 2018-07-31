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

package whisk.core.containerpool.test

import java.time.Instant

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, ActorSystem, FSM}
import akka.stream.scaladsl.Source
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.ByteString
import common.{LoggedFunction, StreamLogging}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.common.{Logging, TransactionId}
import whisk.core.connector.ActivationMessage
import whisk.core.containerpool._
import whisk.core.containerpool.logging.LogCollectingException
import whisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.http.Messages

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
  val log = logging

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val memoryLimit = 256.MB

  val invocationNamespace = EntityName("invocationSpace")
  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

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

  val uuid = UUID()

  val message = ActivationMessage(
    messageTransId,
    action.fullyQualifiedName(true),
    action.rev,
    Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret()), Set.empty),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = None)

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
    case NeedWork(PreWarmedData(_, kind, memoryLimit)) => true
  }

  /** Expect a NeedWork message with warmed data */
  def expectWarmed(namespace: String, action: ExecutableWhiskAction) = {
    val test = EntityName(namespace)
    expectMsgPF() {
      case NeedWork(WarmedData(_, `test`, `action`, _)) => true
    }
  }

  /** Expect the container to pause successfully */
  def expectPause(machine: ActorRef) = {
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(Transition(machine, Pausing, Paused))
  }

  /** Creates an inspectable version of the ack method, which records all calls in a buffer */
  def createAcker(a: ExecutableWhiskAction = action) = LoggedFunction {
    (_: TransactionId, activation: WhiskActivation, _: Boolean, _: ControllerInstanceId, _: UUID) =>
      activation.annotations.get("limits") shouldBe Some(a.limits.toJson)
      activation.annotations.get("path") shouldBe Some(a.fullyQualifiedName(false).toString.toJson)
      activation.annotations.get("kind") shouldBe Some(a.exec.kind.toJson)
      Future.successful(())
  }

  /** Creates an inspectable factory */
  def createFactory(response: Future[Container]) = LoggedFunction {
    (_: TransactionId, _: String, _: ImageName, _: Boolean, _: ByteSize, _: Int) =>
      response
  }

  def createCollector(response: Future[ActivationLogs] = Future.successful(ActivationLogs(Vector.empty))) =
    LoggedFunction {
      (transid: TransactionId,
       user: Identity,
       activation: WhiskActivation,
       container: Container,
       action: ExecutableWhiskAction) =>
        response
    }

  def createStore = LoggedFunction { (transid: TransactionId, activation: WhiskActivation) =>
    Future.successful(())
  }

  val poolConfig = ContainerPoolConfig(1, 2, false)

  behavior of "ContainerProxy"

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
            InvokerInstanceId(0, Some("myname")),
            poolConfig,
            pauseGrace = timeout))
    registerCallback(machine)
    preWarm(machine)

    factory.calls should have size 1
    val (tid, name, _, _, memory, cpuShares) = factory.calls(0)
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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

      val initRunActivation = acker.calls(0)._2
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

      val runOnlyActivation = acker.calls(1)._2
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
      acker
        .calls(0)
        ._2
        .annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis
      acker.calls(1)._2.annotations.get(WhiskActivation.initTimeAnnotation) shouldBe empty
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved)

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
                              timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
        initializeCount += 1
        Future.failed(InitializationError(initInterval, ActivationResponse.applicationError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 0 // should not run the action
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      val activation = acker.calls(0)._2
      activation.response shouldBe ActivationResponse.applicationError("boom")
      activation.annotations
        .get(WhiskActivation.initTimeAnnotation)
        .get
        .convertTo[Int] shouldBe initInterval.duration.toMillis

      store.calls should have size 1
    }
  }

  it should "complete the transaction and destroy the container on a failed run" in within(timeout) {
    val container = new TestContainer {
      override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(
        implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        runCount += 1
        Future.successful((initInterval, ActivationResponse.applicationError("boom")))
      }
    }
    val factory = createFactory(Future.successful(container))
    val acker = createAcker()
    val store = createStore
    val collector = createCollector()

    val machine =
      childActorOf(
        ContainerProxy
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
    expectMsg(Transition(machine, Running, Removing))

    awaitAssert {
      factory.calls should have size 1
      container.initializeCount shouldBe 1
      container.runCount shouldBe 1
      collector.calls should have size 1
      container.destroyCount shouldBe 1
      acker.calls(0)._2.response shouldBe ActivationResponse.applicationError("boom")
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    machine ! Run(action, message)
    expectMsg(Transition(machine, Uninitialized, Running))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
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
          .props(factory, acker, store, createCollector(), InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
          .props(factory, acker, store, createCollector(), InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
    registerCallback(machine)
    run(machine, Uninitialized)
    timeout(machine) // times out Ready state so container suspends
    expectMsg(Transition(machine, Ready, Pausing))
    expectMsg(ContainerRemoved) // The message is sent as soon as the container decides to destroy itself
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
                              timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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
    expectMsg(ContainerRemoved)
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
          .props(factory, acker, store, collector, InvokerInstanceId(0), poolConfig, pauseGrace = timeout))
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

  /**
   * Implements all the good cases of a perfect run to facilitate error case overriding.
   */
  class TestContainer extends Container {
    protected val id = ContainerId("testcontainer")
    protected val addr = ContainerAddress("0.0.0.0")
    protected implicit val logging: Logging = log
    protected implicit val ec: ExecutionContext = system.dispatcher
    override implicit protected val as: ActorSystem = system
    var suspendCount = 0
    var resumeCount = 0
    var destroyCount = 0
    var initializeCount = 0
    var runCount = 0
    var logsCount = 0

    def suspend()(implicit transid: TransactionId): Future[Unit] = {
      suspendCount += 1
      Future.successful(())
    }
    def resume()(implicit transid: TransactionId): Future[Unit] = {
      resumeCount += 1
      Future.successful(())
    }
    override def destroy()(implicit transid: TransactionId): Future[Unit] = {
      destroyCount += 1
      super.destroy()
    }
    override def initialize(initializer: JsObject, timeout: FiniteDuration)(
      implicit transid: TransactionId): Future[Interval] = {
      initializeCount += 1
      initializer shouldBe action.containerInitializer
      timeout shouldBe action.limits.timeout.duration
      Future.successful(initInterval)
    }
    override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(
      implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
      runCount += 1
      environment.fields("namespace") shouldBe invocationNamespace.name.toJson
      environment.fields("action_name") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
      environment.fields("activation_id") shouldBe message.activationId.toJson
      val authEnvironment = environment.fields.filterKeys(message.user.authkey.toEnvironment.fields.contains)
      message.user.authkey.toEnvironment shouldBe authEnvironment.toJson.asJsObject
      val deadline = Instant.ofEpochMilli(environment.fields("deadline").convertTo[String].toLong)
      val maxDeadline = Instant.now.plusMillis(timeout.toMillis)

      // The deadline should be in the future but must be smaller than or equal
      // a freshly computed deadline, as they get computed slightly after each other
      deadline should (be <= maxDeadline and be >= Instant.now)

      Future.successful((runInterval, ActivationResponse.success()))
    }
    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = ???
  }
}
