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

import akka.actor.ActorRef
import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.testkit.TestProbe
import common.StreamLogging
import common.WhiskProperties
import java.util.concurrent.atomic.AtomicInteger
import org.apache.openwhisk.common.PrintStreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.ExecManifest.RuntimeManifest
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.connector.MessageFeed
import org.apache.openwhisk.core.containerpool.ContainerData

/**
 * Behavior tests for the ContainerPool
 *
 * These tests test the runtime behavior of a ContainerPool actor.
 */
@RunWith(classOf[JUnitRunner])
class ContainerPoolTests
    extends TestKit(ActorSystem("ContainerPool"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory
    with StreamLogging {

  override def afterAll = TestKit.shutdownActorSystem(system)

  val timeout = 5.seconds

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val memoryLimit = 256.MB

  /** Creates a `Run` message */
  def createRunMessage(action: ExecutableWhiskAction, invocationNamespace: EntityName) = {
    val uuid = UUID()
    val message = ActivationMessage(
      TransactionId.testing,
      action.fullyQualifiedName(true),
      action.rev,
      Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret()), Set.empty),
      ActivationId.generate(),
      ControllerInstanceId("0"),
      blocking = false,
      content = None)
    Run(action, message)
  }

  val invocationNamespace = EntityName("invocationSpace")
  val differentInvocationNamespace = EntityName("invocationSpace2")
  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)
  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
  val concurrentAction = ExecutableWhiskAction(
    EntityPath("actionSpace"),
    EntityName("actionName"),
    exec,
    limits = ActionLimits(concurrency = ConcurrencyLimit(if (concurrencyEnabled) 3 else 1)))
  val differentAction = action.copy(name = EntityName("actionName2"))
  val largeAction =
    action.copy(
      name = EntityName("largeAction"),
      limits = ActionLimits(memory = MemoryLimit(MemoryLimit.stdMemory * 2)))

  val runMessage = createRunMessage(action, invocationNamespace)
  val runMessageLarge = createRunMessage(largeAction, invocationNamespace)
  val runMessageDifferentAction = createRunMessage(differentAction, invocationNamespace)
  val runMessageDifferentVersion = createRunMessage(action.copy().revision(DocRevision("v2")), invocationNamespace)
  val runMessageDifferentNamespace = createRunMessage(action, differentInvocationNamespace)
  val runMessageDifferentEverything = createRunMessage(differentAction, differentInvocationNamespace)
  val runMessageConcurrent = createRunMessage(concurrentAction, invocationNamespace)
  val runMessageConcurrentDifferentNamespace = createRunMessage(concurrentAction, differentInvocationNamespace)

  /** Helper to create PreWarmedData */
  def preWarmedData(kind: String, memoryLimit: ByteSize = memoryLimit) =
    PreWarmedData(stub[MockableContainer], kind, memoryLimit)

  /** Helper to create WarmedData */
  def warmedData(action: ExecutableWhiskAction = action,
                 namespace: String = "invocationSpace",
                 lastUsed: Instant = Instant.now) =
    WarmedData(stub[MockableContainer], EntityName(namespace), action, lastUsed)

  /** Creates a sequence of containers and a factory returning this sequence. */
  def testContainers(n: Int) = {
    val containers = (0 to n).map(_ => TestProbe())
    val queue = mutable.Queue(containers: _*)
    val factory = (fac: ActorRefFactory) => queue.dequeue().ref
    (containers, factory)
  }

  def poolConfig(userMemory: ByteSize, clusterMangedResources: Boolean = false, idleGrace: FiniteDuration = 0.seconds) =
    ContainerPoolConfig(userMemory, 0.5, false, clusterMangedResources, false, 10, idleGrace)

  val instanceId = InvokerInstanceId(0, userMemory = 1024.MB)
  behavior of "ContainerPool"
  val resMgrFactory = (pool: ActorRef) => new LocalContainerResourceManager(pool)
  /*
   * CONTAINER SCHEDULING
   *
   * These tests only test the simplest approaches. Look below for full coverage tests
   * of the respective scheduling methods.
   */
  it should "reuse a warm container" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(1).expectNoMessage(100.milliseconds)
  }

  it should "reuse a warm container when action is the same even if revision changes" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))

    pool ! runMessageDifferentVersion
    containers(0).expectMsg(runMessageDifferentVersion)
    containers(1).expectNoMessage(100.milliseconds)
  }

  it should "create a container if it cannot find a matching container" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    // Note that the container doesn't respond, thus it's not free to take work
    pool ! runMessage
    containers(1).expectMsg(runMessage)
  }

  it should "remove a container to make space in the pool if it is already full and a different action arrives" in within(
    timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // a pool with only 1 slot
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory), feed.ref, resMgrFactory))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))
    feed.expectMsg(MessageFeed.Processed)
    pool ! runMessageDifferentEverything
    containers(0).expectMsg(Remove)
    containers(1).expectMsg(runMessageDifferentEverything)
  }

  it should "remove several containers to make space in the pool if it is already full and a different large action arrives" in within(
    timeout) {
    val (containers, factory) = testContainers(3)
    val feed = TestProbe()

    // a pool with slots for 2 actions with default memory limit.
    val pool = system.actorOf(ContainerPool.props(instanceId, factory, poolConfig(512.MB), feed.ref, resMgrFactory))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    pool ! runMessageDifferentAction // 2 * stdMemory taken -> full
    containers(1).expectMsg(runMessageDifferentAction)

    containers(0).send(pool, NeedWork(warmedData())) // first action finished -> 1 * stdMemory taken
    feed.expectMsg(MessageFeed.Processed)
    containers(1).send(pool, NeedWork(warmedData())) // second action finished -> 1 * stdMemory taken
    feed.expectMsg(MessageFeed.Processed)

    pool ! runMessageLarge // need to remove both action to make space for the large action (needs 2 * stdMemory)
    containers(0).expectMsg(Remove)
    containers(1).expectMsg(Remove)
    containers(2).expectMsg(runMessageLarge)
  }

  it should "cache a container if there is still space in the pool" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // a pool with only 1 active slot but 2 slots in total
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 2), feed.ref, resMgrFactory))

    // Run the first container
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(lastUsed = Instant.EPOCH)))
    feed.expectMsg(MessageFeed.Processed)

    // Run the second container, don't remove the first one
    pool ! runMessageDifferentEverything
    containers(1).expectMsg(runMessageDifferentEverything)
    containers(1).send(pool, NeedWork(warmedData(lastUsed = Instant.now)))
    feed.expectMsg(MessageFeed.Processed)
    pool ! runMessageDifferentNamespace
    containers(2).expectMsg(runMessageDifferentNamespace)

    // 2 Slots exhausted, remove the first container to make space
    containers(0).expectMsg(Remove)
  }

  it should "remove a container to make space in the pool if it is already full and another action with different invocation namespace arrives" in within(
    timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // a pool with only 1 slot
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory), feed.ref, resMgrFactory))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))
    feed.expectMsg(MessageFeed.Processed)
    pool ! runMessageDifferentNamespace
    containers(0).expectMsg(Remove)
    containers(1).expectMsg(runMessageDifferentNamespace)
  }

  it should "reschedule job when container is removed prematurely without sending message to feed" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // a pool with only 1 slot
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory), feed.ref, resMgrFactory))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, RescheduleJob) // emulate container failure ...
    containers(0).send(pool, runMessage) // ... causing job to be rescheduled
    feed.expectNoMessage(100.millis)
    containers(1).expectMsg(runMessage) // job resent to new actor
  }

  it should "not start a new container if there is not enough space in the pool" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 2), feed.ref, resMgrFactory))

    // Start first action
    pool ! runMessage // 1 * stdMemory taken
    containers(0).expectMsg(runMessage)

    // Send second action to the pool
    pool ! runMessageLarge // message is too large to be processed immediately.
    containers(1).expectNoMessage(100.milliseconds)

    // First action is finished
    containers(0).send(pool, NeedWork(warmedData())) // pool is empty again.
    containers(0).expectMsg(Remove)
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageLarge.action, runMessageLarge.msg, Some(_)) => true
    }

    containers(1).send(pool, NeedWork(warmedData()))
    feed.expectMsg(MessageFeed.Processed)
  }

  /*
   * CONTAINER PREWARMING
   */
  it should "create prewarmed containers on startup" in within(timeout) {
    val (containers, factory) = testContainers(1)
    val feed = TestProbe()

    val pool =
      system.actorOf(
        ContainerPool
          .props(
            instanceId,
            factory,
            poolConfig(0.MB),
            feed.ref,
            resMgrFactory,
            List(PrewarmingConfig(1, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
  }

  it should "use a prewarmed container and create a new one to fill its place" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool =
      system.actorOf(
        ContainerPool
          .props(
            instanceId,
            factory,
            poolConfig(MemoryLimit.stdMemory),
            feed.ref,
            resMgrFactory,
            List(PrewarmingConfig(1, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind)))
    pool ! runMessage
    containers(1).expectMsg(Start(exec, memoryLimit))
  }

  it should "not use a prewarmed container if it doesn't fit the kind" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val alternativeExec = CodeExecAsString(RuntimeManifest("anotherKind", ImageName("testImage")), "testCode", None)

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          resMgrFactory,
          List(PrewarmingConfig(1, alternativeExec, memoryLimit))))
    containers(0).expectMsg(Start(alternativeExec, memoryLimit)) // container0 was prewarmed
    containers(0).send(pool, NeedWork(preWarmedData(alternativeExec.kind)))
    pool ! runMessage
    containers(1).expectMsg(runMessage) // but container1 is used
  }

  it should "not use a prewarmed container if it doesn't fit memory wise" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val alternativeLimit = 128.MB

    val pool =
      system.actorOf(
        ContainerPool
          .props(
            instanceId,
            factory,
            poolConfig(MemoryLimit.stdMemory),
            feed.ref,
            resMgrFactory,
            List(PrewarmingConfig(1, exec, alternativeLimit))))
    containers(0).expectMsg(Start(exec, alternativeLimit)) // container0 was prewarmed
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind, alternativeLimit)))
    pool ! runMessage
    containers(1).expectMsg(runMessage) // but container1 is used
  }

  /*
   * CONTAINER DELETION
   */
  it should "not reuse a container which is scheduled for deletion" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    // container0 is created and used
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))

    // container0 is reused
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData()))

    // container0 is deleted
    containers(0).send(pool, ContainerRemoved)

    // container1 is created and used
    pool ! runMessage
    containers(1).expectMsg(runMessage)
  }

  /*
   * Run buffer
   */
  it should "first put messages into the queue and retrying them and then put messages only into the queue" in within(
    timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // Pool with 512 MB usermemory
    val pool =
      system.actorOf(
        ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 2), feed.ref, resMgrFactory))

    // Send action that blocks the pool
    pool ! runMessageLarge
    containers(0).expectMsg(runMessageLarge)

    // Send action that should be written to the queue and retried in invoker
    pool ! runMessage
    containers(1).expectNoMessage(100.milliseconds)

    // Send another message that should not be retried, but put into the queue as well
    pool ! runMessageDifferentAction
    containers(2).expectNoMessage(100.milliseconds)

    // Action with 512 MB is finished
    containers(0).send(pool, NeedWork(warmedData()))
