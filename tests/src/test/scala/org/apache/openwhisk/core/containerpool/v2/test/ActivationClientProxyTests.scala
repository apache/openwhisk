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

import akka.Done
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, ActorSystem}
import akka.grpc.internal.ClientClosedException
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import common.StreamLogging
import io.grpc.StatusRuntimeException
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.v2._
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.scheduler.SchedulerEndpoints
import org.apache.openwhisk.core.scheduler.grpc.{ActivationResponse => AResponse}
import org.apache.openwhisk.core.scheduler.queue.{ActionMismatch, NoActivationMessage, NoMemoryQueue}
import org.apache.openwhisk.grpc
import org.apache.openwhisk.grpc.{ActivationServiceClient, FetchRequest, RescheduleRequest, RescheduleResponse}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ActivationClientProxyTests
    extends TestKit(ActorSystem("ActivationClientProxy"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging
    with ScalaFutures {

  override def afterAll: Unit = TestKit.shutdownActorSystem(system)

  implicit val ec = system.dispatcher

  val timeout = 20.seconds

  val log = logging

  val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
  val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)
  val fqn = action.fullyQualifiedName(true)
  val rev = action.rev
  val schedulerHost = "127.17.0.1"
  val rpcPort = 13001
  val containerId = ContainerId("fakeContainerId")
  val messageTransId = TransactionId(TransactionId.testing.meta.id)
  val invocationNamespace = EntityName("invocationSpace")
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

  val entityStore = WhiskEntityStore.datastore()

  behavior of "ActivationClientProxy"

  it should "create a grpc client successfully" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)

    machine ! StartClient

    probe.expectMsg(ClientCreationCompleted())
    probe.expectMsg(Transition(machine, ClientProxyUninitialized, ClientProxyReady))
  }

  it should "be closed when failed to create grpc client" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future {
        throw new RuntimeException("failed to create client")
        MockActivationServiceClient(fetch)
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)

    machine ! StartClient

    probe.expectMsg(Transition(machine, ClientProxyUninitialized, ClientProxyRemoving))
    probe.expectMsg(ClientClosed)

    probe expectTerminated machine
  }

  it should "fetch activation message successfully" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(message)
  }

  it should "be recreated when scheduler is changed" in within(timeout) {
    var creationCount = 0
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Left(NoMemoryQueue())).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => {
      creationCount += 1
      Future(MockActivationServiceClient(fetch))
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    // new scheduler is reached
    machine ! RequestActivation(newScheduler = Some(SchedulerEndpoints("0.0.0.0", 10, 11)))

    awaitAssert {
      creationCount should be > 1
    }
  }

  it should "be recreated when the queue does not exist" in within(timeout) {
    var creationCount = 0
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Left(NoMemoryQueue())).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => {
      creationCount += 1
      Future(MockActivationServiceClient(fetch))
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()

    awaitAssert {
      creationCount should be > 1
    }
  }

  it should "be closed when the action version does not match" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Left(ActionMismatch())).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(Transition(machine, ClientProxyReady, ClientProxyRemoving))
    probe.expectMsg(ClientClosed)

    probe expectTerminated machine
  }

  it should "retry to request activation message when scheduler response no activation message" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Left(NoActivationMessage())).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(RetryRequestActivation)
  }

  it should "create activation client on other scheduler when the queue does not exist" in within(timeout) {
    val createClientOnOtherScheduler = new ArrayBuffer[Boolean]()
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Left(NoMemoryQueue())).serialize))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, tryOtherScheduler: Boolean) => {
      createClientOnOtherScheduler += tryOtherScheduler
      Future(MockActivationServiceClient(fetch))
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()

    awaitAssert {
      // Create activation client using original scheduler endpoint firstly
      createClientOnOtherScheduler(0) shouldBe false
      // Create activation client using latest scheduler endpoint(try other scheduler) when no memoryQueue
      createClientOnOtherScheduler(1) shouldBe true
    }
  }

  it should "request activation message when the message can't deserialize" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse("aaaaaa"))
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(RetryRequestActivation)
  }

  it should "be recreated when akka grpc server connection failed" in within(timeout) {
    var creationCount = 0
    val fetch = (_: FetchRequest) =>
      Future {
        throw new StatusRuntimeException(io.grpc.Status.UNAVAILABLE)
        grpc.FetchResponse(AResponse(Right(message)).serialize)
    }
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => {
      creationCount += 1
      Future(MockActivationServiceClient(fetch))
    }

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()

    awaitAssert {
      creationCount should be > 1
    }
  }

  it should "be closed when grpc client is already closed" in within(timeout) {
    val fetch = (_: FetchRequest) =>
      Future {
        throw new ClientClosedException()
        grpc.FetchResponse(AResponse(Right(message)).serialize)
    }
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(Transition(machine, ClientProxyReady, ClientProxyRemoving))
    probe.expectMsg(ClientClosed)

    probe expectTerminated machine
  }

  it should "be closed when it failed to getting activation from scheduler" in within(timeout) {
    val fetch = (_: FetchRequest) =>
      Future {
        throw new Exception("Unknown exception")
        grpc.FetchResponse(AResponse(Right(message)).serialize)
    }
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) =>
      Future(MockActivationServiceClient(fetch))

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! RequestActivation()
    probe.expectMsg(Transition(machine, ClientProxyReady, ClientProxyRemoving))
    probe.expectMsg(ClientClosed)

    probe expectTerminated machine
  }

  it should "be closed when it receives a CloseClientProxy message for a normal timeout case" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val activationClient = MockActivationServiceClient(fetch)
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => Future(activationClient)

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! CloseClientProxy
    awaitAssert(activationClient.isClosed shouldBe true)

    probe.expectMsg(Transition(machine, ClientProxyReady, ClientProxyRemoving))

    machine ! RequestActivation()

    probe expectMsg ClientClosed
    probe expectTerminated machine
  }

  it should "be closed when it receives a StopClientProxy message for the case of graceful shutdown" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val activationClient = MockActivationServiceClient(fetch)
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => Future(activationClient)

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    machine ! StopClientProxy
    awaitAssert(activationClient.isClosed shouldBe true)

    probe expectMsg ClientClosed
    probe expectTerminated machine
  }

  it should "be safely closed when the client is already closed" in within(timeout) {
    val fetch = (_: FetchRequest) => Future(grpc.FetchResponse(AResponse(Right(message)).serialize))
    val activationClient = MockActivationServiceClient(fetch)
    val client = (_: String, _: FullyQualifiedEntityName, _: String, _: Int, _: Boolean) => Future(activationClient)

    val probe = TestProbe()
    val machine =
      probe.childActorOf(
        ActivationClientProxy
          .props(invocationNamespace.asString, fqn, rev, schedulerHost, rpcPort, containerId, client))
    registerCallback(machine, probe)
    ready(machine, probe)

    // close client
    activationClient.close().futureValue
    awaitAssert(activationClient.isClosed shouldBe true)

    // close client again
    machine ! StopClientProxy

    probe expectMsg ClientClosed
    probe expectTerminated machine
  }

  /** Registers the transition callback and expects the first message */
  def registerCallback(c: ActorRef, probe: TestProbe) = {
    c ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(c, ClientProxyUninitialized))
    probe watch c
  }

  def ready(machine: ActorRef, probe: TestProbe) = {
    machine ! StartClient
    probe.expectMsg(ClientCreationCompleted())
    probe.expectMsg(Transition(machine, ClientProxyUninitialized, ClientProxyReady))
  }

  case class MockActivationServiceClient(customFetchActivation: FetchRequest => Future[grpc.FetchResponse])
      extends ActivationServiceClient {

    var isClosed = false

    override def close(): Future[Done] = {
      isClosed = true
      Future.successful(Done)
    }

    override def closed(): Future[Done] = close()

    override def rescheduleActivation(in: RescheduleRequest): Future[RescheduleResponse] = {
      Future.successful(RescheduleResponse())
    }

    override def fetchActivation(in: FetchRequest): Future[grpc.FetchResponse] = {
      if (!isClosed) {
        customFetchActivation(in)
      } else {
        throw new ClientClosedException()
      }
    }
  }
}
