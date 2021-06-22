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
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
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
import org.apache.openwhisk.core.scheduler.container._
import org.apache.openwhisk.core.scheduler.message.{
  ContainerCreation,
  ContainerDeletion,
  FailedCreationJob,
  RegisterCreationJob,
  ReschedulingCreationJob,
  SuccessfulCreationJob
}
import org.apache.openwhisk.core.scheduler.queue.{MemoryQueueKey, MemoryQueueValue, QueuePool}
import org.apache.openwhisk.core.service.WatchEndpointInserted
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import pureconfig.loadConfigOrThrow
import spray.json.{JsArray, JsBoolean, JsString}
import pureconfig.generic.auto._

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
    val producer = receiver.map(fakeProducer(_)).getOrElse(stub[MessageProducer])
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
        .returns(Future.successful(new RecordMetadata(new TopicPartition("fake", 0), 0, 0, 0l, 0l, 0, 0)))
    }

    messaging
  }

  private def fakeProducer(receiver: ActorRef) = new MessageProducer {

    /** Count of messages sent. */
    override def sentCount(): Long = 0

    /** Sends msg to topic. This is an asynchronous operation. */
    override def send(topic: String, msg: Message, retry: Int): Future[RecordMetadata] = {
      val message = s"$topic-$msg"
      receiver ! message

      Future.successful(
        new RecordMetadata(new TopicPartition(topic, 0), -1, -1, System.currentTimeMillis(), null, -1, -1))
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
              invoker.id.userMemory.toMB,
              invoker.id.userMemory.toMB,
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

    // for test, only invoker2 is healthy, so that no-warmed creations can be only sent to invoker2
    val invokers: List[InvokerHealth] = List(
      InvokerHealth(InvokerInstanceId(0, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(1, userMemory = testMemory, tags = Seq.empty[String]), Unhealthy),
      InvokerHealth(InvokerInstanceId(2, userMemory = testMemory, tags = Seq.empty[String]), Healthy),
    )
    expectGetInvokers(mockEtcd, invokers)
    expectGetInvokers(mockEtcd, invokers)
    expectGetInvokers(mockEtcd, invokers) // this test case will run `getPrefix` twice

    val mockJobManager = TestProbe()
    val mockWatcher = TestProbe()
    val receiver = TestProbe()

    val manager =
      system.actorOf(ContainerManager
        .props(factory(mockJobManager), mockMessaging(Some(receiver.ref)), testsid, mockEtcd, config, mockWatcher.ref))

    // there are 1 warmed container for `test-namespace/test-action` and 1 for `test-namespace/test-action-2`
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
    manager ! ContainerCreation(msgs, 128.MB, testInvocationNamespace)

    // ignore warmUp message
    receiver.ignoreMsg {
      case s: String => s.contains("warmUp")
    }

    // msg1 will use warmed container on invoker0, msg2 use warmed container on invoker1, msg3 use the healthy invoker
    receiver.expectMsg(s"invoker0-$msg1")
    receiver.expectMsg(s"invoker1-$msg2")
    receiver.expectMsg(s"invoker2-$msg3")

    mockJobManager.expectMsgPF() {
      case RegisterCreationJob(`msg1`) => true
      case RegisterCreationJob(`msg2`) => true
      case RegisterCreationJob(`msg3`) => true
    }

    // now warmed container for action2 become warmed again
    manager ! SuccessfulCreationJob(msg2.creationId, msg2.invocationNamespace, msg2.action, msg2.revision)
    manager ! SuccessfulCreationJob(msg3.creationId, msg3.invocationNamespace, msg3.action, msg3.revision)
    // it still need to use invoker2
    manager ! ContainerCreation(List(msg1), 128.MB, testInvocationNamespace)
    receiver.expectMsg(s"invoker2-$msg1")
    // it will use warmed container on invoker1
    manager ! ContainerCreation(List(msg2), 128.MB, testInvocationNamespace)
    receiver.expectMsg(s"invoker1-$msg2")

    // warmed container for action1 become warmed
    manager ! SuccessfulCreationJob(msg1.creationId, msg1.invocationNamespace, msg1.action, msg1.revision)
    manager ! ContainerCreation(List(msg1), 128.MB, testInvocationNamespace)
    receiver.expectMsg(s"invoker0-$msg1")
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

  it should "choice invokers" in {
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
    pairs.map(_.invokerId).foreach {
      healthyInvokers.map(_.id) should contain(_)
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
    pairs.map(_.invokerId.instance).foreach {
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
      ScheduledPair(msg1, healthyInvokers(0).id),
      ScheduledPair(msg2, healthyInvokers(1).id),
      ScheduledPair(msg3, healthyInvokers(2).id),
      ScheduledPair(msg4, healthyInvokers(3).id))
    probe.expectMsg(
      FailedCreationJob(
        msg5.creationId,
        testInvocationNamespace,
        msg5.action,
        testRevision,
        NoAvailableResourceInvokersError,
        "No available invokers with resources List(fake)."))
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
          limits = action.limits.copy(memory = MemoryLimit(512.MB)),
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
        InvokerHealth(InvokerInstanceId(1, userMemory = 512.MB, tags = Seq("cpu", "memory")), Healthy))

    // while resourcesStrictPolicy is true, and there is no suitable invokers, return an error
    val pairs =
      ContainerManager.schedule(healthyInvokers, List(msg1), msg1.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs.size shouldBe 0
    probe.expectMsg(
      FailedCreationJob(
        msg1.creationId,
        testInvocationNamespace,
        msg1.action,
        testRevision,
        NoAvailableResourceInvokersError,
        "No available invokers with resources List(non-exist)."))

    // while resourcesStrictPolicy is false, and there is no suitable invokers, should choose no tagged invokers first,
    // here is the invoker0
    val pairs2 =
      ContainerManager.schedule(healthyInvokers, List(msg2), msg2.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs2 should contain theSameElementsAs List(ScheduledPair(msg2, healthyInvokers(0).id))

    // while resourcesStrictPolicy is false, and there is no suitable invokers, should choose no tagged invokers first,
    // if there is none, then choose other invokers, here is the invoker1
    val pairs3 = ContainerManager.schedule(
      healthyInvokers.takeRight(1),
      List(msg3),
      msg3.whiskActionMetaData.limits.memory.megabytes.MB)
    pairs3 should contain theSameElementsAs List(ScheduledPair(msg3, healthyInvokers(1).id))
  }

  it should "send FailedCreationJob to queue manager when no invokers are available" in {
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
        "No available invokers."))
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
  val etcdClient = EtcdClient(loadConfigOrThrow[EtcdConfig](ConfigKeys.etcd).hosts)
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
