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

package org.apache.openwhisk.core.loadBalancer.test

import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.testkit.TestProbe
import common.{StreamLogging, WhiskProperties}
import java.nio.charset.StandardCharsets

import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import org.apache.openwhisk.common.{InvokerHealth, Logging, NestedSemaphore, TransactionId}
import org.apache.openwhisk.core.entity.FullyQualifiedEntityName
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.connector.CompletionMessage
import org.apache.openwhisk.core.connector.Message
import org.apache.openwhisk.core.connector.MessageConsumer
import org.apache.openwhisk.core.connector.MessageProducer
import org.apache.openwhisk.core.connector.MessagingProvider
import org.apache.openwhisk.core.entity.ActivationId
import org.apache.openwhisk.core.entity.BasicAuthenticationAuthKey
import org.apache.openwhisk.core.entity.ControllerInstanceId
import org.apache.openwhisk.core.entity.EntityName
import org.apache.openwhisk.core.entity.EntityPath
import org.apache.openwhisk.core.entity.ExecManifest
import org.apache.openwhisk.core.entity.Identity
import org.apache.openwhisk.core.entity.InvokerInstanceId
import org.apache.openwhisk.core.entity.MemoryLimit
import org.apache.openwhisk.core.entity.Namespace
import org.apache.openwhisk.core.entity.Secret
import org.apache.openwhisk.core.entity.Subject
import org.apache.openwhisk.core.entity.UUID
import org.apache.openwhisk.core.entity.WhiskActionMetaData
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.loadBalancer.FeedFactory
import org.apache.openwhisk.core.loadBalancer.InvokerPoolFactory
import org.apache.openwhisk.core.loadBalancer._

/**
 * Unit tests for the ContainerPool object.
 *
 * These tests test only the "static" methods "schedule" and "remove"
 * of the ContainerPool object.
 */