//    feed.expectMsg(MessageFeed.Processed)

    // Action 1 should start immediately
    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessage.action, runMessage.msg, Some(_)) => true
    }
    // Action 2 should start immediately as well (without any retries, as there is already enough space in the pool)
    containers(1).expectMsg(runMessageDifferentAction)
  }

  it should "process activations in the order they are arriving" in within(timeout) {
    val (containers, factory) = testContainers(4)
    val feed = TestProbe()

    // Pool with 512 MB usermemory
    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 2), feed.ref, resMgrFactory))

    // Send 4 actions to the ContainerPool (Action 0, Action 2 and Action 3 with each 265 MB and Action 1 with 512 MB)
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    pool ! runMessageLarge
    containers(1).expectNoMessage(100.milliseconds)
    pool ! runMessageDifferentNamespace
    containers(2).expectNoMessage(100.milliseconds)
    pool ! runMessageDifferentAction
    containers(3).expectNoMessage(100.milliseconds)

    // Action 0 ist finished -> Large action should be executed now
    containers(0).send(pool, NeedWork(warmedData()))
//    feed.expectMsg(MessageFeed.Processed)
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageLarge.action, runMessageLarge.msg, Some(_)) => true
    }

    // Send another action to the container pool, that would fit memory-wise
    pool ! runMessageDifferentEverything
    containers(4).expectNoMessage(100.milliseconds)

    // Action 1 is finished -> Action 2 and Action 3 should be executed now
    containers(1).send(pool, NeedWork(warmedData()))
    containers(2).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageDifferentNamespace.action, runMessageDifferentNamespace.msg, None) => true
    }
    // Assert retryLogline = false to check if this request has been stored in the queue instead of retrying in the system
    containers(3).expectMsg(runMessageDifferentAction)

    // Action 3 is finished -> Action 4 should start
    containers(3).send(pool, NeedWork(warmedData()))
    containers(4).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageDifferentEverything.action, runMessageDifferentEverything.msg, None) => true
    }

    // Action 2 and 4 are finished
    containers(2).send(pool, NeedWork(warmedData()))
    containers(4).send(pool, NeedWork(warmedData()))
    feed.expectMsg(MessageFeed.Processed)
  }

  it should "increase activation counts when scheduling to containers whose actions support concurrency" in {
    assume(concurrencyEnabled)
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    // container0 is created and used
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container0 is reused
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container0 is reused
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container1 is created and used (these concurrent containers are configured with max 3 concurrent activations)
    pool ! runMessageConcurrent
    containers(1).expectMsg(runMessageConcurrent)
  }

  it should "schedule concurrent activations to different containers for different namespaces" in {
    assume(concurrencyEnabled)
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    // container0 is created and used
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container1 is created and used
    pool ! runMessageConcurrentDifferentNamespace
    containers(1).expectMsg(runMessageConcurrentDifferentNamespace)
  }

  it should "decrease activation counts when receiving NeedWork for actions that support concurrency" in {
    assume(concurrencyEnabled)
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(
      ContainerPool.props(instanceId, factory, poolConfig(MemoryLimit.stdMemory * 4), feed.ref, resMgrFactory))

    // container0 is created and used
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container0 is reused
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container0 is reused
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)

    // container1 is created and used (these concurrent containers are configured with max 3 concurrent activations)
    pool ! runMessageConcurrent
    containers(1).expectMsg(runMessageConcurrent)

    // container1 is reused
    pool ! runMessageConcurrent
    containers(1).expectMsg(runMessageConcurrent)

    // container1 is reused
    pool ! runMessageConcurrent
    containers(1).expectMsg(runMessageConcurrent)

    containers(0).send(pool, NeedWork(warmedData(action = concurrentAction)))

    // container0 is reused (since active count decreased)
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)
  }

  it should "init prewarms only when InitPrewarms message is sent, when ContainerResourceManager.autoStartPrewarming is false" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = new ContainerResourceManager {
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = true
    }

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    //prewarms are not started immediately
    containers(0).expectNoMessage
    //prewarms must be started explicitly (e.g. by the ContainerResourceManager)
    pool ! InitPrewarms

    containers(0).expectMsg(Start(exec, memoryLimit)) // container0 was prewarmed
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind)))
    pool ! runMessage
    containers(0).expectMsg(runMessage)

  }

  it should "limit the number of container prewarm starts" in {
    val (containers, factory) = testContainers(3)
    val feed = TestProbe()
    var reservations = 0;
    val resMgr = new ContainerResourceManager {
      override def addReservation(ref: ActorRef, byteSize: ByteSize): Unit = {
        reservations += 1
      }

      //limit reservations to 2 containers
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = {

        if (reservations >= 2) {
          false
        } else {
          true
        }
      }
    }

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(3, exec, memoryLimit)) //configure 3 prewarms, but only allow 2 to start
        ))
    //prewarms are not started immediately
    containers(0).expectNoMessage

    //prewarms must be started explicitly (e.g. by the ContainerResourceManager)
    pool ! InitPrewarms

    containers(0).expectMsg(Start(exec, memoryLimit)) // container0 was prewarmed

    //second container should start
    containers(1).expectMsg(Start(exec, memoryLimit)) // container1 was prewarmed

    //third container should not start
    containers(2).expectNoMessage()

    //verify that resMgr.addReservation is called exactly twice
    reservations shouldBe 2
  }

  it should "request space from cluster if no resources available" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))

    (resMgr
      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
      .expects(memoryLimit, 0, *, false)
      .returning(true)
      .repeat(2)
    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)
    (resMgr.updateUnused(_: Map[ActorRef, ContainerData])).expects(Map.empty[ActorRef, ContainerData])
    (() => resMgr.activationStartLogMessage()).expects().returning("")
    //expect a request for space
    (resMgr.requestSpace(_: ByteSize)).expects(memoryLimit).atLeastOnce()
    //expect reservation release
    (resMgr.releaseReservation(_: ActorRef)).expects(*).atLeastOnce()

    pool ! runMessage

    containers(0).expectMsg(runMessage)

    containers(0)
      .send(pool, NeedResources(memoryLimit)) // container0 launch failed, will cause ContainerResourceManager.requestSpace() invocation
    containers(0)
      .send(pool, ContainerRemoved) // container0 launch failed, will cause ContainerResourceManager.releaseReservation() invocation

  }

  it should "update unused when container capacity is available" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
    val idleGrace = 1.seconds
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, idleGrace = idleGrace),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    val warmed = warmedData()
    (resMgr
      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
      .expects(memoryLimit, 0, *, false)
      .returning(true)
      .repeat(2)

    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)

    (resMgr.updateUnused(_: Map[ActorRef, ContainerData])).expects(Map.empty[ActorRef, ContainerData]).atLeastOnce()
    (() => resMgr.activationStartLogMessage()).expects().returning("").repeat(2)

    //expect the container to become unused after second NeedWork
    (resMgr
      .updateUnused(_: Map[ActorRef, ContainerData]))
      .expects(where { m: Map[ActorRef, ContainerData] =>
        m.values.head.getContainer == warmed.getContainer
      })

    pool ! runMessageConcurrent
    pool ! runMessageConcurrent

    containers(0).expectMsg(runMessageConcurrent)
    containers(0).expectMsg(runMessageConcurrent)

    containers(0).send(pool, NeedWork(warmed))
    containers(0).send(pool, NeedWork(warmed))

  }

