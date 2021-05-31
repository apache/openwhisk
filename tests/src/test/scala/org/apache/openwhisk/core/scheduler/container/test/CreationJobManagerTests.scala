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

import java.util.concurrent.TimeUnit
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.ibm.etcd.client.{EtcdClient => Client}
import common.StreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.inProgressContainer
import org.apache.openwhisk.core.scheduler.container._
import org.apache.openwhisk.core.scheduler.message._
import org.apache.openwhisk.core.scheduler.queue.{MemoryQueueKey, MemoryQueueValue, QueuePool}
import org.apache.openwhisk.core.service.{RegisterData, UnregisterData}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContextExecutor, Future}

@RunWith(classOf[JUnitRunner])
class CreationJobManagerTests
    extends TestKit(ActorSystem("CreationJobManager"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with StreamLogging {

  private val timeout = loadConfigOrThrow[FiniteDuration](ConfigKeys.schedulerInProgressJobRetentionSecond)
  val blackboxTimeout = FiniteDuration(timeout.toSeconds * 3, TimeUnit.SECONDS)
  implicit val ece: ExecutionContextExecutor = system.dispatcher
  val config = new WhiskConfig(ExecManifest.requiredProperties)
  val creationIdTest = CreationId.generate()
  val isBlackboxInvocation = false

  val testInvocationNamespace = "test-invocation-namespace"
  val testNamespace = "test-namespace"
  val testAction = "test-action"
  val schedulerHost = "127.17.0.1"
  val rpcPort = 13001
  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val execAction = ExecutableWhiskAction(EntityPath(testNamespace), EntityName(testAction), exec)
  val execMetadata =
    CodeExecMetaDataAsString(RuntimeManifest(execAction.exec.kind, ImageName("test")), entryPoint = Some("test"))
  val revision = DocRevision("1-testRev")
  val actionMetadata =
    WhiskActionMetaData(
      execAction.namespace,
      execAction.name,
      execMetadata,
      execAction.parameters,
      execAction.limits,
      execAction.version,
      execAction.publish,
      execAction.annotations)

  override def afterAll(): Unit = {
    client.close()
    QueuePool.clear()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override def beforeEach(): Unit = {
    QueuePool.clear()
  }

  def feedFactory(actorRefFactory: ActorRefFactory,
                  topic: String,
                  maxActiveAcksPerPoll: Int,
                  handler: Array[Byte] => Future[Unit]): ActorRef = {
    TestProbe().ref
  }

  def createRegisterMessage(action: FullyQualifiedEntityName,
                            revision: DocRevision,
                            sid: SchedulerInstanceId): RegisterCreationJob = {
    val message =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        action,
        revision,
        actionMetadata,
        sid,
        schedulerHost,
        rpcPort,
        creationId = creationIdTest)
    RegisterCreationJob(message)
  }

  val action = FullyQualifiedEntityName(EntityPath("test namespace"), EntityName("actionName"))
  val sid = SchedulerInstanceId("0")
  val iid = InvokerInstanceId(0, userMemory = 1024.MB)
  val testKey = inProgressContainer(testInvocationNamespace, action, revision, sid, creationIdTest)
  val memory = 256.MB
  val resources = Seq.empty[String]
  val resourcesStrictPolicy = true

  val registerMessage = createRegisterMessage(action, revision, sid)

  val client: Client = {
    val hostAndPorts = "172.17.0.1:2379"
    Client.forEndpoints(hostAndPorts).withPlainText().build()
  }

  behavior of "CreationJobManager"

  it should "register creation job" in {
    val probe = TestProbe()

    val manager =
      system.actorOf(CreationJobManager.props(feedFactory, sid, probe.ref))

    manager ! registerMessage

    probe.expectMsg(RegisterData(testKey, "", failoverEnabled = false))
  }

  it should "skip duplicated creation job" in {
    val probe = TestProbe()

    val manager =
      system.actorOf(CreationJobManager.props(feedFactory, sid, probe.ref))

    manager ! registerMessage
    manager ! registerMessage

    probe.expectMsg(RegisterData(testKey, "", failoverEnabled = false))
    probe.expectNoMessage()
  }

  def createFinishMessage(action: FullyQualifiedEntityName,
                          revision: DocRevision,
                          memory: ByteSize,
                          invokerInstanceId: InvokerInstanceId,
                          retryCount: Int = 0,
                          error: Option[ContainerCreationError] = None): FinishCreationJob = {
    val message =
      ContainerCreationAckMessage(
        TransactionId.testing,
        creationIdTest,
        testInvocationNamespace,
        action,
        revision,
        actionMetadata,
        invokerInstanceId,
        schedulerHost,
        rpcPort,
        retryCount,
        error)
    FinishCreationJob(message)
  }

  def createRescheduling(finishMsg: FinishCreationJob): ReschedulingCreationJob =
    ReschedulingCreationJob(
      finishMsg.ack.transid,
      finishMsg.ack.creationId,
      finishMsg.ack.invocationNamespace,
      finishMsg.ack.action,
      finishMsg.ack.revision,
      actionMetadata,
      finishMsg.ack.schedulerHost,
      finishMsg.ack.rpcPort,
      finishMsg.ack.retryCount)

  val normalFinish = createFinishMessage(action, revision, memory, iid, retryCount = 0)
  val failedFinish =
    createFinishMessage(action, revision, memory, iid, retryCount = 0, Some(ContainerCreationError.UnknownError))
  val unrescheduleFinish =
    createFinishMessage(action, revision, memory, iid, retryCount = 0, Some(ContainerCreationError.BlackBoxError))
  val tooManyFinish =
    createFinishMessage(action, revision, memory, iid, retryCount = 100, Some(ContainerCreationError.UnknownError))

  it should "delete a creation job normally and send a SuccessfulCreationJob to a queue" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, action.toDocId.asDocInfo(revision)),
      MemoryQueueValue(probe.ref, true))
    jobManager ! registerMessage

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    jobManager ! normalFinish

    dataManagementService.expectMsg(UnregisterData(testKey))

    containerManager.expectMsg(
      SuccessfulCreationJob(
        normalFinish.ack.creationId,
        normalFinish.ack.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision))
    probe.expectMsg(
      SuccessfulCreationJob(
        normalFinish.ack.creationId,
        normalFinish.ack.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision))
  }

  it should "only delete a creation job with failed msg after all retries are failed" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()

    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    jobManager ! registerMessage

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    jobManager ! failedFinish

    containerManager.expectMsg(createRescheduling(failedFinish))

    jobManager ! failedFinish.copy(ack = failedFinish.ack.copy(retryCount = 5))
    dataManagementService.expectMsg(UnregisterData(testKey))
  }

  it should "delete a creation job with failed msg and send a FailedCreationJob to a queue" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, action.toDocId.asDocInfo(revision)),
      MemoryQueueValue(probe.ref, true))

    jobManager ! registerMessage

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    jobManager ! unrescheduleFinish

    dataManagementService.expectMsg(UnregisterData(testKey))

    containerManager.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.BlackBoxError,
        "unknown reason"))
    probe.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.BlackBoxError,
        "unknown reason"))
  }

  it should "delete a creation job that does not exist with failed msg" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()

    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    jobManager ! failedFinish.copy(ack = failedFinish.ack.copy(retryCount = 5))

    dataManagementService.expectMsg(UnregisterData(testKey))
  }

  it should "delete a creation job with timeout" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()

    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    jobManager ! registerMessage

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    Thread.sleep(timeout.toMillis) // sleep 5s to wait for the timeout handler to be executed
    dataManagementService.expectMsg(UnregisterData(testKey))
    containerManager.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.TimeoutError,
        s"timeout waiting for the ack of ${registerMessage.msg.creationId} after $timeout"))
  }

  it should "increase the timeout if an action is a blackbox action" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()

    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))

    val execMetadata =
      BlackBoxExecMetaData(ImageName("test image"), Some("main"), native = false);

    val actionMetaData = WhiskActionMetaData(
      execAction.namespace,
      execAction.name,
      execMetadata,
      execAction.parameters,
      execAction.limits,
      execAction.version,
      execAction.publish,
      execAction.annotations)

    val message =
      ContainerCreationMessage(
        TransactionId.testing,
        testInvocationNamespace,
        action,
        revision,
        actionMetaData,
        sid,
        schedulerHost,
        rpcPort,
        creationId = creationIdTest)
    val creationMsg = RegisterCreationJob(message)

    jobManager ! creationMsg

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    // no message for timeout
    dataManagementService.expectNoMessage(timeout)
    Thread.sleep(timeout.toMillis * 2) // timeout is doubled for blackbox actions
    dataManagementService.expectMsg(UnregisterData(testKey))
    containerManager.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.TimeoutError,
        s"timeout waiting for the ack of ${registerMessage.msg.creationId} after $blackboxTimeout"))
  }

  it should "delete a creation job with too many retry and send a FailedCreationJob to a queue" in {
    val containerManager = TestProbe()
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val jobManager =
      containerManager.childActorOf(CreationJobManager.props(feedFactory, sid, dataManagementService.ref))
    QueuePool.put(
      MemoryQueueKey(testInvocationNamespace, action.toDocId.asDocInfo(revision)),
      MemoryQueueValue(probe.ref, true))

    jobManager ! registerMessage

    dataManagementService.expectMsg(RegisterData(testKey, "", failoverEnabled = false))

    jobManager ! tooManyFinish

    dataManagementService.expectMsg(UnregisterData(testKey))
    containerManager.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.UnknownError,
        "unknown reason"))
    probe.expectMsg(
      FailedCreationJob(
        registerMessage.msg.creationId,
        registerMessage.msg.invocationNamespace,
        registerMessage.msg.action,
        registerMessage.msg.revision,
        ContainerCreationError.UnknownError,
        "unknown reason"))
  }
}