@RunWith(classOf[JUnitRunner])
class ShardingContainerPoolBalancerTests
    extends FlatSpec
    with Matchers
    with StreamLogging
    with ExecHelpers
    with MockFactory {
  behavior of "ShardingContainerPoolBalancerState"

  val defaultUserMemory: ByteSize = 1024.MB

  def healthy(i: Int, memory: ByteSize = defaultUserMemory) =
    new InvokerHealth(InvokerInstanceId(i, userMemory = memory), Healthy)
  def unhealthy(i: Int) = new InvokerHealth(InvokerInstanceId(i, userMemory = defaultUserMemory), Unhealthy)
  def offline(i: Int) = new InvokerHealth(InvokerInstanceId(i, userMemory = defaultUserMemory), Offline)

  def semaphores(count: Int, max: Int): IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] =
    IndexedSeq.fill(count)(new NestedSemaphore[FullyQualifiedEntityName](max))

  def lbConfig(blackboxFraction: Double, managedFraction: Option[Double] = None) =
    ShardingContainerPoolBalancerConfig(
      managedFraction.getOrElse(1.0 - blackboxFraction),
      blackboxFraction,
      1,
      1.minute)

  it should "update invoker's state, growing the slots data and keeping valid old data" in {
    // start empty
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.invokers shouldBe 'empty
    state.blackboxInvokers shouldBe 'empty
    state.managedInvokers shouldBe 'empty
    state.invokerSlots shouldBe 'empty
    state.managedStepSizes shouldBe Seq.empty
    state.blackboxStepSizes shouldBe Seq.empty

    // apply one update, verify everything is updated accordingly
    val update1 = IndexedSeq(healthy(0, memory))
    state.updateInvokers(update1)

    state.invokers shouldBe update1
    state.blackboxInvokers shouldBe update1 // fallback to at least one
    state.managedInvokers shouldBe update1 // fallback to at least one
    state.invokerSlots should have size update1.size
    state.invokerSlots.head.availablePermits shouldBe memory.toMB
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)

    // acquire a slot to alter invoker state
    state.invokerSlots.head.tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB.toInt

    // apply second update, growing the state
    val update2 =
      IndexedSeq(healthy(0, memory), healthy(1, memory * 2))
    state.updateInvokers(update2)

    state.invokers shouldBe update2
    state.managedInvokers shouldBe IndexedSeq(update2.head)
    state.blackboxInvokers shouldBe IndexedSeq(update2.last)
    state.invokerSlots should have size update2.size
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB.toInt
    state.invokerSlots(1).tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots(1).availablePermits shouldBe memory.toMB * 2 - memoryPerSlot.toMB
    state.managedStepSizes shouldBe Seq(1)
    state.blackboxStepSizes shouldBe Seq(1)
  }

  it should "allow managed partition to overlap with blackbox for small N" in {
    Seq(0.1, 0.2, 0.3, 0.4, 0.5).foreach { bf =>
      val state = ShardingContainerPoolBalancerState()(lbConfig(bf))

      (1 to 100).toSeq.foreach { i =>
        state.updateInvokers((1 to i).map(_ => healthy(1, MemoryLimit.STD_MEMORY)))

        withClue(s"invoker count $bf $i:") {
          state.managedInvokers.length should be <= i
          state.blackboxInvokers should have size Math.max(1, (bf * i).toInt)

          val m = state.managedInvokers.length
          val b = state.blackboxInvokers.length
          bf match {
            // written out explicitly for clarity
            case 0.1 if i < 10 => m + b shouldBe i + 1
            case 0.2 if i < 5  => m + b shouldBe i + 1
            case 0.3 if i < 4  => m + b shouldBe i + 1
            case 0.4 if i < 3  => m + b shouldBe i + 1
            case 0.5 if i < 2  => m + b shouldBe i + 1
            case _             => m + b shouldBe i
          }
        }
      }
    }
  }

  it should "return the same pools if managed- and blackbox-pools are overlapping" in {

    val state = ShardingContainerPoolBalancerState()(lbConfig(1.0, Some(1.0)))
    (1 to 100).foreach { i =>
      state.updateInvokers((1 to i).map(_ => healthy(1, MemoryLimit.STD_MEMORY)))
    }

    state.managedInvokers should have size 100
    state.blackboxInvokers should have size 100

    state.managedInvokers shouldBe state.blackboxInvokers
  }

  it should "update the cluster size, adjusting the invoker slots accordingly" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory), healthy(1, memory * 2)))

    state.invokerSlots.head.tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots.head.availablePermits shouldBe (memory - memoryPerSlot).toMB

    state.invokerSlots(1).tryAcquire(memoryPerSlot.toMB.toInt)
    state.invokerSlots(1).availablePermits shouldBe memory.toMB * 2 - memoryPerSlot.toMB

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB / 2 // state reset + divided by 2
    state.invokerSlots(1).availablePermits shouldBe memory.toMB
  }

  it should "fallback to a size of 1 (alone) if cluster size is < 1" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory)))

    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(2)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB / 2

    state.updateCluster(0)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(-1)
    state.invokerSlots.head.availablePermits shouldBe memory.toMB
  }

  it should "set the threshold to 1 if the cluster is bigger than there are slots on 1 invoker" in {
    val slots = 10
    val memoryPerSlot = MemoryLimit.MIN_MEMORY
    val memory = memoryPerSlot * slots
    val state = ShardingContainerPoolBalancerState()(lbConfig(0.5))
    state.updateInvokers(IndexedSeq(healthy(0, memory)))

    state.invokerSlots.head.availablePermits shouldBe memory.toMB

    state.updateCluster(20)

    state.invokerSlots.head.availablePermits shouldBe MemoryLimit.MIN_MEMORY.toMB
  }
  val namespace = EntityPath("testspace")
  val name = EntityName("testname")
  val fqn = FullyQualifiedEntityName(namespace, name)

  behavior of "schedule"

  implicit val transId = TransactionId.testing

  it should "return None on an empty invoker list" in {
    ShardingContainerPoolBalancer.schedule(
      1,
      fqn,
      IndexedSeq.empty,
      IndexedSeq.empty,
      MemoryLimit.MIN_MEMORY.toMB.toInt,
      index = 0,
      step = 2) shouldBe None
  }

  it should "return None if no invokers are healthy" in {
    val invokerCount = 3
    val invokerSlots = semaphores(invokerCount, 3)
    val invokers = (0 until invokerCount).map(unhealthy)

    ShardingContainerPoolBalancer.schedule(
      1,
      fqn,
      invokers,
      invokerSlots,
      MemoryLimit.MIN_MEMORY.toMB.toInt,
      index = 0,
      step = 2) shouldBe None
  }

  it should "choose the first available invoker, jumping in stepSize steps, falling back to randomized scheduling once all invokers are full" in {
    val invokerCount = 3
    val slotPerInvoker = 3
    val invokerSlots = semaphores(invokerCount + 3, slotPerInvoker) // needs to be offset by 3 as well
    val invokers = (0 until invokerCount).map(i => healthy(i + 3)) // offset by 3 to asset InstanceId is returned

    val expectedResult = Seq(3, 3, 3, 5, 5, 5, 4, 4, 4)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 2)
        .get
        ._1
        .toInt
    }

    result shouldBe expectedResult

    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 2)
        .get
    }

    bruteResult.map(_._1.toInt) should contain allOf (3, 4, 5)
    bruteResult.map(_._2) should contain only true
  }

  it should "ignore unhealthy or offline invokers" in {
    val invokers = IndexedSeq(healthy(0), unhealthy(1), offline(2), healthy(3))
    val slotPerInvoker = 3
    val invokerSlots = semaphores(invokers.size, slotPerInvoker)

    val expectedResult = Seq(0, 0, 0, 3, 3, 3)
    val result = expectedResult.map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
        .get
        ._1
        .toInt
    }

    result shouldBe expectedResult

    // more schedules will result in randomized invokers, but the unhealthy and offline invokers should not be part
    val bruteResult = (0 to 100).map { _ =>
      ShardingContainerPoolBalancer
        .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
        .get
    }

    bruteResult.map(_._1.toInt) should contain allOf (0, 3)
    bruteResult.map(_._1.toInt) should contain noneOf (1, 2)
    bruteResult.map(_._2) should contain only true
  }

  it should "only take invokers that have enough free slots" in {
    val invokerCount = 3
    // Each invoker has 4 slots
    val invokerSlots = semaphores(invokerCount, 4)
    val invokers = (0 until invokerCount).map(i => healthy(i))

    // Ask for three slots -> First invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 3, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 0
    // Ask for two slots -> Second invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 2, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 1
    // Ask for 1 slot -> First invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 1, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 0
    // Ask for 4 slots -> Third invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 4, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 2
    // Ask for 2 slots -> Second invoker should be used
    ShardingContainerPoolBalancer
      .schedule(1, fqn, invokers, invokerSlots, 2, index = 0, step = 1)
      .get
      ._1
      .toInt shouldBe 1

    invokerSlots.foreach(_.availablePermits shouldBe 0)
  }

  behavior of "pairwiseCoprimeNumbersUntil"

  it should "return an empty set for malformed inputs" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0) shouldBe Seq.empty
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(-1) shouldBe Seq.empty
  }

  it should "return all coprime numbers until the number given" in {
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(1) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(2) shouldBe Seq(1)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(3) shouldBe Seq(1, 2)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(4) shouldBe Seq(1, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(5) shouldBe Seq(1, 2, 3)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(9) shouldBe Seq(1, 2, 5, 7)
    ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(10) shouldBe Seq(1, 3, 7)
  }

  behavior of "concurrent actions"
  it should "allow concurrent actions to be scheduled to same invoker without affecting memory slots" in {
    val invokerCount = 3
    // Each invoker has 2 slots, each action has concurrency 3
    val slots = 2
    val invokerSlots = semaphores(invokerCount, slots)
    val concurrency = 3
    val invokers = (0 until invokerCount).map(i => healthy(i))

    (0 until invokerCount).foreach { i =>
      (1 to slots).foreach { s =>
        (1 to concurrency).foreach { c =>
          ShardingContainerPoolBalancer
            .schedule(concurrency, fqn, invokers, invokerSlots, 1, 0, 1)
            .get
            ._1
            .toInt shouldBe i
          invokerSlots
            .lift(i)
            .get
            .concurrentState(fqn)
            .availablePermits shouldBe concurrency - c
        }
      }
    }

  }

  val config = new WhiskConfig(ExecManifest.requiredProperties)
  val invokerMem = 2000.MB
  val concurrencyEnabled = Option(WhiskProperties.getProperty("whisk.action.concurrency")).exists(_.toBoolean)
  val concurrency = if (concurrencyEnabled) 5 else 1
  val actionMem = 256.MB
  val actionMetaData =
    WhiskActionMetaData(
      namespace,
      name,
      jsMetaData(Some("jsMain"), false),
      limits = actionLimits(actionMem, concurrency))
  val maxContainers = invokerMem.toMB.toInt / actionMetaData.limits.memory.megabytes
  val numInvokers = 3
  val maxActivations = maxContainers * numInvokers * concurrency

  //run a separate test for each variant of 1..n concurrently-ish arriving activations, to exercise:
  // - no containers started
  // - containers started but no concurrency room
  // - no concurrency room and no memory room to launch new containers
  //(1 until maxActivations).foreach { i =>
  (75 until maxActivations).foreach { i =>
    it should s"reflect concurrent processing $i state in containerSlots" in {
      //each batch will:
      // - submit activations concurrently
      // - wait for activation submission to messaging system (mostly to detect which invoker was assiged
      // - verify remaining concurrency slots available
      // - complete activations concurrently
      // - verify concurrency/memory slots are released
      testActivationBatch(i)
    }
  }

  def mockMessaging(): MessagingProvider = {
    val messaging = stub[MessagingProvider]
    val producer = stub[MessageProducer]
    val consumer = stub[MessageConsumer]
    (messaging
      .getProducer(_: WhiskConfig, _: Option[ByteSize])(_: Logging, _: ActorSystem))
      .when(*, *, *, *)
      .returns(producer)
    (messaging
      .getConsumer(_: WhiskConfig, _: String, _: String, _: Int, _: FiniteDuration)(_: Logging, _: ActorSystem))
      .when(*, *, *, *, *, *, *)
      .returns(consumer)
    (producer
      .send(_: String, _: Message, _: Int))
      .when(*, *, *)
      .returns(Future.successful(new RecordMetadata(new TopicPartition("fake", 0), 0, 0, 0l, 0l, 0, 0)))

    messaging
  }

  def testActivationBatch(numActivations: Int): Unit = {
    //setup mock messaging
    val feedProbe = new FeedFactory {
      def createFeed(f: ActorRefFactory, m: MessagingProvider, p: (Array[Byte]) => Future[Unit]) =
        TestProbe().testActor

    }
    val invokerPoolProbe = new InvokerPoolFactory {
      override def createInvokerPool(
        actorRefFactory: ActorRefFactory,
        messagingProvider: MessagingProvider,
        messagingProducer: MessageProducer,
        sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[RecordMetadata],
        monitor: Option[ActorRef]): ActorRef =
        TestProbe().testActor
    }
    val balancer =
      new ShardingContainerPoolBalancer(config, ControllerInstanceId("0"), feedProbe, invokerPoolProbe, mockMessaging)

    val invokers = IndexedSeq.tabulate(numInvokers) { i =>
      new InvokerHealth(InvokerInstanceId(i, userMemory = invokerMem), Healthy)
    }
    balancer.schedulingState.updateInvokers(invokers)
    val invocationNamespace = EntityName("invocationSpace")

    val fqn = actionMetaData.fullyQualifiedName(true)
    val hash =
      ShardingContainerPoolBalancer.generateHash(invocationNamespace, actionMetaData.fullyQualifiedName(false))
    val home = hash % invokers.size
    val stepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(invokers.size)
    val stepSize = stepSizes(hash % stepSizes.size)
    val uuid = UUID()
    //initiate activation
    val published = (0 until numActivations).map { _ =>
      val aid = ActivationId.generate()
      val msg = ActivationMessage(
        TransactionId.testing,
        actionMetaData.fullyQualifiedName(true),
        actionMetaData.rev,
        Identity(Subject(), Namespace(invocationNamespace, uuid), BasicAuthenticationAuthKey(uuid, Secret())),
        aid,
        ControllerInstanceId("0"),
        blocking = false,
        content = None,
        initArgs = Set.empty,
        lockedArgs = Map.empty)

      //send activation to loadbalancer
      aid -> balancer.publish(actionMetaData.toExecutableWhiskAction.get, msg)

    }.toMap

    val activations = published.values
    val ids = published.keys

    //wait for activation submissions
    Await.ready(Future.sequence(activations.toList), 10.seconds)

    val maxActivationsPerInvoker = concurrency * maxContainers
    //verify updated concurrency slots

    def rem(count: Int) =
      if (count % concurrency > 0) {
        concurrency - (count % concurrency)
      } else {
        0
      }

    //assert available permits per invoker are as expected
    var nextInvoker = home
    ids.toList.grouped(maxActivationsPerInvoker).zipWithIndex.foreach { g =>
      val remaining = rem(g._1.size)
      val concurrentState = balancer.schedulingState._invokerSlots
        .lift(nextInvoker)
        .get
        .concurrentState(fqn)
      concurrentState.availablePermits shouldBe remaining
      concurrentState.counter shouldBe g._1.size
      nextInvoker = (nextInvoker + stepSize) % numInvokers
    }

    //complete all
    val acks = ids.map { aid =>
      val invoker = balancer.activationSlots(aid).invokerName
      completeActivation(invoker, balancer, aid)
    }

    Await.ready(Future.sequence(acks), 10.seconds)

    //verify invokers go back to unused state
    invokers.foreach { i =>
      val concurrentState = balancer.schedulingState._invokerSlots
        .lift(i.id.toInt)
        .get
        .concurrentState
        .get(fqn)

      concurrentState shouldBe None
      balancer.schedulingState._invokerSlots.lift(i.id.toInt).map { i =>
        i.availablePermits shouldBe invokerMem.toMB
      }

    }
  }

  def completeActivation(invoker: InvokerInstanceId, balancer: ShardingContainerPoolBalancer, aid: ActivationId) = {
    //complete activation
    val ack =
      CompletionMessage(TransactionId.testing, aid, Some(false), invoker).serialize.getBytes(StandardCharsets.UTF_8)
    balancer.processAcknowledgement(ack)
  }
}
