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

import java.io.{ByteArrayOutputStream, PrintStream}
import java.time.Instant
import java.util.concurrent.TimeUnit

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
import akka.testkit.TestKit
import akka.testkit.TestProbe
import common.{StreamLogging, WhiskProperties}
import org.apache.openwhisk.common.{Logging, PrintStreamLogging, TransactionId}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, ReactivePrewarmingConfig, RuntimeManifest}
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.connector.MessageFeed
import org.scalatest.concurrent.Eventually

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
    with Eventually
    with StreamLogging {

  override def afterAll = TestKit.shutdownActorSystem(system)

  val timeout = 5.seconds

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val memoryLimit = 256.MB
  val ttl = FiniteDuration(500, TimeUnit.MILLISECONDS)
  val threshold = 1
  val increment = 1

  /** Creates a `Run` message */
  def createRunMessage(action: ExecutableWhiskAction, invocationNamespace: EntityName) = {
    val uuid = UUID()
    val message = ActivationMessage(
      TransactionId.testing,
      action.fullyQualifiedName(true),
      action.rev,
      Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
      ActivationId.generate(),
      ControllerInstanceId("0"),
      blocking = false,
      content = None,
      initArgs = Set.empty,
      lockedArgs = Map.empty)
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
      limits = ActionLimits(memory = MemoryLimit(MemoryLimit.STD_MEMORY * 2)))

  val runMessage = createRunMessage(action, invocationNamespace)
  val runMessageLarge = createRunMessage(largeAction, invocationNamespace)
  val runMessageDifferentAction = createRunMessage(differentAction, invocationNamespace)
  val runMessageDifferentVersion = createRunMessage(action.copy().revision(DocRevision("v2")), invocationNamespace)
  val runMessageDifferentNamespace = createRunMessage(action, differentInvocationNamespace)
  val runMessageDifferentEverything = createRunMessage(differentAction, differentInvocationNamespace)
  val runMessageConcurrent = createRunMessage(concurrentAction, invocationNamespace)
  val runMessageConcurrentDifferentNamespace = createRunMessage(concurrentAction, differentInvocationNamespace)

  /** Helper to create PreWarmedData */
  def preWarmedData(kind: String, memoryLimit: ByteSize = memoryLimit, expires: Option[Deadline] = None) =
    PreWarmedData(stub[MockableContainer], kind, memoryLimit, expires = expires)

  /** Helper to create WarmedData */
  def warmedData(run: Run, lastUsed: Instant = Instant.now) = {
    WarmedData(stub[MockableContainer], run.msg.user.namespace.name, run.action, lastUsed)
  }

  /** Creates a sequence of containers and a factory returning this sequence. */
  def testContainers(n: Int) = {
    val containers = (0 to n).map(_ => TestProbe())
    val queue = mutable.Queue(containers: _*)
    val factory = (fac: ActorRefFactory) => queue.dequeue().ref
    (containers, factory)
  }

  def poolConfig(userMemory: ByteSize) =
    ContainerPoolConfig(userMemory, 0.5, false, 1.minute, None, 100)

  behavior of "ContainerPool"

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
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(1).expectNoMessage(100.milliseconds)
  }

  it should "reuse a warm container when action is the same even if revision changes" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))

    pool ! runMessageDifferentVersion
    containers(0).expectMsg(runMessageDifferentVersion)
    containers(1).expectNoMessage(100.milliseconds)
  }

  it should "create a container if it cannot find a matching container" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))
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
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY), feed.ref))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))
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
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(512.MB), feed.ref))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    pool ! runMessageDifferentAction // 2 * stdMemory taken -> full
    containers(1).expectMsg(runMessageDifferentAction)

    containers(0).send(pool, NeedWork(warmedData(runMessage))) // first action finished -> 1 * stdMemory taken
    feed.expectMsg(MessageFeed.Processed)
    containers(1)
      .send(pool, NeedWork(warmedData(runMessageDifferentAction))) // second action finished -> 1 * stdMemory taken
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
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 2), feed.ref))

    // Run the first container
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage, lastUsed = Instant.EPOCH)))
    feed.expectMsg(MessageFeed.Processed)

    // Run the second container, don't remove the first one
    pool ! runMessageDifferentEverything
    containers(1).expectMsg(runMessageDifferentEverything)
    containers(1).send(pool, NeedWork(warmedData(runMessageDifferentEverything, lastUsed = Instant.now)))
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
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY), feed.ref))
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))
    feed.expectMsg(MessageFeed.Processed)
    pool ! runMessageDifferentNamespace
    containers(0).expectMsg(Remove)
    containers(1).expectMsg(runMessageDifferentNamespace)
  }

  it should "reschedule job when container is removed prematurely without sending message to feed" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    // a pool with only 1 slot
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY), feed.ref))
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

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 2), feed.ref))

    // Start first action
    pool ! runMessage // 1 * stdMemory taken
    containers(0).expectMsg(runMessage)

    // Send second action to the pool
    pool ! runMessageLarge // message is too large to be processed immediately.
    containers(1).expectNoMessage(100.milliseconds)

    // First action is finished
    containers(0).send(pool, NeedWork(warmedData(runMessage))) // pool is empty again.
    feed.expectMsg(MessageFeed.Processed)

    // Second action should run now
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageLarge.action, runMessageLarge.msg, Some(_)) => true
    }

    containers(1).send(pool, NeedWork(warmedData(runMessageLarge)))
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
          .props(factory, poolConfig(0.MB), feed.ref, List(PrewarmingConfig(1, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
  }

  it should "use a prewarmed container and create a new one to fill its place" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool =
      system.actorOf(
        ContainerPool
          .props(factory, poolConfig(MemoryLimit.STD_MEMORY), feed.ref, List(PrewarmingConfig(1, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind)))
    pool ! runMessage
    containers(1).expectMsg(Start(exec, memoryLimit))
  }

  it should "use a prewarmed container with ttl and create a new one to fill its place" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()
    val ttl = 5.seconds //make sure replaced prewarm has ttl
    val pool =
      system.actorOf(
        ContainerPool
          .props(
            factory,
            poolConfig(MemoryLimit.STD_MEMORY * 2),
            feed.ref,
            List(PrewarmingConfig(1, exec, memoryLimit, Some(ReactivePrewarmingConfig(1, 1, ttl, 1, 1))))))
    containers(0).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind, expires = Some(ttl.fromNow))))
    pool ! runMessage
    containers(1).expectMsg(Start(exec, memoryLimit, Some(ttl)))
  }
  it should "not use a prewarmed container if it doesn't fit the kind" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val alternativeExec = CodeExecAsString(RuntimeManifest("anotherKind", ImageName("testImage")), "testCode", None)

    val pool = system.actorOf(
      ContainerPool
        .props(
          factory,
          poolConfig(MemoryLimit.STD_MEMORY),
          feed.ref,
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
            factory,
            poolConfig(MemoryLimit.STD_MEMORY),
            feed.ref,
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

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

    // container0 is created and used
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))

    // container0 is reused
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))

    // container0 is deleted
    containers(0).send(pool, ContainerRemoved(true))

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
      system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 2), feed.ref))

    // Send action that blocks the pool
    pool ! runMessageLarge
    // Action 0 starts -> 0MB free
    containers(0).expectMsg(runMessageLarge)

    // Send action that should be written to the queue and retried in invoker
    pool ! runMessage
    containers(1).expectNoMessage(100.milliseconds)

    // Send another message that should not be retried, but put into the queue as well
    pool ! runMessageDifferentAction
    containers(2).expectNoMessage(100.milliseconds)

    // Action with 512 MB is finished
    // Action 0 completes -> 512MB free
    containers(0).send(pool, NeedWork(warmedData(runMessageLarge)))
    // Action 1 should start immediately -> 256MB free
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessage.action, runMessage.msg, Some(_)) => true
    }
    // Action 2 should start immediately as well -> 0MB free (without any retries, as there is already enough space in the pool)
    containers(2).expectMsg(runMessageDifferentAction)

    // When buffer is emptied, process next feed message
    feed.expectMsg(MessageFeed.Processed)

    // Action 1 completes, process feed
    containers(1).send(pool, NeedWork(warmedData(runMessage)))
    feed.expectMsg(MessageFeed.Processed)

    // Action 2 completes, process feed
    containers(2).send(pool, NeedWork(warmedData(runMessageDifferentAction)))
    feed.expectMsg(MessageFeed.Processed)

  }

  it should "process activations in the order they are arriving" in within(timeout) {
    val (containers, factory) = testContainers(6)
    val feed = TestProbe()

    // Pool with 512 MB usermemory
    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 2), feed.ref))

    // Send 4 actions to the ContainerPool (Action 0, Action 2 and Action 3 with each 256 MB and Action 1 with 512 MB)
    pool ! runMessage
    containers(0).expectMsg(runMessage)
    pool ! runMessageLarge
    containers(1).expectNoMessage(100.milliseconds)
    pool ! runMessageDifferentNamespace
    containers(2).expectNoMessage(100.milliseconds)
    pool ! runMessageDifferentAction
    containers(3).expectNoMessage(100.milliseconds)

    // Action 0 is finished -> 512 free; Large action should be executed now (2 more in queue)
    containers(0).send(pool, NeedWork(warmedData(runMessage)))
    // Buffer still has 2, so feed will not be used
    feed.expectNoMessage(100.milliseconds)
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageLarge.action, runMessageLarge.msg, Some(_)) => true
    }

    // Send another action to the container pool, that would fit memory-wise (3 in queue)
    pool ! runMessageDifferentEverything
    containers(4).expectNoMessage(100.milliseconds)

    // Action 1 is finished -> 512 free; Action 2 and Action 3 should be executed now (1 more in queue)
    containers(1).send(pool, NeedWork(warmedData(runMessageLarge)))
    // Buffer still has 1, so feed will not be used
    feed.expectNoMessage(100.milliseconds)

    containers(2).expectMsg(runMessageDifferentNamespace)
    // Assert retryLogline = false to check if this request has been stored in the queue instead of retrying in the system
    containers(3).expectMsg(runMessageDifferentAction)

    // Action 3 is finished -> 256 free; Action 4 should start (0 in queue)
    containers(3).send(pool, NeedWork(warmedData(runMessageDifferentAction)))
    // Buffer is empty, so go back to processing feed
    feed.expectMsg(MessageFeed.Processed)
    // Run the 5th message from the buffer
    containers(4).expectMsg(runMessageDifferentEverything)

    // Action 2 is finished -> 256 free
    containers(2).send(pool, NeedWork(warmedData(runMessageDifferentNamespace)))
    feed.expectMsg(MessageFeed.Processed)

    pool ! runMessage
    // Back to buffering
    pool ! runMessageDifferentVersion
    containers(5).expectMsg(runMessage)
    // Action 6 won't start because it is buffered, waiting for Action 4 to complete
    containers(6).expectNoMessage(100.milliseconds)

    // Action 4 is finished -> 256 free
    containers(4).send(pool, NeedWork(warmedData(runMessageDifferentEverything)))

    // Run the 6th message from the buffer
    containers(6).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(runMessageDifferentVersion.action, runMessageDifferentVersion.msg, Some(_)) => true
    }

    // When buffer is emptied, process next feed message
    feed.expectMsg(MessageFeed.Processed)

    // Action 5 is finished -> 256 free, process feed
    containers(5).send(pool, NeedWork(warmedData(runMessage)))
    feed.expectMsg(MessageFeed.Processed)

    // Action 6 is finished -> 512 free, process feed
    containers(6).send(pool, NeedWork(warmedData(runMessageDifferentVersion)))
    feed.expectMsg(MessageFeed.Processed)
  }

  it should "process runbuffer instead of requesting new messages" in {

    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 1), feed.ref))

    val run1 = createRunMessage(action, invocationNamespace)
    val run2 = createRunMessage(action, invocationNamespace)
    val run3 = createRunMessage(action, invocationNamespace)

    pool ! run1
    pool ! run2 //will be buffered since the pool can only fit 1
    pool ! run3 //will be buffered since the pool can only fit 1

    //start first run
    containers(0).expectMsg(run1)

    //cannot launch more containers, so make sure additional containers are not created
    containers(1).expectNoMessage(100.milliseconds)

    //complete processing of first run
    containers(0).send(pool, NeedWork(warmedData(run1)))

    //don't feed till runBuffer is emptied
    feed.expectNoMessage(100.milliseconds)

    //start second run
    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run2.action, run2.msg, Some(_)) => true
    }

    //complete processing of second run
    containers(0).send(pool, NeedWork(warmedData(run2)))

    //feed as part of last buffer item processing
    feed.expectMsg(MessageFeed.Processed)

    //start third run
    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run3.action, run3.msg, None) => true
    }

    //complete processing of third run
    containers(0).send(pool, NeedWork(warmedData(run3)))

    //now we expect feed to send a new message (1 per completion = 2 new messages)
    feed.expectMsg(MessageFeed.Processed)

    //make sure only one though
    feed.expectNoMessage(100.milliseconds)
  }

  it should "process runbuffer when container is removed" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val run1 = createRunMessage(action, invocationNamespace)
    val run2 = createRunMessage(action, invocationNamespace)

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 1), feed.ref))

    //these will get buffered since allowLaunch is false
    pool ! run1
    pool ! run2

    //start first run
    containers(0).expectMsg(run1)

    //trigger removal of the container ref, but don't start processing
    containers(0).send(pool, RescheduleJob)

    //trigger buffer processing by ContainerRemoved message
    pool ! ContainerRemoved(true)

    //start second run
    containers(1).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run2.action, run2.msg, Some(_)) => true
    }
  }

  it should "process runbuffered items only once" in {
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 1), feed.ref))

    val run1 = createRunMessage(action, invocationNamespace)
    val run2 = createRunMessage(action, invocationNamespace)
    val run3 = createRunMessage(action, invocationNamespace)

    pool ! run1
    pool ! run2 //will be buffered since the pool can only fit 1
    pool ! run3 //will be buffered since the pool can only fit 1

    //start first run
    containers(0).expectMsg(run1)

    //cannot launch more containers, so make sure additional containers are not created
    containers(1).expectNoMessage(100.milliseconds)

    //ContainerRemoved triggers buffer processing - if we don't prevent duplicates, this will cause the buffer head to be resent!
    pool ! ContainerRemoved(true)
    pool ! ContainerRemoved(true)
    pool ! ContainerRemoved(true)

    //complete processing of first run
    containers(0).send(pool, NeedWork(warmedData(run1)))

    //don't feed till runBuffer is emptied
    feed.expectNoMessage(100.milliseconds)

    //start second run
    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run2.action, run2.msg, Some(_)) => true
    }

    //complete processing of second run
    containers(0).send(pool, NeedWork(warmedData(run2)))

    //feed as part of last buffer item processing
    feed.expectMsg(MessageFeed.Processed)

    //start third run
    containers(0).expectMsgPF() {
      // The `Some` assures, that it has been retried while the first action was still blocking the invoker.
      case Run(run3.action, run3.msg, None) => true
    }

    //complete processing of third run
    containers(0).send(pool, NeedWork(warmedData(run3)))

    //now we expect feed to send a new message (1 per completion = 2 new messages)
    feed.expectMsg(MessageFeed.Processed)

    //make sure only one though
    feed.expectNoMessage(100.milliseconds)
  }
  it should "increase activation counts when scheduling to containers whose actions support concurrency" in {
    assume(concurrencyEnabled)
    val (containers, factory) = testContainers(2)
    val feed = TestProbe()

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

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

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

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

    val pool = system.actorOf(ContainerPool.props(factory, poolConfig(MemoryLimit.STD_MEMORY * 4), feed.ref))

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

    containers(0).send(pool, NeedWork(warmedData(runMessageConcurrent)))

    // container0 is reused (since active count decreased)
    pool ! runMessageConcurrent
    containers(0).expectMsg(runMessageConcurrent)
  }

  it should "backfill prewarms when prewarm containers are removed" in {
    val (containers, factory) = testContainers(6)
    val feed = TestProbe()

    val pool =
      system.actorOf(ContainerPool
        .props(factory, poolConfig(MemoryLimit.STD_MEMORY * 5), feed.ref, List(PrewarmingConfig(2, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectMsg(Start(exec, memoryLimit))

    //removing 2 prewarm containers will start 2 containers via backfill
    containers(0).send(pool, ContainerRemoved(true))
    containers(1).send(pool, ContainerRemoved(true))
    containers(2).expectMsg(Start(exec, memoryLimit))
    containers(3).expectMsg(Start(exec, memoryLimit))
    //make sure extra prewarms are not started
    containers(4).expectNoMessage(100.milliseconds)
    containers(5).expectNoMessage(100.milliseconds)
  }

  it should "adjust prewarm container run well without reactive config" in {
    val (containers, factory) = testContainers(4)
    val feed = TestProbe()

    stream.reset()
    val prewarmExpirationCheckIntervel = FiniteDuration(2, TimeUnit.SECONDS)
    val poolConfig =
      ContainerPoolConfig(MemoryLimit.STD_MEMORY * 4, 0.5, false, prewarmExpirationCheckIntervel, None, 100)
    val initialCount = 2
    val pool =
      system.actorOf(
        ContainerPool
          .props(factory, poolConfig, feed.ref, List(PrewarmingConfig(initialCount, exec, memoryLimit))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectMsg(Start(exec, memoryLimit))
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind)))
    containers(1).send(pool, NeedWork(preWarmedData(exec.kind)))

    // when invoker starts, include 0 prewarm container at the very beginning
    stream.toString should include(s"found 0 started")

    // the desiredCount should equal with initialCount when invoker starts
    stream.toString should include(s"desired count: ${initialCount}")

    stream.reset()

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    // Because already supplemented the prewarmed container, so currentCount should equal with initialCount
    eventually {
      stream.toString should not include ("started")
    }
  }

  it should "adjust prewarm container run well with reactive config" in {
    val (containers, factory) = testContainers(15)
    val feed = TestProbe()

    stream.reset()
    val prewarmExpirationCheckIntervel = 2.seconds
    val poolConfig =
      ContainerPoolConfig(MemoryLimit.STD_MEMORY * 8, 0.5, false, prewarmExpirationCheckIntervel, None, 100)
    val minCount = 0
    val initialCount = 2
    val maxCount = 4
    val deadline: Option[Deadline] = Some(ttl.fromNow)
    val reactive: Option[ReactivePrewarmingConfig] =
      Some(ReactivePrewarmingConfig(minCount, maxCount, ttl, threshold, increment))
    val pool =
      system.actorOf(
        ContainerPool
          .props(factory, poolConfig, feed.ref, List(PrewarmingConfig(initialCount, exec, memoryLimit, reactive))))
    //start 2 prewarms
    containers(0).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(1).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(0).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
    containers(1).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))

    // when invoker starts, include 0 prewarm container at the very beginning
    stream.toString should include(s"found 0 started")

    // the desiredCount should equal with initialCount when invoker starts
    stream.toString should include(s"desired count: ${initialCount}")

    stream.reset()

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)
    //expire 2 prewarms
    containers(0).expectMsg(Remove)
    containers(1).expectMsg(Remove)
    containers(0).send(pool, ContainerRemoved(false))
    containers(1).send(pool, ContainerRemoved(false))

    // currentCount should equal with 0 due to these 2 prewarmed containers are expired
    stream.toString should not include (s"found 0 started")

    // the desiredCount should equal with minCount because cold start didn't happen
    stream.toString should not include (s"desired count: ${minCount}")
    // Previously created prewarmed containers should be removed
    stream.toString should not include (s"removed ${initialCount} expired prewarmed container")

    stream.reset()
    val action = ExecutableWhiskAction(
      EntityPath("actionSpace"),
      EntityName("actionName"),
      exec,
      limits = ActionLimits(memory = MemoryLimit(memoryLimit)))
    val run = createRunMessage(action, invocationNamespace)
    // 2 cold start happened
    pool ! run
    pool ! run
    containers(2).expectMsg(run)
    containers(3).expectMsg(run)

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    eventually {
      // Because already removed expired prewarmed containrs, so currentCount should equal with 0
      stream.toString should include(s"found 0 started")
      // the desiredCount should equal with 2 due to cold start happened
      stream.toString should include(s"desired count: 2")
    }
    //add 2 prewarms due to increments
    containers(4).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(5).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(4).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
    containers(5).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))

    stream.reset()

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    containers(4).expectMsg(Remove)
    containers(5).expectMsg(Remove)
    containers(4).send(pool, ContainerRemoved(false))
    containers(5).send(pool, ContainerRemoved(false))

    // removed previous 2 prewarmed container due to expired
    stream.toString should include(s"removing up to ${poolConfig.prewarmExpirationLimit} of 2 expired containers")

    stream.reset()
    // 5 code start happened(5 > maxCount)
    pool ! run
    pool ! run
    pool ! run
    pool ! run
    pool ! run

    containers(6).expectMsg(run)
    containers(7).expectMsg(run)
    containers(8).expectMsg(run)
    containers(9).expectMsg(run)
    containers(10).expectMsg(run)

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    eventually {
      // Because already removed expired prewarmed containrs, so currentCount should equal with 0
      stream.toString should include(s"found 0 started")
      // in spite of the cold start number > maxCount, but the desiredCount can't be greater than maxCount
      stream.toString should include(s"desired count: ${maxCount}")
    }

    containers(11).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(12).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(13).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(14).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(11).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
    containers(12).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
    containers(13).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
    containers(14).send(pool, NeedWork(preWarmedData(exec.kind, expires = deadline)))
  }
}
abstract class MockableContainer extends Container {
  protected[core] val addr: ContainerAddress = ContainerAddress("nohost")
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
  def preWarmedData(kind: String = "anyKind", expires: Option[Deadline] = None) =
    PreWarmedData(stub[MockableContainer], kind, 256.MB, expires = expires)

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
    ContainerPool.remove(Map.empty, MemoryLimit.STD_MEMORY) shouldBe List.empty
  }

  it should "not provide a container from busy pool with non-warm containers" in {
    val pool = Map('none -> noData(), 'pre -> preWarmedData())
    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY) shouldBe List.empty
  }

  it should "not provide a container from pool if there is not enough capacity" in {
    val pool = Map('first -> warmedData())

    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY * 2) shouldBe List.empty
  }

  it should "provide a container from pool with one single free container" in {
    val data = warmedData()
    val pool = Map('warm -> data)
    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY) shouldBe List('warm)
  }

  it should "provide oldest container from busy pool with multiple containers" in {
    val commonNamespace = differentNamespace.asString
    val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1))
    val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2))
    val oldest = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0))

    val pool = Map('first -> first, 'second -> second, 'oldest -> oldest)

    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY) shouldBe List('oldest)
  }

  it should "provide a list of the oldest containers from pool, if several containers have to be removed" in {
    val namespace = differentNamespace.asString
    val first = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(1))
    val second = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(2))
    val third = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(3))
    val oldest = warmedData(namespace = namespace, lastUsed = Instant.ofEpochMilli(0))

    val pool = Map('first -> first, 'second -> second, 'third -> third, 'oldest -> oldest)

    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY * 2) shouldBe List('oldest, 'first)
  }

  it should "provide oldest container (excluding concurrently busy) from busy pool with multiple containers" in {
    val commonNamespace = differentNamespace.asString
    val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1), active = 0)
    val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2), active = 0)
    val oldest = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0), active = 3)

    var pool = Map('first -> first, 'second -> second, 'oldest -> oldest)
    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY) shouldBe List('first)
    pool = pool - 'first
    ContainerPool.remove(pool, MemoryLimit.STD_MEMORY) shouldBe List('second)
  }

  it should "remove expired in order of expiration" in {
    val poolConfig = ContainerPoolConfig(0.MB, 0.5, false, 10.seconds, None, 1)
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    //use a second kind so that we know sorting is not isolated to the expired of each kind
    val exec2 = CodeExecAsString(RuntimeManifest("actionKind2", ImageName("testImage")), "testCode", None)
    val memoryLimit = 256.MB
    val prewarmConfig =
      List(
        PrewarmingConfig(1, exec, memoryLimit, Some(ReactivePrewarmingConfig(0, 10, 10.seconds, 1, 1))),
        PrewarmingConfig(1, exec2, memoryLimit, Some(ReactivePrewarmingConfig(0, 10, 10.seconds, 1, 1))))
    val oldestDeadline = Deadline.now - 1.seconds
    val newerDeadline = Deadline.now
    val newestDeadline = Deadline.now + 1.seconds
    val prewarmedPool = Map(
      'newest -> preWarmedData("actionKind", Some(newestDeadline)),
      'oldest -> preWarmedData("actionKind2", Some(oldestDeadline)),
      'newer -> preWarmedData("actionKind", Some(newerDeadline)))
    lazy val stream = new ByteArrayOutputStream
    lazy val printstream = new PrintStream(stream)
    lazy implicit val logging: Logging = new PrintStreamLogging(printstream)
    ContainerPool.removeExpired(poolConfig, prewarmConfig, prewarmedPool) shouldBe (List('oldest))
  }

  it should "remove only the prewarmExpirationLimit of expired prewarms" in {
    //limit prewarm removal to 2
    val poolConfig = ContainerPoolConfig(0.MB, 0.5, false, 10.seconds, None, 2)
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val memoryLimit = 256.MB
    val prewarmConfig =
      List(PrewarmingConfig(3, exec, memoryLimit, Some(ReactivePrewarmingConfig(0, 10, 10.seconds, 1, 1))))
    //all are overdue, with different expiration times
    val oldestDeadline = Deadline.now - 5.seconds
    val newerDeadline = Deadline.now - 4.seconds
    //the newest* ones are expired, but not the oldest, and not within the limit of 2 prewarms, so won't be removed
    val newestDeadline = Deadline.now - 3.seconds
    val newestDeadline2 = Deadline.now - 2.seconds
    val newestDeadline3 = Deadline.now - 1.seconds
    val prewarmedPool = Map(
      'newest -> preWarmedData("actionKind", Some(newestDeadline)),
      'oldest -> preWarmedData("actionKind", Some(oldestDeadline)),
      'newest3 -> preWarmedData("actionKind", Some(newestDeadline3)),
      'newer -> preWarmedData("actionKind", Some(newerDeadline)),
      'newest2 -> preWarmedData("actionKind", Some(newestDeadline2)))
    lazy val stream = new ByteArrayOutputStream
    lazy val printstream = new PrintStream(stream)
    lazy implicit val logging: Logging = new PrintStreamLogging(printstream)
    ContainerPool.removeExpired(poolConfig, prewarmConfig, prewarmedPool) shouldBe (List('oldest, 'newer))
  }

  it should "remove only the expired prewarms regardless of minCount" in {
    //limit prewarm removal to 100
    val poolConfig = ContainerPoolConfig(0.MB, 0.5, false, 10.seconds, None, 100)
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val memoryLimit = 256.MB
    //minCount is 2 - should leave at least 2 prewarms when removing expired
    val prewarmConfig =
      List(PrewarmingConfig(3, exec, memoryLimit, Some(ReactivePrewarmingConfig(2, 10, 10.seconds, 1, 1))))
    //all are overdue, with different expiration times
    val oldestDeadline = Deadline.now - 5.seconds
    val newerDeadline = Deadline.now - 4.seconds
    //the newest* ones are expired, but not the oldest, and not within the limit of 2 prewarms, so won't be removed
    val newestDeadline = Deadline.now - 3.seconds
    val newestDeadline2 = Deadline.now - 2.seconds
    val newestDeadline3 = Deadline.now - 1.seconds
    val prewarmedPool = Map(
      'newest -> preWarmedData("actionKind", Some(newestDeadline)),
      'oldest -> preWarmedData("actionKind", Some(oldestDeadline)),
      'newest3 -> preWarmedData("actionKind", Some(newestDeadline3)),
      'newer -> preWarmedData("actionKind", Some(newerDeadline)),
      'newest2 -> preWarmedData("actionKind", Some(newestDeadline2)))
    lazy val stream = new ByteArrayOutputStream
    lazy val printstream = new PrintStream(stream)
    lazy implicit val logging: Logging = new PrintStreamLogging(printstream)
    ContainerPool.removeExpired(poolConfig, prewarmConfig, prewarmedPool) shouldBe (List(
      'oldest,
      'newer,
      'newest,
      'newest2,
      'newest3))
  }
}