//  it should "track resent messages to avoid duplicated resends" in {
//
//    val (containers, factory) = testContainers(2)
//    val feed = TestProbe()
//    var allowLaunch = false
//    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
//    val pool = system.actorOf(
//      ContainerPool
//        .props(
//          instanceId,
//          factory,
//          poolConfig(MemoryLimit.stdMemory, true),
//          feed.ref,
//          List(PrewarmingConfig(1, exec, memoryLimit)),
//          Some(resMgr)))
//
//    val run1 = createRunMessage(concurrentAction, invocationNamespace)
//    val run2 = createRunMessage(concurrentAction, invocationNamespace)
//
//    //resMgr will start by returning false
//    (resMgr
//      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
//      .expects(memoryLimit, 0, *, false)
//      .onCall((p1, p2, p3, p4) => allowLaunch) //use onCall to vary the return value
//      .repeat(3)
//    (resMgr
//      .requestSpace(_: ByteSize))
//      .expects(memoryLimit)
//      .repeat(2)
//    (() => resMgr.rescheduleLogMessage()).expects().repeat(2)
//
//    //after allowing launches
//    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)
//
//    //3 activations started
//    (() => resMgr.activationStartLogMessage()).expects().returning("").repeat(1)
//
//    pool ! run1
//    pool ! run2
//
//    //if we don't track resends, notifying resource updates will cause same message to be resent repeatedly
//    pool ! ResourceUpdate
//    pool ! ResourceUpdate
//
//    containers(0).expectNoMessage() //will cause waiting
//    //now allow launching
//    allowLaunch = true
//
//    pool ! ResourceUpdate
//    pool ! ResourceUpdate
//
//    containers(0).expectMsg(run1)
//    containers(0).expectMsg(run2)
//
//  }

  it should "process runbuffer when container is removed" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    //resMgr will start by returning false
    var allowLaunch = false;
    val resMgr = new ContainerResourceManager {
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = {
        allowLaunch
      }
    }
    val run1 = createRunMessage(concurrentAction, invocationNamespace)
    val run2 = createRunMessage(concurrentAction, invocationNamespace)

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, true),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))

    //these will get buffered since allowLaunch is false
    pool ! run1
    pool ! run2

    containers(0).expectNoMessage() //will cause waiting
    //now allow launching
    allowLaunch = true
    //trigger buffer processing by ContainerRemoved message
    pool ! ContainerRemoved

    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run1.action, run1.msg, Some(_)) => true
    }

    containers(0).expectMsg(run2)

  }
  it should "process runbuffer on ResourceUpdate" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    //resMgr will start by returning false
    var allowLaunch = false;
    val resMgr = new ContainerResourceManager {
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = {
        allowLaunch
      }
    }
    val run1 = createRunMessage(concurrentAction, invocationNamespace)
    val run2 = createRunMessage(concurrentAction, invocationNamespace)

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, true),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))

    //these will get buffered since allowLaunch is false
    pool ! run1
    pool ! run2

    containers(0).expectNoMessage() //will cause waiting
    //now allow launching
    allowLaunch = true
    //trigger buffer processing by ContainerRemoved message
    pool ! ResourceUpdate

    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run1.action, run1.msg, Some(_)) => true
    }
    containers(0).expectMsg(run2)
  }
  it should "release reservation on ContainerStarted" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    (resMgr
      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
      .expects(memoryLimit, 0, *, false)
      .returning(true)
      .repeat(2)

    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)

    //(resMgr.updateUnused(_: Map[ActorRef, ContainerData])).expects(Map.empty[ActorRef, ContainerData]).atLeastOnce()
    (() => resMgr.activationStartLogMessage()).expects().returning("").repeat(2)

    //expect the container to become unused after second NeedWork
    (resMgr.releaseReservation(_: ActorRef)).expects(containers(0).ref).atLeastOnce()

    pool ! runMessageConcurrent
    pool ! runMessageConcurrent

    containers(0).expectMsg(runMessageConcurrent)
    containers(0).expectMsg(runMessageConcurrent)

    //ContainerStarted will cause resMgr.releaseReservation call
    containers(0).send(pool, ContainerStarted)

  }
  it should "request space from cluster on NeedResources" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    (resMgr
      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
      .expects(memoryLimit, 0, *, false)
      .returning(true) //return true, but simulate unavailable resources by sending NeedResources back
      .repeat(2)

    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)

    (() => resMgr.activationStartLogMessage()).expects().returning("").repeat(1)

    //expect to call resMgr.requestSpace due to NeedResources
    (resMgr.requestSpace(_: ByteSize)).expects(memoryLimit)

    pool ! runMessageConcurrent

    containers(0).expectMsg(runMessageConcurrent)

    containers(0)
      .send(pool, NeedResources(memoryLimit)) // container0 launch failed, will cause ContainerResourceManager.requestSpace() invocation

  }
  it should "remove unused on ReleaseFree" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = mock[ContainerResourceManager] //mock to capture invocations
    val idleGrace = 1.seconds
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, idleGrace = idleGrace),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    val warmed = warmedData() //will only be released if lastUsed is after idleGrace

    (resMgr
      .canLaunch(_: ByteSize, _: Long, _: ContainerPoolConfig, _: Boolean))
      .expects(memoryLimit, 0, *, false)
      .returning(true)
      .repeat(2)

    (resMgr.addReservation(_: ActorRef, _: ByteSize)).expects(*, memoryLimit)

    (resMgr.updateUnused(_: Map[ActorRef, ContainerData])).expects(Map.empty[ActorRef, ContainerData]).atLeastOnce()
    (() => resMgr.activationStartLogMessage()).expects().returning("").repeat(2)

    //expect the container to become unused after second NeedWork
    var warmLastUsed: Instant = null
    (resMgr
      .updateUnused(_: Map[ActorRef, ContainerData]))
      .expects(where { m: Map[ActorRef, ContainerData] =>
        if (m.values.head.getContainer == warmed.getContainer) {
          warmLastUsed = m.values.head.lastUsed //this bit of hackery allows us to capture the lastUsed value from the arg
          true
        } else {
          false
        }

      })

    println(s"last used ${warmed.lastUsed}")
    pool ! runMessageConcurrent
    pool ! runMessageConcurrent

    containers(0).expectMsg(runMessageConcurrent)
    containers(0).expectMsg(runMessageConcurrent)

    containers(0).send(pool, NeedWork(warmed))
    containers(0)
      .send(pool, NeedWork(warmed))

    //container will become unused, and removable only after idle grace period
    Thread.sleep(idleGrace.toMillis + 1)

    pool ! ReleaseFree(List(RemoteContainerRef(memoryLimit, warmLastUsed, warmed.container.addr)))

    containers(0).expectMsg(Remove)

  }

  it should "not remove unused on ReleaseFree if idleGrace has not passed" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val resMgr = new ContainerResourceManager {
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = {
        true
      }
    }
    val idleGrace = 25.seconds
    val pool = TestActorRef(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, idleGrace = idleGrace),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))
    val warmed = warmedData(lastUsed = Instant.now().minusSeconds(idleGrace.toSeconds - 3)) //will only be released if lastUsed is after idleGrace

    pool ! runMessageConcurrent
    pool ! runMessageConcurrent

    containers(0).expectMsg(runMessageConcurrent)
    containers(0).expectMsg(runMessageConcurrent)

    containers(0).send(pool, NeedWork(warmed))
    containers(0).send(pool, NeedWork(warmed)) //container will become unused, and removable now

    pool ! ReleaseFree(List(RemoteContainerRef(memoryLimit, warmed.lastUsed, warmed.container.addr)))

    containers(0).expectNoMessage()

  }

  it should "process runbuffer instead of requesting new messages" in {

    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    //resMgr will start by returning false
    var allowLaunch = false;
    val resMgr = new ContainerResourceManager {
      override def canLaunch(size: ByteSize,
                             poolMemory: Long,
                             poolConfig: ContainerPoolConfig,
                             prewarm: Boolean): Boolean = {
        allowLaunch
      }
    }
//    val run1 = createRunMessage(concurrentAction, invocationNamespace)
//    val run2 = createRunMessage(concurrentAction, invocationNamespace)

    val pool = system.actorOf(
      ContainerPool
        .props(
          instanceId,
          factory,
          poolConfig(MemoryLimit.stdMemory, true),
          feed.ref,
          _ => resMgr,
          List(PrewarmingConfig(1, exec, memoryLimit))))

    val run1 = createRunMessage(action, invocationNamespace)
    val run2 = createRunMessage(action, invocationNamespace)

    //these will get buffered since allowLaunch is false
    pool ! run1
    pool ! run2

    containers(0).expectNoMessage() //will cause waiting
    //now allow launching
    allowLaunch = true
    //trigger buffer processing by ContainerRemoved message
    pool ! ResourceUpdate
    pool ! ResourceUpdate

    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run1.action, run1.msg, Some(_)) => true
    }
    containers(1).expectMsg(run2)

    feed.expectNoMessage()

    //complete processing of run buffered messages
    containers(0).send(pool, NeedWork(warmedData()))
    containers(1).send(pool, NeedWork(warmedData()))

    //now we expect feed to send a new message (1 per completion = 2 new messages)
    feed.expectMsg(MessageFeed.Processed)
    feed.expectMsg(MessageFeed.Processed)

  }

  it should "remove any amount of space when freeing unused resources in cluster managed case" in {}

}

