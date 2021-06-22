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

package org.apache.openwhisk.core.invoker.test

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import common.StreamLogging
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.{WarmUp, WhiskConfig}
import org.apache.openwhisk.core.connector.ContainerCreationError._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.connector.test.TestConnector
import org.apache.openwhisk.core.containerpool.v2.CreationContainer
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.invoker.ContainerMessageConsumer
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.utils.{retry => utilRetry}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class ContainerMessageConsumerTests
    extends TestKit(ActorSystem("ContainerMessageConsumer"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with StreamLogging
    with MockFactory
    with DbUtils
    with ExecHelpers {

  implicit val actualActorSystem = system // Use system for duplicate system and actorSystem.
  implicit val ec = actualActorSystem.dispatcher
  implicit val transId = TransactionId.testing
  implicit val creationId = CreationId.generate()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private val whiskConfig = new WhiskConfig(
    Map(
      WhiskConfig.actionInvokePerMinuteLimit -> null,
      WhiskConfig.triggerFirePerMinuteLimit -> null,
      WhiskConfig.actionInvokeConcurrentLimit -> null,
      WhiskConfig.runtimesManifest -> null,
      WhiskConfig.actionSequenceMaxLimit -> null))

  private val entityStore = WhiskEntityStore.datastore()
  private val producer = stub[MessageProducer]

  private val defaultUserMemory: ByteSize = 1024.MB
  private val invokerInstance = InvokerInstanceId(0, userMemory = defaultUserMemory)
  private val schedulerInstanceId = SchedulerInstanceId("0")

  private val invocationNamespace = EntityName("invocationSpace")

  private val schedulerHost = "127.17.0.1"

  private val rpcPort = 13001

  override def afterEach(): Unit = {
    cleanup()
  }

  private def fakeMessageProvider(consumer: TestConnector): MessagingProvider = {
    new MessagingProvider {
      override def getConsumer(
        whiskConfig: WhiskConfig,
        groupId: String,
        topic: String,
        maxPeek: Int,
        maxPollInterval: FiniteDuration)(implicit logging: Logging, actorSystem: ActorSystem): MessageConsumer =
        consumer

      override def getProducer(config: WhiskConfig, maxRequestSize: Option[ByteSize])(
        implicit logging: Logging,
        actorSystem: ActorSystem): MessageProducer = consumer.getProducer()

      override def ensureTopic(config: WhiskConfig,
                               topic: String,
                               topicConfig: String,
                               maxMessageBytes: Option[ByteSize])(implicit logging: Logging): Try[Unit] = Try {}
    }
  }

  def sendAckToScheduler(producer: MessageProducer)(schedulerInstanceId: SchedulerInstanceId,
                                                    ackMessage: ContainerCreationAckMessage): Future[RecordMetadata] = {
    val topic = s"creationAck${schedulerInstanceId.asString}"
    producer.send(topic, ackMessage)
  }

  private def createAckMsg(creationMessage: ContainerCreationMessage,
                           error: Option[ContainerCreationError],
                           reason: Option[String]) = {
    ContainerCreationAckMessage(
      creationMessage.transid,
      creationMessage.creationId,
      creationMessage.invocationNamespace,
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

  it should "forward ContainerCreationMessage to containerPool" in {
    val pool = TestProbe()
    val mockConsumer = new TestConnector("fakeTopic", 4, true)
    val msgProvider = fakeMessageProvider(mockConsumer)

    val consumer =
      new ContainerMessageConsumer(
        invokerInstance,
        pool.ref,
        entityStore,
        whiskConfig,
        msgProvider,
        200.milliseconds,
        500,
        sendAckToScheduler(producer))

    val exec = CodeExecAsString(RuntimeManifest("nodejs:10", ImageName("testImage")), "testCode", None)
    val action =
      WhiskAction(EntityPath("testns"), EntityName("testAction"), exec, limits = ActionLimits(TimeLimit(1.minute)))
    put(entityStore, action)
    val execMetadata =
      CodeExecMetaDataAsString(exec.manifest, entryPoint = exec.entryPoint)
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

    val msg =
      ContainerCreationMessage(
        transId,
        invocationNamespace.asString,
        action.fullyQualifiedName(true),
        DocRevision.empty,
        actionMetadata,
        schedulerInstanceId,
        schedulerHost,
        rpcPort,
        creationId = creationId)

    mockConsumer.send(msg)

    pool.expectMsgPF() {
      case CreationContainer(_, _) => true
    }
  }

  it should "send ack(failed) to scheduler when failed to get action from DB " in {
    val pool = TestProbe()
    val creationConsumer = new TestConnector("creation", 4, true)
    val msgProvider = fakeMessageProvider(creationConsumer)

    val ackTopic = "ack"
    val ackConsumer = new TestConnector(ackTopic, 4, true)

    val consumer =
      new ContainerMessageConsumer(
        invokerInstance,
        pool.ref,
        entityStore,
        whiskConfig,
        msgProvider,
        200.milliseconds,
        500,
        sendAckToScheduler(ackConsumer.getProducer()))

    val exec = CodeExecAsString(RuntimeManifest("nodejs:10", ImageName("testImage")), "testCode", None)
    val whiskAction =
      WhiskAction(EntityPath("testns"), EntityName("testAction2"), exec, limits = ActionLimits(TimeLimit(1.minute)))
    val execMetadata =
      CodeExecMetaDataAsString(exec.manifest, entryPoint = exec.entryPoint)
    val actionMetadata =
      WhiskActionMetaData(
        whiskAction.namespace,
        whiskAction.name,
        execMetadata,
        whiskAction.parameters,
        whiskAction.limits,
        whiskAction.version,
        whiskAction.publish,
        whiskAction.annotations)

    val creationMessage =
      ContainerCreationMessage(
        transId,
        invocationNamespace.asString,
        whiskAction.fullyQualifiedName(true),
        DocRevision.empty,
        actionMetadata,
        schedulerInstanceId,
        schedulerHost,
        rpcPort,
        creationId = creationId)

    // action doesn't exist
    val ackMessage = createAckMsg(creationMessage, Some(DBFetchError), Some(Messages.actionRemovedWhileInvoking))
    creationConsumer.send(creationMessage)

    within(5.seconds) {
      utilRetry({
        val buffer = ackConsumer.peek(50.millisecond)
        buffer.size shouldBe 1
        buffer.head._1 shouldBe ackTopic
        new String(buffer.head._4, StandardCharsets.UTF_8) shouldBe ackMessage.serialize
      }, 10, Some(500.millisecond))
      pool.expectNoMessage(2.seconds)
    }

    // action exist but version mismatch
    put(entityStore, whiskAction)
    val actualCreationMessage = creationMessage.copy(revision = DocRevision("1-fake"))
    val fetchErrorAckMessage =
      createAckMsg(actualCreationMessage, Some(DBFetchError), Some(Messages.actionFetchErrorWhileInvoking))
    creationConsumer.send(actualCreationMessage)

    within(5.seconds) {
      utilRetry({
        val buffer2 = ackConsumer.peek(50.millisecond)
        buffer2.size shouldBe 1
        buffer2.head._1 shouldBe ackTopic
        new String(buffer2.head._4, StandardCharsets.UTF_8) shouldBe fetchErrorAckMessage.serialize
      }, 10, Some(500.millisecond))
      pool.expectNoMessage(2.seconds)
    }
  }

  it should "drop messages of warm-up action" in {
    val pool = TestProbe()
    val mockConsumer = new TestConnector("fakeTopic", 4, true)
    val msgProvider = fakeMessageProvider(mockConsumer)

    val consumer =
      new ContainerMessageConsumer(
        invokerInstance,
        pool.ref,
        entityStore,
        whiskConfig,
        msgProvider,
        200.milliseconds,
        500,
        sendAckToScheduler(producer))

    val exec = CodeExecAsString(RuntimeManifest("nodejs:10", ImageName("testImage")), "testCode", None)
    val action =
      WhiskAction(
        WarmUp.warmUpAction.namespace.toPath,
        WarmUp.warmUpAction.name,
        exec,
        limits = ActionLimits(TimeLimit(1.minute)))
    val doc = put(entityStore, action)
    val execMetadata =
      CodeExecMetaDataAsString(exec.manifest, entryPoint = exec.entryPoint)

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

    val msg =
      ContainerCreationMessage(
        transId,
        invocationNamespace.asString,
        action.fullyQualifiedName(false),
        DocRevision.empty,
        actionMetadata,
        schedulerInstanceId,
        schedulerHost,
        rpcPort,
        creationId = creationId)

    mockConsumer.send(msg)

    pool.expectNoMessage(1.seconds)
  }
}
