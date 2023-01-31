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

package org.apache.openwhisk.core.scheduler.container.test

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.ibm.etcd.api.{KeyValue, RangeResponse}
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.common.InvokerState.{Healthy, Unhealthy}
import org.apache.openwhisk.common.{GracefulShutdown, InvokerHealth, Logging, TransactionId}
import org.apache.openwhisk.core.connector.ContainerCreationError.{
  NoAvailableInvokersError,
  NoAvailableResourceInvokersError
}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.{ContainerId, Uninitialized}
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.containerPrefix
import org.apache.openwhisk.core.etcd.EtcdKV.{ContainerKeys, InvokerKeys}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.apache.openwhisk.core.scheduler.container.{ScheduledPair, _}
import org.apache.openwhisk.core.scheduler.message.{
  ContainerCreation,
  ContainerDeletion,
  FailedCreationJob,
  RegisterCreationJob,
  ReschedulingCreationJob
}

import scala.language.postfixOps
import org.apache.openwhisk.core.scheduler.queue.{MemoryQueueKey, MemoryQueueValue, QueuePool}
import org.apache.openwhisk.core.service.{WatchEndpointInserted, WatchEndpointRemoved}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import pureconfig.loadConfigOrThrow
import spray.json.{JsArray, JsBoolean, JsString}
import pureconfig.generic.auto._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}