/**
 * Unit tests for the ContainerPool object.
 *
 * These tests test only the "static" methods "schedule" and "remove"
 * of the ContainerPool object.
 */
@RunWith(classOf[JUnitRunner])
class ContainerPoolObjectTests extends FlatSpec with Matchers with MockFactory {

  val actionExec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val standardNamespace = EntityName("standardNamespace")
  val differentNamespace = EntityName("differentNamespace")

  /** Helper to create a new action from String representations */
  def createAction(namespace: String = "actionNS", name: String = "actionName", limits: ActionLimits = ActionLimits()) =
    ExecutableWhiskAction(EntityPath(namespace), EntityName(name), actionExec, limits = limits)

  /** Helper to create WarmedData with sensible defaults */
  def warmedData(action: ExecutableWhiskAction = createAction(),
                 namespace: String = standardNamespace.asString,
                 lastUsed: Instant = Instant.now,
                 active: Int = 0) =
    WarmedData(stub[MockableContainer], EntityName(namespace), action, lastUsed, active)

  /** Helper to create WarmingData with sensible defaults */
  def warmingData(action: ExecutableWhiskAction = createAction(),
                  namespace: String = standardNamespace.asString,
                  lastUsed: Instant = Instant.now,
                  active: Int = 0) =
    WarmingData(stub[MockableContainer], EntityName(namespace), action, lastUsed, active)

