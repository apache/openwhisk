/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool.test

import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.reflectiveCalls

import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.FSM
import akka.actor.FSM.CurrentState
import akka.actor.FSM.SubscribeTransitionCallBack
import akka.actor.FSM.Transition
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.Timeout
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.container.Interval
import whisk.core.containerpool._
import whisk.core.entity._
import whisk.core.entity.ExecManifest.RuntimeManifest
import whisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class ContainerProxyTests extends TestKit(ActorSystem("ContainerProxys"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    //with OneInstancePerTest
    with MockFactory {

    override def afterAll = TestKit.shutdownActorSystem(system)

    implicit val timeout = Timeout(5.seconds)

    // Common entities to pass to the tests. We don't really care what's inside
    // those for the behavior testing here, as none of the contents will really
    // reach a container anyway. We merely assert that passing and extraction of
    // the values is done properly.
    val exec = CodeExecAsString(RuntimeManifest("actionKind"), "testCode", None)
    val invocationNamespace = EntityName("invocationSpace")
    val action = WhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

    val message = ActivationMessage(
        TransactionId.testing,
        action.fullyQualifiedName(true),
        action.rev,
        Identity(Subject(), invocationNamespace, AuthKey(), Set()),
        ActivationId(),
        invocationNamespace.toPath,
        None)

    /** Imitates a StateTimeout in the FSM */
    def timeout(actor: ActorRef) = actor ! FSM.StateTimeout

    /** Common fixtures for all of the tests */
    val pool = new TestProbe(system) {
        /** Registers the transition callback and expects the first message */
        def registerCallback(c: ActorRef) = {
            send(c, SubscribeTransitionCallBack(this.ref))
            expectMsg(CurrentState(c, Uninitialized))
        }

        /** Pre-warms the given state-machine, assumes good cases */
        def preWarm(machine: ActorRef) = {
            send(machine, Start(exec))
            expectMsg(Transition(machine, Uninitialized, Starting))
            expectPreWarmed(exec.kind)
            expectMsg(Transition(machine, Starting, Started))
        }

        /** Run the common action on the state-machine, assumes good cases */
        def run(machine: ActorRef, currentState: ContainerState) = {
            send(machine, Run(action, message))
            expectMsg(Transition(machine, currentState, Running))
            expectWarmed(invocationNamespace.name, action)
            expectMsg(Transition(machine, Running, Ready))
        }

        /** Expect a NeedWork message with prewarmed data */
        def expectPreWarmed(kind: String) = expectMsgPF() {
            case NeedWork(PreWarmedData(_, kind)) => true
        }

        /** Expect a NeedWork message with warmed data */
        def expectWarmed(namespace: String, action: WhiskAction) = {
            val test = EntityName(namespace)
            expectMsgPF() {
                case NeedWork(WarmedData(_, `test`, `action`, _)) => true
            }
        }

        /** Expect the container to pause successfully */
        def expectPause(machine: ActorRef) = {
            expectMsg(Transition(machine, Ready, Pausing))
            expectWarmed(invocationNamespace.name, action)
            expectMsg(Transition(machine, Pausing, Paused))
        }
    }
    val ack = stubFunction[TransactionId, WhiskActivation, Future[Any]]
    val store = stubFunction[TransactionId, WhiskActivation, Future[Any]]

    behavior of "ContainerProxy"

    /*
     * SUCCESSFUL CASES
     */
    it should "create a container given a Start message" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = {
            tid shouldBe TransactionId.invokerWarmup
            name should fullyMatch regex """wsk_\d+_prewarm_actionKind"""
            memoryLimit shouldBe 256.MB

            Future.successful(container)
        }

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.preWarm(machine)
        }
    }

    it should "run a container which has been started before, write an active ack, write to the store, pause and remove the container" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)

            pool.preWarm(machine)
            pool.run(machine, Started)

            // Timeout causes the container to pause
            timeout(machine)
            pool.expectPause(machine)

            // Another pause causes the container to be removed
            timeout(machine)
            pool.expectMsg(ContainerRemoved)
            pool.expectMsg(Transition(machine, Paused, Removing))
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 1
        container.logsCount shouldBe 1
        container.haltCount shouldBe 1
        container.destroyCount shouldBe 1
        ack.verify(message.transid, *)
        store.verify(message.transid, *)
    }

    it should "run an action and continue with a next run without pausing the container" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.preWarm(machine)

            pool.run(machine, Started)
            // Note that there are no intermediate state changes
            pool.run(machine, Ready)
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 2
        container.logsCount shouldBe 2
        container.haltCount shouldBe 0
        ack.verify(message.transid, *).repeat(2)
        store.verify(message.transid, *).repeat(2)
    }

    it should "run an action after pausing the container" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.preWarm(machine)

            pool.run(machine, Started)
            timeout(machine)
            pool.expectPause(machine)
            pool.run(machine, Paused)
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 2
        container.logsCount shouldBe 2
        container.haltCount shouldBe 1
        container.resumeCount shouldBe 1
        ack.verify(message.transid, *).repeat(2)
        store.verify(message.transid, *).repeat(2)
    }

    it should "successfully run on an uninitialized container" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.run(machine, Uninitialized)
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 1
        container.logsCount shouldBe 1
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    /*
     * ERROR CASES
     */
    it should "complete the transaction and abort if container creation fails" in {
        val container = new TestContainer
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.failed(new Exception())

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.send(machine, Run(action, message))
            pool.expectMsg(Transition(machine, Uninitialized, Running))
            pool.expectMsg(ContainerRemoved)
        }

        container.initializeCount shouldBe 0
        container.runCount shouldBe 0
        container.logsCount shouldBe 0 // gather no logs
        container.destroyCount shouldBe 0 // no destroying possible as no container could be obtained
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    it should "complete the transaction and destroy the container on a failed init" in {
        val container = new TestContainer {
            override def initialize(initializer: Option[JsObject], timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
                initializeCount += 1
                Future.failed(InitializationError(ActivationResponse.applicationError("boom"), Interval.zero))
            }
        }
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.send(machine, Run(action, message))
            pool.expectMsg(Transition(machine, Uninitialized, Running))
            pool.expectMsg(ContainerRemoved)
            pool.expectMsg(Transition(machine, Running, Removing))
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 0 // should not run the action
        container.logsCount shouldBe 1
        container.destroyCount shouldBe 1
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    it should "complete the transaction and destroy the container on a failed run" in {
        val container = new TestContainer {
            override def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
                runCount += 1
                Future.successful((Interval.zero, ActivationResponse.applicationError("boom")))
            }
        }
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.send(machine, Run(action, message))
            pool.expectMsg(Transition(machine, Uninitialized, Running))
            pool.expectMsg(ContainerRemoved)
            pool.expectMsg(Transition(machine, Running, Removing))
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 1
        container.logsCount shouldBe 1
        container.destroyCount shouldBe 1
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    /*
     * DELAYED DELETION CASES
     */
    // this test represents a Remove message whenever you are in the "Running" state. Therefore, testing
    // a Remove while /init should suffice to guarantee testcoverage here.
    it should "delay a deletion message until the transaction is completed successfully" in {
        val initPromise = Promise[Interval]
        val container = new TestContainer {
            override def initialize(initializer: Option[JsObject], timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
                initializeCount += 1
                initPromise.future
            }
        }
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)

            // Start running the action
            pool.send(machine, Run(action, message))
            pool.expectMsg(Transition(machine, Uninitialized, Running))

            // Schedule the container to be removed
            pool.send(machine, Remove)

            // Finish /init, note that /run and log-collecting happens nonetheless
            initPromise.success(Interval.zero)
            pool.expectWarmed(invocationNamespace.name, action)
            pool.expectMsg(Transition(machine, Running, Ready))

            // Remove the container after the transaction finished
            pool.expectMsg(ContainerRemoved)
            pool.expectMsg(Transition(machine, Ready, Removing))
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 1
        container.logsCount shouldBe 1
        container.haltCount shouldBe 0 // skips pausing the container
        container.destroyCount shouldBe 1
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    // this tests a Run message in the "Removing" state. The contract between the pool and state-machine
    // is, that only one Run is to be sent until a "NeedWork" comes back. If we sent a NeedWork but no work is
    // there, we might run into the final timeout which will schedule a removal of the container. There is a
    // time window though, in which the pool doesn't know of that desicion yet. We handle the collision by
    // sending the Run back to the pool so it can reschedule.
    it should "send back a Run message which got sent before the container decided to remove itself" in {
        val destroyPromise = Promise[Unit]
        val container = new TestContainer {
            override def destroy()(implicit transid: TransactionId): Future[Unit] = {
                destroyCount += 1
                destroyPromise.future
            }
        }
        def factory(tid: TransactionId, name: String, exec: Exec, memoryLimit: ByteSize) = Future.successful(container)

        val machine = pool.childActorOf(ContainerProxy.props(factory, ack, store))
        within(timeout.duration) {
            pool.registerCallback(machine)
            pool.run(machine, Uninitialized)
            timeout(machine)
            pool.expectPause(machine)
            timeout(machine)

            // We don't know of this timeout, so we schedule a run.
            pool.send(machine, Run(action, message))

            // State-machine shuts down nonetheless.
            pool.expectMsg(ContainerRemoved)
            pool.expectMsg(Transition(machine, Paused, Removing))

            // Pool gets the message again.
            pool.expectMsg(Run(action, message))
        }

        container.initializeCount shouldBe 1
        container.runCount shouldBe 1
        container.logsCount shouldBe 1
        container.haltCount shouldBe 1
        container.resumeCount shouldBe 1
        container.destroyCount shouldBe 1
        ack.verify(message.transid, *).repeat(1)
        store.verify(message.transid, *).repeat(1)
    }

    /**
     * Implements all the good cases of a perfect run to facilitate error case overriding.
     */
    class TestContainer extends Container {
        var haltCount = 0
        var resumeCount = 0
        var destroyCount = 0
        var initializeCount = 0
        var runCount = 0
        var logsCount = 0

        def halt()(implicit transid: TransactionId): Future[Unit] = {
            haltCount += 1
            Future.successful(())
        }
        def resume()(implicit transid: TransactionId): Future[Unit] = {
            resumeCount += 1
            Future.successful(())
        }
        def destroy()(implicit transid: TransactionId): Future[Unit] = {
            destroyCount += 1
            Future.successful(())
        }
        def initialize(initializer: Option[JsObject], timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
            initializeCount += 1
            initializer shouldBe action.containerInitializer
            timeout shouldBe action.limits.timeout.duration
            Future.successful(Interval.zero)
        }
        def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
            runCount += 1
            environment.fields("api_key") shouldBe message.user.authkey.toJson
            environment.fields("namespace") shouldBe invocationNamespace.toJson
            environment.fields("action_name") shouldBe message.action.qualifiedNameWithLeadingSlash.toJson
            environment.fields("activation_id") shouldBe message.activationId.toJson
            val deadline = Instant.ofEpochMilli(environment.fields("deadline").convertTo[String].toLong)
            val maxDeadline = Instant.now.plusMillis(timeout.toMillis)

            // The deadline should be in the future but must be smaller than or equal
            // a freshly computed deadline, as they get computed slightly after each other
            deadline should (be <= maxDeadline and be >= Instant.now)

            Future.successful((Interval.zero, ActivationResponse.success()))
        }
        def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Future[List[String]] = {
            logsCount += 1
            Future.successful(List("helloTest"))
        }
    }
}
