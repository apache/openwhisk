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

package org.apache.openwhisk.core.scheduler.grpc.test

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import common.StreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WarmUp.warmUpAction
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.scheduler.grpc.ActivationServiceImpl
import org.apache.openwhisk.core.scheduler.queue.{
  ActionMismatch,
  MemoryQueueKey,
  MemoryQueueValue,
  NoActivationMessage,
  NoMemoryQueue,
  QueuePool
}
import org.apache.openwhisk.grpc.{FetchRequest, FetchResponse}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}
import org.apache.openwhisk.core.scheduler.grpc.{ActivationResponse, GetActivation}
import org.scalatest.concurrent.ScalaFutures
import spray.json.JsonParser.ParsingException

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ActivationServiceImplTests
    extends TestKit(ActorSystem("ActivationService"))
    with CommonVariable
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with StreamLogging {

  override def afterAll = {
    QueuePool.clear()
    TestKit.shutdownActorSystem(system)
  }
  override def beforeEach = QueuePool.clear()

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  behavior of "ActivationService"

  implicit val ec = system.dispatcher

  val messageTransId = TransactionId(TransactionId.testing.meta.id)
  val uuid = UUID()

  val testDoc = testFQN.toDocId.asDocInfo(testDocRevision)
  val message = ActivationMessage(
    messageTransId,
    FullyQualifiedEntityName(testEntityPath, testEntityName),
    DocRevision.empty,
    Identity(
      Subject(),
      Namespace(EntityName(testNamespace), uuid),
      BasicAuthenticationAuthKey(uuid, Secret()),
      Set.empty),
    ActivationId.generate(),
    ControllerInstanceId("0"),
    blocking = false,
    content = None)

  it should "delegate the FetchRequest to a MemoryQueue" in {

    val mock = system.actorOf(Props(new Actor() {
      override def receive: Receive = {
        case getActivation: GetActivation =>
          testActor ! getActivation
          sender() ! ActivationResponse(Right(message))
      }
    }))

    QueuePool.put(MemoryQueueKey(testEntityPath.asString, testDoc), MemoryQueueValue(mock, true))
    val activationServiceImpl = ActivationServiceImpl()

    val tid = TransactionId(TransactionId.generateTid())
    activationServiceImpl
      .fetchActivation(
        FetchRequest(
          tid.serialize,
          message.user.namespace.name.asString,
          testFQN.serialize,
          testDocRevision.serialize,
          testContainerId,
          false,
          alive = true))
      .futureValue shouldBe FetchResponse(ActivationResponse(Right(message)).serialize)

    expectMsg(GetActivation(tid, testFQN, testContainerId, false, None))
  }

  it should "return without any retry if there is no such queue" in {
    val activationServiceImpl = ActivationServiceImpl()

    activationServiceImpl
      .fetchActivation(
        FetchRequest(
          TransactionId(TransactionId.generateTid()).serialize,
          message.user.namespace.name.asString,
          testFQN.serialize,
          testDocRevision.serialize,
          testContainerId,
          false,
          alive = true))
      .futureValue shouldBe FetchResponse(ActivationResponse(Left(NoMemoryQueue())).serialize)

    expectNoMessage(200.millis)
  }

  it should "return ActionMismatchError if get request for an old action" in {

    val activationServiceImpl = ActivationServiceImpl()

    QueuePool.put(MemoryQueueKey(testEntityPath.asString, testDoc), MemoryQueueValue(testActor, true))

    activationServiceImpl
      .fetchActivation(
        FetchRequest( // same doc id but with a different doc revision
          TransactionId(TransactionId.generateTid()).serialize,
          message.user.namespace.name.asString,
          testFQN.serialize,
          DocRevision("new-one").serialize,
          testContainerId,
          false,
          alive = true))
      .futureValue shouldBe FetchResponse(ActivationResponse(Left(ActionMismatch())).serialize)

    expectNoMessage(200.millis)
  }

  it should "return NoActivationMessage if it is a warm-up action" in {

    val activationServiceImpl = ActivationServiceImpl()

    QueuePool.put(MemoryQueueKey(testEntityPath.asString, testDoc), MemoryQueueValue(testActor, true))

    activationServiceImpl
      .fetchActivation(
        FetchRequest(
          TransactionId(TransactionId.generateTid()).serialize,
          message.user.namespace.name.asString,
          warmUpAction.serialize,
          testDocRevision.serialize,
          testContainerId,
          false,
          alive = true))
      .futureValue shouldBe FetchResponse(ActivationResponse(Left(NoActivationMessage())).serialize)

    expectNoMessage(200.millis)
  }

  it should "throw parsing error if fqn can't be parsed" in {
    val notParsableFqn = "aaaaaaaaa"

    val activationServiceImpl = ActivationServiceImpl()

    QueuePool.put(MemoryQueueKey(testEntityPath.asString, testDoc), MemoryQueueValue(testActor, true))

    a[ParsingException] should be thrownBy await {
      activationServiceImpl
        .fetchActivation(
          FetchRequest(
            TransactionId(TransactionId.generateTid()).serialize,
            message.user.namespace.name.asString,
            notParsableFqn,
            testDocRevision.serialize,
            testContainerId,
            false,
            alive = true))
    }
  }

  it should "throw parsing error if rev can't be parsed" in {
    val notParsableRev = "aaaaaaaaa"

    val activationServiceImpl = ActivationServiceImpl()

    QueuePool.put(MemoryQueueKey(testEntityPath.asString, testDoc), MemoryQueueValue(testActor, true))

    a[ParsingException] should be thrownBy await {
      activationServiceImpl
        .fetchActivation(
          FetchRequest(
            TransactionId(TransactionId.generateTid()).serialize,
            message.user.namespace.name.asString,
            testFQN.serialize,
            notParsableRev,
            testContainerId,
            false,
            alive = true))
    }
  }

}