  /** Helper to create WarmingData with sensible defaults */
  def warmingColdData(action: ExecutableWhiskAction = createAction(),
                      namespace: String = standardNamespace.asString,
                      lastUsed: Instant = Instant.now,
                      active: Int = 0) =
    WarmingColdData(EntityName(namespace), action, lastUsed, active)

  /** Helper to create PreWarmedData with sensible defaults */
  def preWarmedData(kind: String = "anyKind") = PreWarmedData(stub[MockableContainer], kind, 256.MB)

  /** Helper to create NoData */
  def noData() = NoData()

  behavior of "ContainerPool schedule()"

  it should "not provide a container if idle pool is empty" in {
    ContainerPool.schedule(createAction(), standardNamespace, Map.empty) shouldBe None
  }

  it should "reuse an applicable warm container from idle pool with one container" in {
    val data = warmedData()
    val pool = Map('name -> data)

    // copy to make sure, referencial equality doesn't suffice
    ContainerPool.schedule(data.action.copy(), data.invocationNamespace, pool) shouldBe Some('name, data)
  }

  it should "reuse an applicable warm container from idle pool with several applicable containers" in {
    val data = warmedData()
    val pool = Map('first -> data, 'second -> data)

    ContainerPool.schedule(data.action.copy(), data.invocationNamespace, pool) should (be(Some('first, data)) or be(
      Some('second, data)))
  }

  it should "reuse an applicable warm container from idle pool with several different containers" in {
    val matchingData = warmedData()
    val pool = Map('none -> noData(), 'pre -> preWarmedData(), 'warm -> matchingData)

    ContainerPool.schedule(matchingData.action.copy(), matchingData.invocationNamespace, pool) shouldBe Some(
      'warm,
      matchingData)
  }

  it should "not reuse a container from idle pool with non-warm containers" in {
    val data = warmedData()
    // data is **not** in the pool!
    val pool = Map('none -> noData(), 'pre -> preWarmedData())

    ContainerPool.schedule(data.action.copy(), data.invocationNamespace, pool) shouldBe None
  }

  it should "not reuse a warm container with different invocation namespace" in {
    val data = warmedData()
    val pool = Map('warm -> data)
    val differentNamespace = EntityName(data.invocationNamespace.asString + "butDifferent")

    data.invocationNamespace should not be differentNamespace
    ContainerPool.schedule(data.action.copy(), differentNamespace, pool) shouldBe None
  }

  it should "not reuse a warm container with different action name" in {
    val data = warmedData()
    val differentAction = data.action.copy(name = EntityName(data.action.name.asString + "butDifferent"))
    val pool = Map('warm -> data)

    data.action.name should not be differentAction.name
    ContainerPool.schedule(differentAction, data.invocationNamespace, pool) shouldBe None
  }

  it should "not reuse a warm container with different action version" in {
    val data = warmedData()
    val differentAction = data.action.copy(version = data.action.version.upMajor)
    val pool = Map('warm -> data)

    data.action.version should not be differentAction.version
    ContainerPool.schedule(differentAction, data.invocationNamespace, pool) shouldBe None
  }

  it should "not use a container when active activation count >= maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val data = warmedData(
      active = maxConcurrent,
      action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent))))
    val pool = Map('warm -> data)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe None

    val data2 = warmedData(
      active = maxConcurrent - 1,
      action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent))))
    val pool2 = Map('warm -> data2)

    ContainerPool.schedule(data2.action, data2.invocationNamespace, pool2) shouldBe Some('warm, data2)

  }

  it should "use a warming when active activation count < maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent)))
    val data = warmingData(active = maxConcurrent - 1, action = action)
    val pool = Map('warming -> data)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe Some('warming, data)

    val data2 = warmedData(active = maxConcurrent - 1, action = action)
    val pool2 = pool ++ Map('warm -> data2)

    ContainerPool.schedule(data2.action, data2.invocationNamespace, pool2) shouldBe Some('warm, data2)
  }

  it should "prefer warm to warming when active activation count < maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent)))
    val data = warmingColdData(active = maxConcurrent - 1, action = action)
    val data2 = warmedData(active = maxConcurrent - 1, action = action)
    val pool = Map('warming -> data, 'warm -> data2)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe Some('warm, data2)
  }

  it should "use a warmingCold when active activation count < maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent)))
    val data = warmingColdData(active = maxConcurrent - 1, action = action)
    val pool = Map('warmingCold -> data)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe Some('warmingCold, data)

    //after scheduling, the pool will update with new data to set active = maxConcurrent
    val data2 = warmingColdData(active = maxConcurrent, action = action)
    val pool2 = Map('warmingCold -> data2)

    ContainerPool.schedule(data2.action, data2.invocationNamespace, pool2) shouldBe None
  }

  it should "prefer warm to warmingCold when active activation count < maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent)))
    val data = warmingColdData(active = maxConcurrent - 1, action = action)
    val data2 = warmedData(active = maxConcurrent - 1, action = action)
    val pool = Map('warmingCold -> data, 'warm -> data2)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe Some('warm, data2)
  }

  it should "prefer warming to warmingCold when active activation count < maxconcurrent" in {
    val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
    val maxConcurrent = if (concurrencyEnabled) 25 else 1

    val action = createAction(limits = ActionLimits(concurrency = ConcurrencyLimit(maxConcurrent)))
    val data = warmingColdData(active = maxConcurrent - 1, action = action)
    val data2 = warmingData(active = maxConcurrent - 1, action = action)
    val pool = Map('warmingCold -> data, 'warming -> data2)
    ContainerPool.schedule(data.action, data.invocationNamespace, pool) shouldBe Some('warming, data2)
  }

  behavior of "ContainerPool remove()"

  it should "not provide a container if pool is empty" in {
    ContainerPool.remove(Map.empty[Any, ContainerData], MemoryLimit.stdMemory, Instant.now()) shouldBe Map.empty
  }

  it should "not provide a container from busy pool with non-warm containers" in {
    val pool = Map('none -> noData(), 'pre -> preWarmedData())
    ContainerPool.remove(pool, MemoryLimit.stdMemory, Instant.now()) shouldBe Map.empty
  }

  it should "not provide a container from pool if there is not enough capacity" in {
    val pool = Map('first -> warmedData())

    ContainerPool.remove(pool, MemoryLimit.stdMemory * 2, Instant.now()) shouldBe Map.empty
  }

  it should "provide a container from pool with one single free container" in {
    val data = warmedData()
    val pool = Map('warm -> data)
    ContainerPool.remove(pool, MemoryLimit.stdMemory, Instant.now()) shouldBe Map(
      'warm -> data.action.limits.memory.megabytes.MB)
  }

  it should "provide oldest container from busy pool with multiple containers" in {
    val commonNamespace = differentNamespace.asString
    val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1))
    val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2))
    val oldest = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0))

    val pool = Map('first -> first, 'second -> second, 'oldest -> oldest)

    ContainerPool.remove(pool, MemoryLimit.stdMemory, Instant.now()) shouldBe Map(
      'oldest -> oldest.action.limits.memory.megabytes.MB)
  }

  it should "provide a list of the oldest containers from pool, if several containers have to be removed" in {
    val namespace = differentNamespace.asString
    val first = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(1))
    val second = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(2))
    val third = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(3))
    val oldest = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(0))

    val pool = Map('first -> first, 'second -> second, 'third -> third, 'oldest -> oldest)

    ContainerPool.remove(pool, MemoryLimit.stdMemory * 2, Instant.now()) shouldBe Map(
      'oldest -> oldest.action.limits.memory.megabytes.MB,
      'first -> first.action.limits.memory.megabytes.MB)
  }

  it should "provide oldest container (excluding concurrently busy) from busy pool with multiple containers" in {
    val commonNamespace = differentNamespace.asString
    val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1), active = 0)
    val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2), active = 0)
    val oldest = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0), active = 3)

    var pool = Map('first -> first, 'second -> second, 'oldest -> oldest)
    ContainerPool.remove(pool, MemoryLimit.stdMemory, Instant.now()) shouldBe Map(
      'first -> first.action.limits.memory.megabytes.MB)
    pool = pool - 'first
    ContainerPool.remove(pool, MemoryLimit.stdMemory, Instant.now()) shouldBe Map(
      'second -> second.action.limits.memory.megabytes.MB)
  }
  it should "not remove container that is younger than idle grace instant" in {
    val data = warmedData()
    val idleGraceInstant = Instant.now().minusSeconds(5)
    val pool = Map('warm -> data)
    ContainerPool.remove(pool, MemoryLimit.stdMemory, idleGraceInstant) shouldBe Map.empty
  }

  it should "find idles to remove, but only the matching and unused" in {
    implicit val logger = new PrintStreamLogging(Console.out)
    val commonNamespace = differentNamespace.asString
    val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1), active = 0)
    val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2), active = 0)
    val third = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0), active = 3)

    var pool = Map('first -> first, 'second -> second, 'third -> third)

    ContainerPool.findIdlesToRemove(
      10.seconds,
      pool,
      List(
        RemoteContainerRef(first.memoryLimit, first.lastUsed, first.container.addr),
        RemoteContainerRef(second.memoryLimit, second.lastUsed, second.container.addr),
        RemoteContainerRef(third.memoryLimit, third.lastUsed, third.container.addr))) shouldBe Set('first, 'second) //cannot remove third since it has active > 0

    ContainerPool.findIdlesToRemove(
      10.seconds,
      pool,
      List(
        RemoteContainerRef(first.memoryLimit, first.lastUsed.minusMillis(1), first.container.addr), //cannot remove first since lastUsed doesn't match
        RemoteContainerRef(second.memoryLimit, second.lastUsed, second.container.addr))) shouldBe Set('second)

  }

}
object MockableContainer {
  val portCounter = new AtomicInteger(0)
}
abstract class MockableContainer extends Container {
  override val addr: ContainerAddress = new ContainerAddress(
    "mock.address",
    MockableContainer.portCounter
      .incrementAndGet()) //scalamock cannot stub this - see https://github.com/paulbutcher/ScalaMock/issues/114
}
