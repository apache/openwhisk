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

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestActor, TestKit, TestProbe}
import common.StreamLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common.{Enable, GracefulShutdown, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ContainerCreationError._
import org.apache.openwhisk.core.connector.test.TestConnector
import org.apache.openwhisk.core.connector.{
  ContainerCreationAckMessage,
  ContainerCreationError,
  ContainerCreationMessage,
  MessageProducer
}
import org.apache.openwhisk.core.containerpool.docker.DockerContainer
import org.apache.openwhisk.core.containerpool.v2._
import org.apache.openwhisk.core.containerpool.{
  Container,
  ContainerAddress,
  ContainerPoolConfig,
  ContainerRemoved,
  PrewarmContainerCreationConfig,
  PrewarmingConfig
}
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, ReactivePrewarmingConfig, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.utils.{retry => utilRetry}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import org.scalatest.concurrent.Eventually

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Behavior tests for the ContainerPool
 *
 * These tests test the runtime behavior of a ContainerPool actor.
 */
@RunWith(classOf[JUnitRunner])
class FunctionPullingContainerPoolTests
    extends TestKit(ActorSystem("FunctionPullingContainerPool"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with MockFactory
    with StreamLogging
    with Eventually
    with DbUtils {

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }
  override def afterEach = {
    cleanup()
    super.afterEach()
  }

  private val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config) should be a 'success

  val timeout = 5.seconds

  private implicit val transId = TransactionId.testing
  private implicit val creationId = CreationId.generate()

  // Common entities to pass to the tests. We don't really care what's inside
  // those for the behavior testing here, as none of the contents will really
  // reach a container anyway. We merely assert that passing and extraction of
  // the values is done properly.
  private val actionKind = "nodejs:8"
  private val exec = CodeExecAsString(RuntimeManifest(actionKind, ImageName("testImage")), "testCode", None)
  private val memoryLimit = MemoryLimit.STD_MEMORY.toMB.MB
  private val whiskAction = WhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)
  private val invocationNamespace = EntityName("invocationSpace")
  private val schedulerHost = "127.17.0.1"
  private val rpcPort = 13001
  private val isBlockboxInvocation = false
  private val bigWhiskAction = WhiskAction(
    EntityPath("actionSpace"),
    EntityName("bigActionName"),
    exec,
    limits = ActionLimits(memory = MemoryLimit(memoryLimit * 2)))
  private val execMetadata = CodeExecMetaDataAsString(exec.manifest, entryPoint = exec.entryPoint)
  private val actionMetaData =
    WhiskActionMetaData(
      whiskAction.namespace,
      whiskAction.name,
      execMetadata,
      whiskAction.parameters,
      whiskAction.limits,
      whiskAction.version,
      whiskAction.publish,
      whiskAction.annotations)
  private val bigActionMetaData =
    WhiskActionMetaData(
      bigWhiskAction.namespace,
      bigWhiskAction.name,
      execMetadata,
      bigWhiskAction.parameters,
      bigWhiskAction.limits,
      bigWhiskAction.version,
      bigWhiskAction.publish,
      bigWhiskAction.annotations)
  private val invokerHealthService = TestProbe()
  private val schedulerInstanceId = SchedulerInstanceId("0")
  private val producer = stub[MessageProducer]
  private val prewarmedData = PreWarmData(mock[MockableV2Container], actionKind, memoryLimit)
  private val initializedData =
    InitializedData(
      mock[MockableV2Container],
      invocationNamespace.asString,
      whiskAction.toExecutableWhiskAction.get,
      TestProbe().ref)

  private val entityStore = WhiskEntityStore.datastore()
  private val invokerInstance = InvokerInstanceId(0, userMemory = 0 B)
  private val creationMessage =
    ContainerCreationMessage(
      transId,
      invocationNamespace.asString,
      whiskAction.fullyQualifiedName(true),
      DocRevision.empty,
      actionMetaData,
      schedulerInstanceId,
      schedulerHost,
      rpcPort,
      creationId = creationId)
  private val creationMessageLarge =
    ContainerCreationMessage(
      transId,
      invocationNamespace.asString,
      bigWhiskAction.fullyQualifiedName(true),
      DocRevision.empty,
      bigActionMetaData,
      schedulerInstanceId,
      schedulerHost,
      rpcPort,
      creationId = creationId)

  /** Creates a sequence of containers and a factory returning this sequence. */
  def testContainers(n: Int) = {
    val containers = (0 to n).map(_ => TestProbe())
    val queue = mutable.Queue(containers: _*)
    val factory = (fac: ActorRefFactory) => queue.dequeue().ref
    (containers, factory)
  }

  def poolConfig(userMemory: ByteSize,
                 memorySyncInterval: FiniteDuration = FiniteDuration(1, TimeUnit.SECONDS),
                 prewarmMaxRetryLimit: Int = 3,
                 prewarmPromotion: Boolean = false,
                 prewarmContainerCreationConfig: Option[PrewarmContainerCreationConfig] = None) =
    ContainerPoolConfig(
      userMemory,
      0.5,
      false,
      FiniteDuration(2, TimeUnit.SECONDS),
      FiniteDuration(1, TimeUnit.MINUTES),
      None,
      100,
      prewarmMaxRetryLimit,
      prewarmPromotion,
      memorySyncInterval,
      prewarmContainerCreationConfig)

  def sendAckToScheduler(producer: MessageProducer)(schedulerInstanceId: SchedulerInstanceId,
                                                    ackMessage: ContainerCreationAckMessage): Future[RecordMetadata] = {
    val topic = s"creationAck${schedulerInstanceId.asString}"
    producer.send(topic, ackMessage)
  }

  behavior of "ContainerPool"

  /*
   * CONTAINER SCHEDULING
   *
   * These tests only test the simplest approaches. Look below for full coverage tests
   * of the respective scheduling methods.
   */

  it should "create a container if it cannot find a matching prewarmed container" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY * 4),
          invokerInstance,
          List.empty,
          sendAckToScheduler(producer))))

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(1).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "not start a new container if there is not enough space in the pool" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)
    val bigDoc = put(entityStore, bigWhiskAction)
    // use a fake producer here so sendAckToScheduler won't failed
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY * 2),
          invokerInstance,
          List.empty,
          sendAckToScheduler(producer))))

    // Start first action
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction) // 1 * stdMemory taken
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // Send second action to the pool
    pool ! CreationContainer(creationMessageLarge.copy(revision = bigDoc.rev), bigWhiskAction) // message is too large to be processed immediately.
    containers(1).expectNoMessage(100.milliseconds)

    // First container is removed
    containers(0).send(pool, ContainerRemoved(true)) // pool is empty again.

    pool ! CreationContainer(creationMessageLarge.copy(revision = bigDoc.rev), bigWhiskAction)
    // Second container should run now
    containers(1).expectMsgPF() {
      case Initialize(invocationNamespace, bigExecuteAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "not start a new container if it is shut down" in within(timeout) {
    val (containers, factory) = testContainers(1)
    val doc = put(entityStore, bigWhiskAction)
    val topic = s"creationAck${schedulerInstanceId.asString}"
    val consumer = new TestConnector(topic, 4, true)
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY),
          invokerInstance,
          List.empty,
          sendAckToScheduler(consumer.getProducer()))))

    pool ! GracefulShutdown
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction) // 1 * stdMemory taken

    containers(0).expectNoMessage()

    val error =
      s"creationId: ${creationMessage.creationId}, invoker is shutting down, reschedule ${creationMessage.action.copy(version = None)}"
    val ackMessage =
      createAckMsg(creationMessage.copy(revision = doc.rev), Some(ShuttingDownError), Some(error))

    utilRetry({
      val buffer = consumer.peek(50.millisecond)
      buffer.size shouldBe 1
      buffer.head._1 shouldBe topic
      buffer.head._4 shouldBe ackMessage.serialize.getBytes
    }, 10, Some(500.millisecond))

    // pool should be back to work after enabled again
    pool ! Enable
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction) // 1 * stdMemory taken
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "create prewarmed containers on startup" in within(timeout) {
    stream.reset()
    val (containers, factory) = testContainers(1)

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY * 2),
        invokerInstance,
        List(PrewarmingConfig(1, exec, memoryLimit)),
        sendAckToScheduler(producer))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    stream.toString should include("initing 1 pre-warms to desired count: 1")
    stream.toString should not include ("prewarm container creation is starting with creation delay configuration")
  }

  it should "create prewarmed containers on startup with creation delay configuration" in within(7.seconds) {
    stream.reset()
    val (containers, factory) = testContainers(3)
    val prewarmContainerCreationConfig: Option[PrewarmContainerCreationConfig] =
      Some(PrewarmContainerCreationConfig(1, 3.seconds))

    val poolConfig = ContainerPoolConfig(
      MemoryLimit.STD_MEMORY * 3,
      0.5,
      false,
      FiniteDuration(10, TimeUnit.SECONDS),
      FiniteDuration(10, TimeUnit.SECONDS),
      None,
      100,
      3,
      false,
      FiniteDuration(10, TimeUnit.SECONDS),
      prewarmContainerCreationConfig)

    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig,
          invokerInstance,
          List(PrewarmingConfig(3, exec, memoryLimit)),
          sendAckToScheduler(producer))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectNoMessage(2.seconds)
    containers(1).expectMsg(4.seconds, Start(exec, memoryLimit))
    containers(2).expectNoMessage(2.seconds)
    containers(2).expectMsg(4.seconds, Start(exec, memoryLimit))
    stream.toString should include("prewarm container creation is starting with creation delay configuration")
  }

  it should "backfill prewarms when prewarm containers are removed" in within(timeout) {
    val (containers, factory) = testContainers(6)

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY * 2),
        invokerInstance,
        List(PrewarmingConfig(2, exec, memoryLimit)),
        sendAckToScheduler(producer))))

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

  it should "use a prewarmed container when kind and memory are both match and create a new one to fill its place when prewarmPromotion is false" in within(
    timeout) {
    val (containers, factory) = testContainers(4)
    val doc = put(entityStore, whiskAction)
    val biggerMemory = memoryLimit * 2

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY * 6, prewarmPromotion = false),
        invokerInstance,
        List(PrewarmingConfig(1, exec, memoryLimit), PrewarmingConfig(1, exec, biggerMemory)),
        sendAckToScheduler(producer))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectMsg(Start(exec, biggerMemory))

    // prewarm container is started
    containers(0).send(pool, ReadyToWork(prewarmedData))
    containers(1).send(pool, ReadyToWork(prewarmedData.copy(memoryLimit = biggerMemory)))

    // the prewarm container with matched memory should be chose
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // prewarm a new container
    containers(2).expectMsgPF() {
      case Start(exec, memoryLimit, _) => true
    }

    // the prewarm container with bigger memory should not be chose
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(3).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "use a prewarmed container when kind is matched and create a new one to fill its place when prewarmPromotion is true" in within(
    timeout) {
    val (containers, factory) = testContainers(6)
    val doc = put(entityStore, whiskAction)
    val biggerMemory = memoryLimit * 2
    val biggestMemory = memoryLimit * 3

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY * 6, prewarmPromotion = true),
        invokerInstance,
        List(PrewarmingConfig(1, exec, memoryLimit), PrewarmingConfig(1, exec, biggestMemory)),
        sendAckToScheduler(producer))))
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectMsg(Start(exec, biggestMemory))

    // two prewarm containers are started
    containers(0).send(pool, ReadyToWork(prewarmedData))
    containers(1).send(pool, ReadyToWork(prewarmedData.copy(memoryLimit = biggestMemory)))

    // the prewarm container with smallest memory should be chose
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // prewarm a new container
    containers(2).expectMsgPF() {
      case Start(exec, memoryLimit, _) => true
    }

    // the prewarm container with bigger memory should be chose
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(1).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // prewarm a new container
    containers(3).expectMsgPF() {
      case Start(exec, biggestMemory, _) => true
    }

    // now free memory is (6 - 3 - 1) * stdMemory, and required 2 * stdMemory, so both two prewarmed containers are not suitable
    // a new container should be created
    pool ! CreationContainer(creationMessageLarge.copy(revision = doc.rev), bigWhiskAction)
    containers(4).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // no new prewarmed container should be created
    containers(6).expectNoMessage(500.milliseconds)
  }

  it should "not use a prewarmed container if it doesn't fit the kind" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)

    val alternativeExec = CodeExecAsString(RuntimeManifest("anotherKind", ImageName("testImage")), "testCode", None)

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY),
        invokerInstance,
        List(PrewarmingConfig(1, alternativeExec, memoryLimit)),
        sendAckToScheduler(producer))))

    containers(0).expectMsg(Start(alternativeExec, memoryLimit)) // container0 was prewarmed
    containers(0).send(pool, ReadyToWork(prewarmedData.copy(kind = alternativeExec.kind))) // container0 was started

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(1).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "not use a prewarmed container if it doesn't fit memory wise" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)

    val alternativeLimit = 128.MB

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY),
        invokerInstance,
        List(PrewarmingConfig(1, exec, alternativeLimit)),
        sendAckToScheduler(producer))))

    containers(0).expectMsg(Start(exec, alternativeLimit)) // container0 was prewarmed
    containers(0).send(pool, ReadyToWork(prewarmedData.copy(memoryLimit = alternativeLimit))) // container0 was started

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(1).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "use a warmed container when invocationNamespace, action and revision matched" in within(timeout) {
    val (containers, factory) = testContainers(3)
    val doc = put(entityStore, whiskAction)

    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY * 4),
          invokerInstance,
          List.empty,
          sendAckToScheduler(producer))))

    // register a fake warmed container
    val container = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container.ref)

    // the revision doesn't match, create 1 container
    pool ! CreationContainer(creationMessage, whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // the invocation namespace doesn't match, create 1 container
    pool ! CreationContainer(creationMessage.copy(invocationNamespace = "otherNamespace"), whiskAction)
    containers(1).expectMsgPF() {
      case Initialize("otherNamespace", executeAction, schedulerHost, rpcPort, _) => true
    }

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    container.expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // warmed container is occupied, create 1 more container
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(2).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "retry when chosen warmed container is failed to resume" in within(timeout) {

    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)

    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY * 2),
          invokerInstance,
          List.empty,
          sendAckToScheduler(producer))))

    // register a fake warmed container
    val container = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container.ref)

    // choose the warmed container
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    container.expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

    // warmed container is failed to resume
    pool.tell(
      ResumeFailed(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container.ref)

    // then a new container will be created
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  it should "remove oldest previously used container to make space for the job passed to run" in within(timeout) {
    val (containers, factory) = testContainers(2)
    val doc = put(entityStore, whiskAction)

    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY * 3),
          invokerInstance,
          List.empty,
          sendAckToScheduler(producer))))

    // register three fake warmed containers, so now pool has no space for new container
    val container1 = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container1.ref)

    val container2 = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container2.ref)

    val container3 = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container3.ref)

    // now the pool has no free memory, and new job needs 2*stdMemory, so it needs to remove two warmed containers
    pool ! CreationContainer(creationMessage, bigWhiskAction)
    container1.expectMsg(Remove)
    container2.expectMsg(Remove)
    container3.expectNoMessage()

    // a new container will be created
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
  }

  private def createAckMsg(creationMessage: ContainerCreationMessage,
                           error: Option[ContainerCreationError],
                           reason: Option[String]) = {
    ContainerCreationAckMessage(
      creationMessage.transid,
      creationMessage.creationId,
      invocationNamespace.asString,
      creationMessage.action,
      creationMessage.revision,
      creationMessage.whiskActionMetaData,
      invokerInstance,
      creationMessage.schedulerHost,
      creationMessage.rpcPort,
      creationMessage.retryCount,
      error,
      reason)
  }

  it should "send ack(success) to scheduler when container creation is finished" in within(timeout) {
    val (containers, factory) = testContainers(1)
    val doc = put(entityStore, whiskAction)
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val topic = s"creationAck${creationMessage.rootSchedulerIndex.asString}"

    val consumer = new TestConnector(topic, 4, true)
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY),
          invokerInstance,
          List.empty,
          sendAckToScheduler(consumer.getProducer()))))

    val actualCreationMessage = creationMessage.copy(revision = doc.rev)
    val ackMessage = createAckMsg(actualCreationMessage, None, None)

    pool ! CreationContainer(actualCreationMessage, whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    containers(0).send(pool, Initialized(initializedData)) // container is initialized

    utilRetry({
      val buffer = consumer.peek(50.millisecond)
      buffer.size shouldBe 1
      buffer.head._1 shouldBe topic
      buffer.head._4 shouldBe ackMessage.serialize.getBytes
    }, 10, Some(500.millisecond))
  }

  it should "send ack(success) to scheduler when chosen warmed container is resumed" in within(timeout) {
    val (containers, factory) = testContainers(1)
    val doc = put(entityStore, whiskAction)
    // Actions are created with default memory limit (MemoryLimit.stdMemory). This means 4 actions can be scheduled.
    val topic = s"creationAck${creationMessage.rootSchedulerIndex.asString}"

    val consumer = new TestConnector(topic, 4, true)
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY),
          invokerInstance,
          List.empty,
          sendAckToScheduler(consumer.getProducer()))))

    val actualCreationMessage = creationMessage.copy(revision = doc.rev)
    val ackMessage = createAckMsg(actualCreationMessage, None, None)

    // register a fake warmed container
    val container = TestProbe()
    pool.tell(
      ContainerIsPaused(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container.ref)

    // choose the warmed container
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    container.expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool.tell(
      Resumed(
        WarmData(
          stub[DockerContainer],
          invocationNamespace.asString,
          whiskAction.toExecutableWhiskAction.get,
          doc.rev,
          Instant.now,
          TestProbe().ref)),
      container.ref)

    utilRetry({
      val buffer = consumer.peek(50.millisecond)
      buffer.size shouldBe 1
      buffer.head._1 shouldBe topic
      buffer.head._4 shouldBe ackMessage.serialize.getBytes
    }, 10, Some(500.millisecond))
  }

  it should "send ack(reschedule) to scheduler when container creation is failed or resource is not enough" in within(
    timeout) {
    val (containers, factory) = testContainers(1)
    val doc = put(entityStore, bigWhiskAction)
    val doc2 = put(entityStore, whiskAction)
    val topic = s"creationAck${schedulerInstanceId.asString}"
    val consumer = new TestConnector(topic, 4, true)
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig(MemoryLimit.STD_MEMORY),
          invokerInstance,
          List.empty,
          sendAckToScheduler(consumer.getProducer()))))

    val actualCreationMessageLarge = creationMessageLarge.copy(revision = doc.rev)
    val error =
      s"creationId: ${creationMessageLarge.creationId}, invoker[$invokerInstance] doesn't have enough resource for container: ${creationMessageLarge.action}"
    val ackMessage =
      createAckMsg(actualCreationMessageLarge, Some(ResourceNotEnoughError), Some(error))

    pool ! CreationContainer(actualCreationMessageLarge, bigWhiskAction)

    utilRetry({
      val buffer = consumer.peek(50.millisecond)
      buffer.size shouldBe 1
      buffer.head._1 shouldBe topic
      buffer.head._4 shouldBe ackMessage.serialize.getBytes
    }, 10, Some(500.millisecond))

    val actualCreationMessage = creationMessage.copy(revision = doc2.rev)
    val rescheduleAckMsg = createAckMsg(actualCreationMessage, Some(UnknownError), Some("ContainerProxy init failed."))

    pool ! CreationContainer(actualCreationMessage, whiskAction)
    containers(0).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    containers(0).send(pool, ContainerRemoved(true)) // the container0 init failed or create container failed

    utilRetry({
      val buffer2 = consumer.peek(50.millisecond)
      buffer2.size shouldBe 1
      buffer2.head._1 shouldBe topic
      buffer2.head._4 shouldBe rescheduleAckMsg.serialize.getBytes
    }, 10, Some(500.millisecond))
  }

  it should "send memory info to invokerHealthManager immediately when doesn't have enough resource happens" in within(
    timeout) {
    val (containers, factory) = testContainers(1)
    val doc = put(entityStore, whiskAction)

    val invokerHealthService = TestProbe()
    var count = 0
    invokerHealthService.setAutoPilot((_: ActorRef, msg: Any) =>
      msg match {
        case _: MemoryInfo =>
          count += 1
          TestActor.KeepRunning

        case _ =>
          TestActor.KeepRunning
    })

    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig(MemoryLimit.STD_MEMORY * 1, memorySyncInterval = 1.minute),
        invokerInstance,
        List(PrewarmingConfig(1, exec, memoryLimit)),
        sendAckToScheduler(producer))))
    containers(0).expectMsg(Start(exec, memoryLimit))

    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)

    awaitAssert {
      count shouldBe 3
    }
  }

  it should "adjust prewarm container run well without reactive config" in {
    stream.reset()
    val (containers, factory) = testContainers(4)

    val prewarmExpirationCheckInitDelay = FiniteDuration(2, TimeUnit.SECONDS)
    val prewarmExpirationCheckIntervel = FiniteDuration(2, TimeUnit.SECONDS)
    val poolConfig =
      ContainerPoolConfig(
        MemoryLimit.STD_MEMORY * 4,
        0.5,
        false,
        prewarmExpirationCheckInitDelay,
        prewarmExpirationCheckIntervel,
        None,
        100,
        3,
        false,
        1.second)
    val initialCount = 2
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig,
          invokerInstance,
          List(PrewarmingConfig(initialCount, exec, memoryLimit)),
          sendAckToScheduler(producer))))

    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(1).expectMsg(Start(exec, memoryLimit))
    containers(0).send(pool, ReadyToWork(prewarmedData))
    containers(1).send(pool, ReadyToWork(prewarmedData))

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
    stream.reset()
    val (containers, factory) = testContainers(15)
    val doc = put(entityStore, whiskAction)

    val prewarmExpirationCheckInitDelay = FiniteDuration(2, TimeUnit.SECONDS)
    val prewarmExpirationCheckIntervel = FiniteDuration(2, TimeUnit.SECONDS)
    val poolConfig =
      ContainerPoolConfig(
        MemoryLimit.STD_MEMORY * 8,
        0.5,
        false,
        prewarmExpirationCheckInitDelay,
        prewarmExpirationCheckIntervel,
        None,
        100,
        3,
        false,
        1.second)
    val minCount = 0
    val initialCount = 2
    val maxCount = 4
    val ttl = FiniteDuration(500, TimeUnit.MILLISECONDS)
    val threshold = 1
    val increment = 1
    val deadline: Option[Deadline] = Some(ttl.fromNow)
    val reactive: Option[ReactivePrewarmingConfig] =
      Some(ReactivePrewarmingConfig(minCount, maxCount, ttl, threshold, increment))
    val prewarmedData = PreWarmData(mock[MockableV2Container], actionKind, memoryLimit, deadline)
    val pool = system.actorOf(
      Props(new FunctionPullingContainerPool(
        factory,
        invokerHealthService.ref,
        poolConfig,
        invokerInstance,
        List(PrewarmingConfig(initialCount, exec, memoryLimit, reactive)),
        sendAckToScheduler(producer))))

    containers(0).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(1).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(0).send(pool, ReadyToWork(prewarmedData))
    containers(1).send(pool, ReadyToWork(prewarmedData))

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
    containers(0).send(pool, ContainerRemoved(true))
    containers(1).send(pool, ContainerRemoved(true))

    // currentCount should equal with 0 due to these 2 prewarmed containers are expired
    stream.toString should not include (s"found 0 started")

    // the desiredCount should equal with minCount because cold start didn't happen
    stream.toString should not include (s"desired count: ${minCount}")
    // Previously created prewarmed containers should be removed
    stream.toString should not include (s"removed ${initialCount} expired prewarmed container")

    // 2 cold start happened
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(2).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(3).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    eventually {
      // Because already removed expired prewarmed containrs, so currentCount should equal with 0
      stream.toString should include(s"found 0 started")
      // the desiredCount should equal with 2 due to cold start happened
      stream.toString should include(s"desired count: 2")
    }
    containers(4).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(5).expectMsg(Start(exec, memoryLimit, Some(ttl)))
    containers(4).send(pool, ReadyToWork(prewarmedData))
    containers(5).send(pool, ReadyToWork(prewarmedData))

    stream.reset()

    // Make sure AdjustPrewarmedContainer is sent by ContainerPool's scheduler after prewarmExpirationCheckIntervel time
    Thread.sleep(prewarmExpirationCheckIntervel.toMillis)

    containers(4).expectMsg(Remove)
    containers(5).expectMsg(Remove)
    containers(4).send(pool, ContainerRemoved(true))
    containers(5).send(pool, ContainerRemoved(true))

    // removed previous 2 prewarmed container due to expired
    stream.toString should include(s"removing up to ${poolConfig.prewarmExpirationLimit} of 2 expired containers")

    stream.reset()

    // 5 code start happened(5 > maxCount)
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(6).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(7).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(8).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(9).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }
    pool ! CreationContainer(creationMessage.copy(revision = doc.rev), whiskAction)
    containers(10).expectMsgPF() {
      case Initialize(invocationNamespace, executeAction, schedulerHost, rpcPort, _) => true
    }

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
    containers(11).send(pool, ReadyToWork(prewarmedData))
    containers(12).send(pool, ReadyToWork(prewarmedData))
    containers(13).send(pool, ReadyToWork(prewarmedData))
    containers(14).send(pool, ReadyToWork(prewarmedData))
  }

  it should "prewarm failed creation count cannot exceed max retry limit" in {
    stream.reset()
    val (containers, factory) = testContainers(4)

    val prewarmExpirationCheckInitDelay = FiniteDuration(1, TimeUnit.MINUTES)
    val prewarmExpirationCheckIntervel = FiniteDuration(1, TimeUnit.MINUTES)
    val maxRetryLimit = 3
    val poolConfig =
      ContainerPoolConfig(
        MemoryLimit.STD_MEMORY * 4,
        0.5,
        false,
        prewarmExpirationCheckInitDelay,
        prewarmExpirationCheckIntervel,
        None,
        100,
        maxRetryLimit,
        false,
        1.second)
    val initialCount = 1
    val pool = system.actorOf(
      Props(
        new FunctionPullingContainerPool(
          factory,
          invokerHealthService.ref,
          poolConfig,
          invokerInstance,
          List(PrewarmingConfig(initialCount, exec, memoryLimit)),
          sendAckToScheduler(producer))))

    // create the prewarm initially
    containers(0).expectMsg(Start(exec, memoryLimit))
    containers(0).send(pool, ContainerRemoved(true))
    stream.toString should not include (s"prewarm create failed count exceeds max retry limit")

    // the first retry
    containers(1).expectMsg(Start(exec, memoryLimit))
    containers(1).send(pool, ContainerRemoved(true))
    stream.toString should not include (s"prewarm create failed count exceeds max retry limit")

    // the second retry
    containers(2).expectMsg(Start(exec, memoryLimit))
    containers(2).send(pool, ContainerRemoved(true))
    stream.toString should not include (s"prewarm create failed count exceeds max retry limit")

    // the third retry
    containers(3).expectMsg(Start(exec, memoryLimit))
    containers(3).send(pool, ContainerRemoved(true))

    // the forth retry but failed retry count exceeds max retry limit
    eventually {
      stream.toString should include(s"prewarm create failed count exceeds max retry limit")
    }
  }

}

abstract class MockableV2Container extends Container {
  protected[core] val addr: ContainerAddress = ContainerAddress("nohost")
}