@RunWith(classOf[JUnitRunner])
class ContainerManagerTests
    extends TestKit(ActorSystem("ContainerManager"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with StreamLogging {

  val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config)

  val testInvocationNamespace = "test-invocation-namespace"
  val testNamespace = "test-namespace"
  val testAction = "test-action"
  val testfqn = FullyQualifiedEntityName(EntityPath(testNamespace), EntityName(testAction))
  val blackboxInvocation = false
  val testCreationId = CreationId.generate()
  val testRevision = DocRevision("1-testRev")
  val testMemory = 256.MB
  val testResources = Seq.empty[String]
  val resourcesStrictPolicy = false

  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val action = ExecutableWhiskAction(EntityPath(testNamespace), EntityName(testAction), exec)
  val execMetadata = CodeExecMetaDataAsString(exec.manifest, entryPoint = exec.entryPoint)
  val actionMetadata =
    WhiskActionMetaData(
      action.namespace,
      action.name,
      execMetadata,
      action.parameters,
      action.limits,
      action.version,
      action.publish,
      action.annotations)

  val invokers: List[InvokerHealth] = List(
    InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(2, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(3, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(4, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(5, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(6, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(7, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(8, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    InvokerHealth(InvokerInstanceId(9, userMemory = testMemory, tags = Seq.empty[String]), Healthy))

  val testsid = SchedulerInstanceId("0")

  val schedulerHost = "127.17.0.1"
  val rpcPort = 13001

  override def afterAll(): Unit = {
    logLines.foreach(println)
    QueuePool.clear()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    QueuePool.clear()
  }

  def mockMessaging(receiver: Option[ActorRef] = None): MessagingProvider = {
    val messaging = stub[MessagingProvider]
    val producer = receiver.map(fakeProducer).getOrElse(stub[MessageProducer])
    val consumer = stub[MessageConsumer]
    (messaging
      .getProducer(_: WhiskConfig, _: Option[ByteSize])(_: Logging, _: ActorSystem))
      .when(*, *, *, *)
      .returns(producer)
    (messaging
      .getConsumer(_: WhiskConfig, _: String, _: String, _: Int, _: FiniteDuration)(_: Logging, _: ActorSystem))
      .when(*, *, *, *, *, *, *)
      .returns(consumer)
    // this is a stub producer
    if (receiver.isEmpty) {
      (producer
        .send(_: String, _: Message, _: Int))
        .when(*, *, *)
        .returns(Future.successful(ResultMetadata("fake", 0, 0)))
    }

    messaging
  }

  private def fakeProducer(receiver: ActorRef) = new MessageProducer {

    /** Count of messages sent. */
    override def sentCount(): Long = 0

    /** Sends msg to topic. This is an asynchronous operation. */
    override def send(topic: String, msg: Message, retry: Int): Future[ResultMetadata] = {
      val message = s"$topic-$msg"
      receiver ! message

      Future.successful(ResultMetadata(topic, 0, -1))
    }

    /** Closes producer. */
    override def close(): Unit = {}
  }

  def expectGetInvokers(etcd: EtcdClient, invokers: List[InvokerHealth] = invokers): Unit = {
    (etcd
      .getPrefix(_: String))
      .expects(InvokerKeys.prefix)
      .returning(Future.successful {
        invokers
          .foldLeft(RangeResponse.newBuilder()) { (builder, invoker) =>
            val msg = InvokerResourceMessage(
              invoker.status.asString,
              invoker.id.userMemory.toMB,
              invoker.id.busyMemory.getOrElse(0.MB).toMB,
              0,
              invoker.id.tags,
              invoker.id.dedicatedNamespaces)

            builder.addKvs(
              KeyValue
                .newBuilder()
                .setKey(InvokerKeys.health(invoker.id))
                .setValue(msg.toString)
                .build())
          }
          .build()
      })
  }

  /** Registers the transition callback and expects the first message */
  def registerCallback(c: ActorRef) = {
    c ! SubscribeTransitionCallBack(testActor)
    expectMsg(CurrentState(c, Uninitialized))
  }

  def factory(t: TestProbe)(f: ActorRefFactory): ActorRef = t.ref

  behavior of "ContainerManager"

  it should "create container" in {
    val mockEtcd = mock[EtcdClient]
    (mockEtcd
      .getPrefix(_: String))
      .expects(*)
      .returning(Future.successful {
        RangeResponse.newBuilder().build()
      })
    expectGetInvokers(mockEtcd)

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns3"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns3"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val msgs = List(msg1, msg2, msg3)
    val creationMsg = ContainerCreation(msgs, testMemory, testInvocationNamespace)

    manager ! creationMsg

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg1`) => true
      case RegisterCreationJob(`msg2`) => true
      case RegisterCreationJob(`msg3`) => true
    }
  }

  it should "try warmed containers first" in {
    val mockEtcd = mock[EtcdClient]

    // at first, invoker states look like this.
    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 256.MB, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 256.MB, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 512.MB, tags = Seq.empty[String]), Healthy),
    )

    // after then, invoker states changes like this.
    val updatedInvokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 512.MB, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 256.MB, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 256.MB, tags = Seq.empty[String]), Healthy),
    )
    expectGetInvokers(mockEtcd, invokers) // for warm up
    expectGetInvokers(mockEtcd, invokers) // for first creation
    expectGetInvokers(mockEtcd, updatedInvokers) // for second creation

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()
    // ignore warmUp message
    receiver.ignoreMsg {
      case s: String => s.contains("warmUp")
    }

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    // Add warmed containers for action1 and action2 in invoker0 and invoker1 respectively
    manager ! WatchEndpointInserted(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        testRevision,
        InvokerInstanceId(0, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)
    manager ! WatchEndpointInserted(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        InvokerInstanceId(1, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msgs = List(msg1, msg2, msg3)

    // it should reuse 2 warmed containers
    manager ! ContainerCreation(msgs, 256.MB, testInvocationNamespace)

    // msg1 will use warmed container on invoker0, msg2 use warmed container on invoker1, msg3 use the remainder
    receiver.expectMsg(s"invoker0-$msg1")
    receiver.expectMsg(s"invoker1-$msg2")
    receiver.expectMsg(s"invoker2-$msg3")

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg1`) => true
      case RegisterCreationJob(`msg2`) => true
      case RegisterCreationJob(`msg3`) => true
    }

    // remove a warmed container from invoker0
    manager ! WatchEndpointRemoved(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        testRevision,
        InvokerInstanceId(0, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    // remove a warmed container from invoker1
    manager ! WatchEndpointRemoved(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        InvokerInstanceId(1, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    // create a warmed container for action1 in from invoker1
    manager ! WatchEndpointInserted(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        testRevision,
        InvokerInstanceId(1, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    // create a warmed container for action2 in from invoker2
    manager ! WatchEndpointInserted(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        InvokerInstanceId(2, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    // it should reuse 2 warmed containers
    manager ! ContainerCreation(msgs, 256.MB, testInvocationNamespace)

    // msg1 will use warmed container on invoker1, msg2 use warmed container on invoker2, msg3 use the remainder
    receiver.expectMsg(s"invoker1-$msg1")
    receiver.expectMsg(s"invoker2-$msg2")
    receiver.expectMsg(s"invoker0-$msg3")

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg1`) => true
      case RegisterCreationJob(`msg2`) => true
      case RegisterCreationJob(`msg3`) => true
    }
  }

  it should "not try warmed containers if revision is unmatched" in {
    val mockEtcd = mock[EtcdClient]

    // for test, only invoker2 is healthy, so that no-warmed creations can be only sent to invoker2
    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    )
    expectGetInvokers(mockEtcd, invokers)
    expectGetInvokers(mockEtcd, invokers) // one for warmup

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()
    // ignore warmUp message
    receiver.ignoreMsg {
      case s: String => s.contains("warmUp")
    }

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    // there are 1 warmed container for `test-namespace/test-action` but with a different revision
    manager ! WatchEndpointInserted(
      ContainerKeys.warmedPrefix,
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        DocRevision("2-testRev"),
        InvokerInstanceId(0, userMemory = 0.bytes),
        ContainerId("fake")),
      "",
      true)

    val msg =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    // it should not reuse the warmed container
    manager ! ContainerCreation(List(msg), 128.MB, testInvocationNamespace)

    // it should be scheduled to the sole health invoker: invoker2
    receiver.expectMsg(s"invoker2-$msg")

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg`) => true
    }
  }

  it should "rescheduling container creation" in {
    val mockEtcd = mock[EtcdClient]
    (mockEtcd
      .getPrefix(_: String))
      .expects(*)
      .returning(Future.successful {
        RangeResponse.newBuilder().build()
      })
    expectGetInvokers(mockEtcd)

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    val reschedulingMsg =
      ReschedulingCreationJob(
        TransactionId.testing,
        testCreationId,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        schedulerHost,
        rpcPort,
        0)

    val creationMsg = reschedulingMsg.toCreationMessage(testsid, reschedulingMsg.retry + 1)

    manager ! reschedulingMsg

    mockJobManager.expectMsg(RegisterCreationJob(creationMsg))
  }

  it should "forward GracefulShutdown to creation job manager" in {
    val mockEtcd = mock[EtcdClient]
    (mockEtcd
      .getPrefix(_: String))
      .expects(*)
      .returning(Future.successful {
        RangeResponse.newBuilder().build()
      })

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    manager ! GracefulShutdown

    mockJobManager.expectMsg(GracefulShutdown)
  }

  it should "generate random number less than mod" in {
    val mod = 10
    (1 to 100).foreach(_ => {
      val num = ContainerManager.rng(mod)
      num should be < mod
    })
  }

  it should "choose invokers" in {
    val healthyInvokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 512.MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 512.MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 512.MB), Healthy))

    val minMemory = 512.MB
    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns1")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns2")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns3")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msgs = List(msg1, msg2, msg3)

    val pairs = ContainerManager.schedule(healthyInvokers, msgs, minMemory)

    pairs.map(_.msg) should contain theSameElementsAs msgs
    pairs.flatMap(_.invokerId).foreach { invokerId =>
      healthyInvokers.map(_.id) should contain(invokerId)
    }
  }

  it should "choose invoker even if there is only one invoker" in {
    val healthyInvokers: List[InvokerHealth] = List(InvokerHealth(InvokerInstanceId(0, userMemory = 1024.MB), Healthy))

    val minMemory = 128.MB
    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns1")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns2")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns3")),
        testRevision,
        actionMetadata.copy(limits = actionMetadata.limits.copy(memory = MemoryLimit(minMemory))),
        testsid,
        schedulerHost,
        rpcPort)
    val msgs = List(msg1, msg2, msg3)

    val pairs = ContainerManager.schedule(healthyInvokers, msgs, minMemory)

    pairs.map(_.msg) should contain theSameElementsAs msgs
    pairs.map(_.invokerId.get.instance).foreach {
      healthyInvokers.map(_.id.instance) should contain(_)
    }
  }

  it should "filter invokers based on tags" in {
    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns1")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("cpu"), JsString("memory")))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns2")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("memory"), JsString("disk")))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns3")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("disk"), JsString("cpu")))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg4 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns4")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg5 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns5")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("fake"))) ++ Parameters(
              Annotations.InvokerResourcesStrictPolicyAnnotationName,
              JsBoolean(true))),
        testsid,
        schedulerHost,
        rpcPort)

    val probe = TestProbe()
    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, testfqn.toDocId.asDocInfo(testRevision)),
      MemoryQueueValue(probe.ref, true))

    val healthyInvokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 512.MB, tags = Seq("cpu", "memory")), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 512.MB, tags = Seq("memory", "disk")), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 512.MB, tags = Seq("disk", "cpu")), Healthy),
      InvokerHealth(InvokerInstanceId(3, userMemory = 512.MB), Healthy))

    // for msg1/2/3 we choose the exact invokers for them, for msg4, we choose no tagged invokers first, here is the invoker3
    // for msg5, there is no available invokers, and the resource strict policy is true, so return an error
    val pairs = ContainerManager.schedule(
      healthyInvokers,
      List(msg1, msg2, msg3, msg4, msg5),
      msg1.whiskActionMetaData.limits.memory.megabytes.MB) // the memory is same for all msgs
    pairs should contain theSameElementsAs List(
      ScheduledPair(msg1, Some(healthyInvokers(0).id), None),
      ScheduledPair(msg2, Some(healthyInvokers(1).id), None),
      ScheduledPair(msg3, Some(healthyInvokers(2).id), None),
      ScheduledPair(msg4, Some(healthyInvokers(3).id), None),
      ScheduledPair(msg5, None, Some(NoAvailableResourceInvokersError)))
  }

  it should "choose tagged invokers when no untagged invoker is available" in {
    val msg =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns1")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns2")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val probe = TestProbe()
    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, testfqn.toDocId.asDocInfo(testRevision)),
      MemoryQueueValue(probe.ref, true))

    val healthyInvokers: List[InvokerHealth] =
      List(InvokerHealth(InvokerInstanceId(0, userMemory = 256.MB, tags = Seq("cpu", "memory")), Healthy))

    // there is no available invokers which has no tags, it should choose tagged invokers for msg
    // and for msg2, it should return no available invokers
    val pairs =
      ContainerManager.schedule(healthyInvokers, List(msg, msg2), msg.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs should contain theSameElementsAs List(
      ScheduledPair(msg, Some(healthyInvokers(0).id), None),
      ScheduledPair(msg2, None, Some(NoAvailableInvokersError)))
  }

  it should "respect the resource policy while use resource filter" in {
    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns1")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("non-exist"))) ++ Parameters(
              Annotations.InvokerResourcesStrictPolicyAnnotationName,
              JsBoolean(true))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns2")),
        testRevision,
        actionMetadata.copy(
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("non-exist"))) ++ Parameters(
              Annotations.InvokerResourcesStrictPolicyAnnotationName,
              JsBoolean(false))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns3")),
        testRevision,
        actionMetadata.copy(
          limits = action.limits.copy(memory = MemoryLimit(256.MB)),
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("non-exist"))) ++ Parameters(
              Annotations.InvokerResourcesStrictPolicyAnnotationName,
              JsBoolean(false))),
        testsid,
        schedulerHost,
        rpcPort)
    val msg4 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.resolve(EntityName("ns3")),
        testRevision,
        actionMetadata.copy(
          limits = action.limits.copy(memory = MemoryLimit(256.MB)),
          annotations =
            Parameters(Annotations.InvokerResourcesAnnotationName, JsArray(JsString("non-exist"))) ++ Parameters(
              Annotations.InvokerResourcesStrictPolicyAnnotationName,
              JsBoolean(false))),
        testsid,
        schedulerHost,
        rpcPort)

    val probe = TestProbe()
    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, testfqn.toDocId.asDocInfo(testRevision)),
      MemoryQueueValue(probe.ref, true))
    val healthyInvokers: List[InvokerHealth] =
      List(
        InvokerHealth(InvokerInstanceId(0, userMemory = 256.MB, tags = Seq.empty[String]), Healthy),
        InvokerHealth(InvokerInstanceId(1, userMemory = 256.MB, tags = Seq("cpu", "memory")), Healthy))

    // while resourcesStrictPolicy is true, and there is no suitable invokers, return an error
    val pairs =
      ContainerManager.schedule(healthyInvokers, List(msg1), msg1.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs should contain theSameElementsAs List(ScheduledPair(msg1, None, Some(NoAvailableResourceInvokersError)))

    // while resourcesStrictPolicy is false, and there is no suitable invokers, should choose no tagged invokers first,
    // here is the invoker0
    val pairs2 =
      ContainerManager.schedule(healthyInvokers, List(msg2), msg2.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs2 should contain theSameElementsAs List(ScheduledPair(msg2, Some(healthyInvokers(0).id), None))

    // while resourcesStrictPolicy is false, and there is no suitable invokers, should choose no tagged invokers first,
    // if there is none, then choose invokers with other tags, if there is still none, return no available invokers
    val pairs3 = ContainerManager.schedule(
      healthyInvokers.takeRight(1),
      List(msg3, msg4),
      msg3.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs3 should contain theSameElementsAs List(
      ScheduledPair(msg3, Some(healthyInvokers(1).id)),
      ScheduledPair(msg4, None, Some(NoAvailableInvokersError)))
  }

  it should "send FailedCreationJob to memory queue when no invokers are available" in {
    val mockEtcd = mock[EtcdClient]
    val probe = TestProbe()
    (mockEtcd
      .getPrefix(_: String))
      .expects(InvokerKeys.prefix)
      .returning(Future.successful {
        RangeResponse.newBuilder().build()
      })
      .twice()

    val fqn = FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction))

    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, fqn.toDocId.asDocInfo(testRevision)),
      MemoryQueueValue(probe.ref, true))

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    val msg =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        fqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    manager ! ContainerCreation(List(msg), testMemory, testInvocationNamespace)
    probe.expectMsg(
      FailedCreationJob(
        msg.creationId,
        testInvocationNamespace,
        msg.action,
        testRevision,
        NoAvailableInvokersError,
        NoAvailableInvokersError))
  }

  it should "send FailedCreationJob to memory queue when available invoker query fails" in {
    val mockEtcd = mock[EtcdClient]
    val probe = TestProbe()
    (mockEtcd
      .getPrefix(_: String))
      .expects(InvokerKeys.prefix)
      .returning(Future.failed(new Exception("etcd request failed.")))
      .twice()

    val fqn = FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction))

    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, fqn.toDocId.asDocInfo(testRevision)),
      MemoryQueueValue(probe.ref, true))

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    val msg =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        fqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    manager ! ContainerCreation(List(msg), testMemory, testInvocationNamespace)
    probe.expectMsg(
      FailedCreationJob(
        msg.creationId,
        testInvocationNamespace,
        msg.action,
        testRevision,
        NoAvailableInvokersError,
        NoAvailableInvokersError))
  }

  it should "schedule to the blackbox invoker when isBlackboxInvocation is true" in {
    stream.reset()
    val mockEtcd = mock[EtcdClient]
    (mockEtcd
      .getPrefix(_: String))
      .expects(*)
      .returning(Future.successful {
        RangeResponse.newBuilder().build()
      })
    expectGetInvokers(mockEtcd)

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()

    val manager =
      system.actorOf(
        ContainerManager.props(factory(mockJobManager), mockMessaging(), testsid, mockEtcd, config, mockWatcher.ref))

    val exec = BlackBoxExec(ExecManifest.ImageName("image"), None, None, native = false, binary = false)
    val action = ExecutableWhiskAction(EntityPath(testNamespace), EntityName(testAction), exec)
    val execMetadata = BlackBoxExecMetaData(exec.image, exec.entryPoint, exec.native, exec.binary)
    val actionMetadata =
      WhiskActionMetaData(
        action.namespace,
        action.name,
        execMetadata,
        action.parameters,
        action.limits,
        action.version,
        action.publish,
        action.annotations)

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val msgs = List(msg1)
    val creationMsg = ContainerCreation(msgs, testMemory, testInvocationNamespace)

    manager ! creationMsg

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg1`) => true
    }

    Thread.sleep(1000)

    // blackbox invoker number = 10 * 0.1 = 1, so the last blackbox invoker will be scheduled
    // Because the debugging invoker is excluded, it sends a message to invoker9.
    stream.toString should include(s"posting to invoker9")
  }

  it should "delete container" in {
    val mockEtcd = mock[EtcdClient]
    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    )
    val fqn = FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction))

    expectGetInvokers(mockEtcd, invokers)

    // both warmed and existing containers are in all invokers.
    (mockEtcd
      .getPrefix(_: String))
      .expects(containerPrefix(ContainerKeys.namespacePrefix, testInvocationNamespace, fqn))
      .returning(Future.successful {
        invokers
          .foldLeft(RangeResponse.newBuilder()) { (builder, invoker) =>
            builder.addKvs(
              KeyValue
                .newBuilder()
                .setKey(
                  ContainerKeys.existingContainers(
                    testInvocationNamespace,
                    fqn,
                    testRevision,
                    Some(invoker.id),
                    Some(ContainerId("testContainer"))))
                .build())
          }
          .build()
      })

    (mockEtcd
      .getPrefix(_: String))
      .expects(containerPrefix(ContainerKeys.warmedPrefix, testInvocationNamespace, fqn))
      .returning(Future.successful {
        invokers
          .foldLeft(RangeResponse.newBuilder()) { (builder, invoker) =>
            builder.addKvs(KeyValue
              .newBuilder()
              .setKey(ContainerKeys
                .warmedContainers(testInvocationNamespace, fqn, testRevision, invoker.id, ContainerId("testContainer")))
              .build())
          }
          .build()
      })

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    val msg = ContainerDeletionMessage(
      TransactionId.containerDeletion,
      testInvocationNamespace,
      fqn,
      testRevision,
      actionMetadata)
    val deletionMessage = ContainerDeletion(testInvocationNamespace, fqn, testRevision, actionMetadata)

    manager ! deletionMessage

    val expectedMsgs = invokers.map(i => s"invoker${i.id.instance}-$msg")

    receiver.expectMsgPF() {
      case msg: String if msg.contains("warmUp") => true
      case msg: String                           => expectedMsgs.contains(msg)
      case msg =>
        println(s"unexpected message: $msg")
        fail()
    }
  }

  it should "allow managed partition to overlap with blackbox for small N" in {
    Seq((0.1, 0.9), (0.2, 0.8), (0.3, 0.7), (0.4, 0.6), (0.5, 0.5)).foreach { fraction =>
      val blackboxFraction = fraction._1
      val managedFraction = fraction._2

      (1 to 100).toSeq.foreach { i =>
        val m = Math.max(1, Math.ceil(i.toDouble * managedFraction).toInt)
        val b = Math.max(1, Math.floor(i.toDouble * blackboxFraction).toInt)

        m should be <= i
        b shouldBe Math.max(1, (blackboxFraction * i).toInt)

        blackboxFraction match {
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

  it should "return the same pools if managed- and blackbox-pools are overlapping" in {
    val blackboxFraction = 1.0
    val managedFraction = 1.0
    val totalInvokerSize = 100
    var result = mutable.Buffer[InvokerHealth]()
    (1 to totalInvokerSize).foreach { i =>
      result = result :+ InvokerHealth(InvokerInstanceId(i, userMemory = 256.MB), Healthy)
    }

    val m = Math.max(1, Math.ceil(totalInvokerSize.toDouble * managedFraction).toInt)
    val b = Math.max(1, Math.floor(totalInvokerSize.toDouble * blackboxFraction).toInt)

    m shouldBe totalInvokerSize
    b shouldBe totalInvokerSize

    result.take(m) shouldBe result.takeRight(b)
  }

  behavior of "warm up"

  it should "warm up all invokers when start" in {
    val mockEtcd = mock[EtcdClient]

    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    )
    expectGetInvokers(mockEtcd, invokers)

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    (0 to 2).foreach(i => {
      receiver.expectMsgPF() {
        case msg: String if msg.contains("warmUp") && msg.contains(s"invoker$i") => true
        case msg                                                                 => false
      }
    })
  }

  it should "warm up new invoker when new one is registered" in {
    val mockEtcd = mock[EtcdClient]
    expectGetInvokers(mockEtcd, List.empty)

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    manager ! WatchEndpointInserted(
      InvokerKeys.prefix,
      InvokerKeys.health(InvokerInstanceId(0, userMemory = 128.MB)),
      "",
      true)
    receiver.expectMsgPF() {
      case msg: String if msg.contains("warmUp") && msg.contains(s"invoker0") => true
      case _                                                                  => false
    }

    // shouldn't warmup again
    manager ! WatchEndpointInserted(
      InvokerKeys.prefix,
      InvokerKeys.health(InvokerInstanceId(0, userMemory = 128.MB)),
      "",
      true)
    receiver.expectNoMessage()

    // should warmup again since invoker0 is a new one
    manager ! WatchEndpointRemoved(
      InvokerKeys.prefix,
      InvokerKeys.health(InvokerInstanceId(0, userMemory = 128.MB)),
      "",
      true)
    manager ! WatchEndpointInserted(
      InvokerKeys.prefix,
      InvokerKeys.health(InvokerInstanceId(0, userMemory = 128.MB)),
      "",
      true)
    receiver.expectMsgPF() {
      case msg: String if msg.contains("warmUp") && msg.contains(s"invoker0") => true
      case _                                                                  => false
    }
  }

  it should "choose an invoker from candidates" in {
    val candidates = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 128 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 128 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 256 MB), Healthy),
    )
    val msg = ContainerCreationMessage(
      TransactionId.testing,
      testInvocationNamespace,
      FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
      testRevision,
      actionMetadata,
      testsid,
      schedulerHost,
      rpcPort)

    // no matter how many time we schedule the msg, it should always choose invoker2.
    (1 to 10).foreach { _ =>
      val newPairs = ContainerManager.chooseInvokerFromCandidates(candidates, msg)
      newPairs.invokerId shouldBe Some(InvokerInstanceId(2, userMemory = 256 MB))
    }
  }

  it should "not choose an invoker when there is no candidate with enough memory" in {
    val candidates = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 128 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 128 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 128 MB), Healthy),
    )
    val msg = ContainerCreationMessage(
      TransactionId.testing,
      testInvocationNamespace,
      FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
      testRevision,
      actionMetadata,
      testsid,
      schedulerHost,
      rpcPort)

    // no matter how many time we schedule the msg, no invoker should be assigned.
    (1 to 10).foreach { _ =>
      val newPairs = ContainerManager.chooseInvokerFromCandidates(candidates, msg)
      newPairs.invokerId shouldBe None
    }
  }

  it should "not choose an invoker when there is no candidate" in {
    val candidates = List()
    val msg = ContainerCreationMessage(
      TransactionId.testing,
      testInvocationNamespace,
      FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
      testRevision,
      actionMetadata,
      testsid,
      schedulerHost,
      rpcPort)

    val newPairs = ContainerManager.chooseInvokerFromCandidates(candidates, msg)
    newPairs.invokerId shouldBe None
  }

  it should "update invoker memory" in {
    val invokers = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024 MB), Healthy),
    )
    val expected = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 768 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024 MB), Healthy),
    )
    val requiredMemory = 256.MB.toMB
    val invokerId = Some(InvokerInstanceId(1, userMemory = 1024 MB))

    val updatedInvokers = ContainerManager.updateInvokerMemory(invokerId, requiredMemory, invokers)

    updatedInvokers shouldBe expected
  }

  it should "not update invoker memory when no invoker is assigned" in {
    val invokers = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024 MB), Healthy),
    )
    val requiredMemory = 256.MB.toMB

    val updatedInvokers = ContainerManager.updateInvokerMemory(None, requiredMemory, invokers)

    updatedInvokers shouldBe invokers
  }

  it should "drop an invoker with less memory than MIN_MEMORY" in {
    val invokers = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = 320 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024 MB), Healthy),
    )
    val expected = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = 1024 MB), Healthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024 MB), Healthy),
    )
    val requiredMemory = 256.MB.toMB
    val invokerId = Some(InvokerInstanceId(1, userMemory = 320 MB))

    val updatedInvokers = ContainerManager.updateInvokerMemory(invokerId, requiredMemory, invokers)

    updatedInvokers shouldBe expected
  }

  it should "filter warmed creations when there is no warmed container" in {

    val warmedContainers = Set.empty[String]
    val inProgressWarmedContainers = TrieMap.empty[String, String]

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns1"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns3"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        FullyQualifiedEntityName(EntityPath("ns3"), EntityName(testAction)),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val msgs = List(msg1, msg2, msg3)

    val (coldCreations, warmedCreations) =
      ContainerManager.filterWarmedCreations(warmedContainers, inProgressWarmedContainers, invokers, msgs)

    warmedCreations.isEmpty shouldBe true
    coldCreations.size shouldBe 3
  }

  it should "filter warmed creations when there are warmed containers" in {
    val warmedContainers = Set(
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        testRevision,
        InvokerInstanceId(0, userMemory = 0.bytes),
        ContainerId("fake")),
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        InvokerInstanceId(1, userMemory = 0.bytes),
        ContainerId("fake")))
    val inProgressWarmedContainers = TrieMap.empty[String, String]

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val msgs = List(msg1, msg2, msg3)

    val (coldCreations, warmedCreations) =
      ContainerManager.filterWarmedCreations(warmedContainers, inProgressWarmedContainers, invokers, msgs)

    warmedCreations.size shouldBe 2
    coldCreations.size shouldBe 1

    warmedCreations.map(_._1).contains(msg1) shouldBe true
    warmedCreations.map(_._1).contains(msg2) shouldBe true
    coldCreations.map(_._1).contains(msg3) shouldBe true
  }

  it should "choose cold creation when warmed containers are in disabled invokers" in {
    val warmedContainers = Set(
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn,
        testRevision,
        InvokerInstanceId(0, userMemory = 0.bytes),
        ContainerId("fake")),
      ContainerKeys.warmedContainers(
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        InvokerInstanceId(1, userMemory = 0.bytes),
        ContainerId("fake")))
    val inProgressWarmedContainers = TrieMap.empty[String, String]

    val msg1 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg2 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn.copy(name = EntityName("test-action-2")),
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)
    val msg3 =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        testfqn,
        testRevision,
        actionMetadata,
        testsid,
        schedulerHost,
        rpcPort)

    val msgs = List(msg1, msg2, msg3)

    // unhealthy invokers should not be chosen even if they have warmed containers
    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = 1024.MB, tags = Seq.empty[String]), Healthy))

    val (coldCreations, _) =
      ContainerManager.filterWarmedCreations(warmedContainers, inProgressWarmedContainers, invokers, msgs)

    coldCreations.size shouldBe 3
    coldCreations.map(_._1).containsSlice(List(msg1, msg2, msg3)) shouldBe true
  }
}

@RunWith(classOf[JUnitRunner])
class ContainerManager2Tests
    extends FlatSpecLike
    with Matchers
    with StreamLogging
    with ExecHelpers
    with MockFactory
    with ScalaFutures
    with WskActorSystem
    with BeforeAndAfterEach
    with DbUtils {

  implicit val dispatcher = actorSystem.dispatcher
  val etcdClient = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd))
  val testInvocationNamespace = "test-invocation-namespace"

  override def afterAll(): Unit = {
    etcdClient.close()
    super.afterAll()
  }

  it should "load invoker from specified clusterName only" in {
    val clusterName1 = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)
    val clusterName2 = "clusterName2"
    val invokerResourceMessage =
      InvokerResourceMessage(Healthy.asString, 1024, 0, 0, Seq.empty[String], Seq.empty[String])
    etcdClient.put(s"${clusterName1}/invokers/0", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName1}/invokers/1", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName1}/invokers/2", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName2}/invokers/3", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName2}/invokers/4", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName2}/invokers/5", invokerResourceMessage.serialize)
    // Make sure store above data in etcd
    Thread.sleep(5.seconds.toMillis)
    ContainerManager.getAvailableInvokers(etcdClient, 0.MB, testInvocationNamespace).map { invokers =>
      invokers.length shouldBe 3
      invokers.foreach { invokerHealth =>
        List(0, 1, 2) should contain(invokerHealth.id.instance)
      }
    }
    // Delete etcd data finally
    List(
      s"${clusterName1}/invokers/0",
      s"${clusterName1}/invokers/1",
      s"${clusterName1}/invokers/2",
      s"${clusterName2}/invokers/3",
      s"${clusterName2}/invokers/4",
      s"${clusterName2}/invokers/5").foreach(etcdClient.del(_))
  }

  it should "load invoker from specified invocation namespace only" in {
    val clusterName = loadConfigOrThrow[String](ConfigKeys.whiskClusterName)
    val invokerResourceMessage =
      InvokerResourceMessage(Healthy.asString, 1024, 0, 0, Seq.empty[String], Seq.empty[String])
    val invokerResourceMessage2 =
      InvokerResourceMessage(Healthy.asString, 1024, 0, 0, Seq.empty[String], Seq(testInvocationNamespace))
    etcdClient.put(s"${clusterName}/invokers/0", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName}/invokers/1", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName}/invokers/2", invokerResourceMessage.serialize)
    etcdClient.put(s"${clusterName}/invokers/3", invokerResourceMessage2.serialize)
    etcdClient.put(s"${clusterName}/invokers/4", invokerResourceMessage2.serialize)
    etcdClient.put(s"${clusterName}/invokers/5", invokerResourceMessage2.serialize)
    // Make sure store above data in etcd
    Thread.sleep(5.seconds.toMillis)
    ContainerManager.getAvailableInvokers(etcdClient, 0.MB, testInvocationNamespace).map { invokers =>
      invokers.length shouldBe 6
      invokers.foreach { invokerHealth =>
        List(0, 1, 2, 3, 4, 5) should contain(invokerHealth.id.instance)
      }
    }

    // this new namespace should not use invoker3/4/5
    ContainerManager.getAvailableInvokers(etcdClient, 0.MB, "new-namespace").map { invokers =>
      invokers.length shouldBe 3
      invokers.foreach { invokerHealth =>
        List(0, 1, 2) should contain(invokerHealth.id.instance)
      }
    }
    // Delete etcd data finally
    List(
      s"${clusterName}/invokers/0",
      s"${clusterName}/invokers/1",
      s"${clusterName}/invokers/2",
      s"${clusterName}/invokers/3",
      s"${clusterName}/invokers/4",
      s"${clusterName}/invokers/5").foreach(etcdClient.del(_))
  }
}
