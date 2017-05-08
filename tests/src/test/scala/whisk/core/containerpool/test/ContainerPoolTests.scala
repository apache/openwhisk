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
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.containerpool._
import whisk.core.dispatcher.ActivationFeed.ContainerReleased
import whisk.core.entity._
import whisk.core.entity.ExecManifest.RuntimeManifest
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity.size._

/**
 * Behavior tests for the ContainerPool
 *
 * These tests test the runtime behavior of a ContainerPool actor.
 */
@RunWith(classOf[JUnitRunner])
class ContainerPoolTests extends TestKit(ActorSystem("ContainerPool"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockFactory {

    override def afterAll = TestKit.shutdownActorSystem(system)

    val timeout = 5.seconds

    // Common entities to pass to the tests. We don't really care what's inside
    // those for the behavior testing here, as none of the contents will really
    // reach a container anyway. We merely assert that passing and extraction of
    // the values is done properly.
    val exec = CodeExecAsString(RuntimeManifest("actionKind", ImageName("testImage")), "testCode", None)
    val memoryLimit = 256.MB

    val invocationNamespace = EntityName("invocationSpace")
    val action = ExecutableWhiskAction(EntityPath("actionSpace"), EntityName("actionName"), exec)

    val message = ActivationMessage(
        TransactionId.testing,
        action.fullyQualifiedName(true),
        action.rev,
        Identity(Subject(), invocationNamespace, AuthKey(), Set()),
        ActivationId(),
        invocationNamespace.toPath,
        None)

    val runMessage = Run(action, message)

    /** Helper to create PreWarmedData */
    def preWarmedData(kind: String, memoryLimit: ByteSize = memoryLimit) = PreWarmedData(stub[Container], kind, memoryLimit)

    /** Helper to create WarmedData */
    def warmedData(action: ExecutableWhiskAction = action, namespace: String = "invocationSpace", lastUsed: Instant = Instant.now) =
        WarmedData(stub[Container], EntityName(namespace), action, lastUsed)

    /** Creates a sequence of containers and a factory returning this sequence. */
    def testContainers(n: Int) = {
        val containers = (0 to n).map(_ => TestProbe())
        val queue = mutable.Queue(containers: _*)
        val factory = (fac: ActorRefFactory) => queue.dequeue().ref
        (containers, factory)
    }

    behavior of "ContainerPool"

    it should "indicate free resources to the feed only if a warm container responds" in within(timeout) {
        val (containers, factory) = testContainers(1)
        val feed = TestProbe()

        val pool = system.actorOf(ContainerPool.props(factory, 0, feed.ref))
        containers(0).send(pool, NeedWork(warmedData()))
        feed.expectMsg(ContainerReleased)
    }

    /*
     * CONTAINER SCHEDULING
     *
     * These tests only test the simplest approaches. Look below for full coverage tests
     * of the respective scheduling methods.
     */
    it should "reuse a warm container" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()
        val pool = system.actorOf(ContainerPool.props(factory, 2, feed.ref))

        pool ! runMessage
        containers(0).expectMsg(runMessage)
        containers(0).send(pool, NeedWork(warmedData()))

        pool ! runMessage
        containers(0).expectMsg(runMessage)
        containers(1).expectNoMsg(100.milliseconds)
    }

    it should "create a container if it cannot find a matching container" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()

        val pool = system.actorOf(ContainerPool.props(factory, 2, feed.ref))
        pool ! runMessage
        containers(0).expectMsg(runMessage)
        // Note that the container doesn't respond, thus it's not free to take work
        pool ! runMessage
        containers(1).expectMsg(runMessage)
    }

    it should "remove a container to make space in the pool if it is already full" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()

        // a pool with only 1 slot
        val pool = system.actorOf(ContainerPool.props(factory, 1, feed.ref))
        pool ! runMessage
        containers(0).expectMsg(runMessage)
        containers(0).send(pool, NeedWork(warmedData()))
        pool ! runMessage
        containers(0).expectMsg(runMessage)
        pool ! runMessage
        containers(0).expectMsg(Remove)
        containers(1).expectMsg(runMessage)
    }

    /*
     * CONTAINER PREWARMING
     */
    it should "create prewarmed containers on startup" in within(timeout) {
        val (containers, factory) = testContainers(1)
        val feed = TestProbe()

        val pool = system.actorOf(ContainerPool.props(factory, 0, feed.ref, Some(PrewarmingConfig(1, exec, memoryLimit))))
        containers(0).expectMsg(Start(exec, memoryLimit))
    }

    it should "use a prewarmed container and create a new one to fill its place" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()

        val pool = system.actorOf(ContainerPool.props(factory, 1, feed.ref, Some(PrewarmingConfig(1, exec, memoryLimit))))
        containers(0).expectMsg(Start(exec, memoryLimit))
        containers(0).send(pool, NeedWork(preWarmedData(exec.kind)))
        pool ! Run(action, message)
        containers(1).expectMsg(Start(exec, memoryLimit))
    }

    it should "not use a prewarmed container if it doesn't fit the kind" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()

        val alternativeExec = CodeExecAsString(RuntimeManifest("anotherKind", ImageName("testImage")), "testCode", None)

        val pool = system.actorOf(ContainerPool.props(factory, 1, feed.ref, Some(PrewarmingConfig(1, alternativeExec, memoryLimit))))
        containers(0).expectMsg(Start(alternativeExec, memoryLimit)) // container0 was prewarmed
        containers(0).send(pool, NeedWork(preWarmedData(alternativeExec.kind)))
        pool ! runMessage
        containers(1).expectMsg(runMessage) // but container1 is used
    }

    it should "not use a prewarmed container if it doesn't fit memory wise" in within(timeout) {
        val (containers, factory) = testContainers(2)
        val feed = TestProbe()

        val alternativeLimit = 128.MB

        val pool = system.actorOf(ContainerPool.props(factory, 1, feed.ref, Some(PrewarmingConfig(1, exec, alternativeLimit))))
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

        val pool = system.actorOf(ContainerPool.props(factory, 2, feed.ref))

        // container0 is created and used
        pool ! runMessage
        containers(0).expectMsg(runMessage)
        containers(0).send(pool, NeedWork(warmedData()))

        // container0 is reused
        pool ! runMessage
        containers(0).expectMsg(runMessage)
        containers(0).send(pool, NeedWork(warmedData()))

        // container0 is deleted
        containers(0).send(pool, ContainerRemoved)

        // container1 is created and used
        pool ! Run(action, message)
        containers(1).expectMsg(Run(action, message))
    }

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

    /** Helper to create a new action from String representations */
    def createAction(namespace: String = "actionNS", name: String = "actionName") =
        ExecutableWhiskAction(EntityPath(namespace), EntityName(name), actionExec)

    /** Helper to create WarmedData with sensible defaults */
    def warmedData(action: ExecutableWhiskAction = createAction(), namespace: String = "anyNamespace", lastUsed: Instant = Instant.now) =
        WarmedData(stub[Container], EntityName(namespace), action, lastUsed)

    /** Helper to create PreWarmedData with sensible defaults */
    def preWarmedData(kind: String = "anyKind") = PreWarmedData(stub[Container], kind, 256.MB)

    /** Helper to create NoData */
    def noData() = NoData()

    /** Helper to create a free Worker, for shorter notation */
    def freeWorker(data: ContainerData) = WorkerData(data, Free)

    behavior of "ContainerPool schedule()"

    it should "not provide a container if idle pool is empty" in {
        ContainerPool.schedule(createAction(), EntityName("anyNamespace"), Map()) shouldBe None
    }

    it should "reuse an applicable warm container from idle pool with one container" in {
        val data = warmedData()
        val pool = Map('name -> freeWorker(data))

        // copy to make sure, referencial equality doesn't suffice
        ContainerPool.schedule(data.action.copy(), data.namespace, pool) shouldBe Some('name)
    }

    it should "reuse an applicable warm container from idle pool with several applicable containers" in {
        val data = warmedData()
        val pool = Map(
            'first -> freeWorker(data),
            'second -> freeWorker(data))

        ContainerPool.schedule(data.action.copy(), data.namespace, pool) should contain oneOf ('first, 'second)
    }

    it should "reuse an applicable warm container from idle pool with several different containers" in {
        val matchingData = warmedData()
        val pool = Map(
            'none -> freeWorker(noData()),
            'pre -> freeWorker(preWarmedData()),
            'warm -> freeWorker(matchingData))

        ContainerPool.schedule(matchingData.action.copy(), matchingData.namespace, pool) shouldBe Some('warm)
    }

    it should "not reuse a container from idle pool with non-warm containers" in {
        val data = warmedData()
        // data is **not** in the pool!
        val pool = Map(
            'none -> freeWorker(noData()),
            'pre -> freeWorker(preWarmedData()))

        ContainerPool.schedule(data.action.copy(), data.namespace, pool) shouldBe None
    }

    it should "not reuse a warm container with different invocation namespace" in {
        val data = warmedData()
        val pool = Map('warm -> freeWorker(data))
        val differentNamespace = EntityName(data.namespace.asString + "butDifferent")

        data.namespace should not be differentNamespace
        ContainerPool.schedule(data.action.copy(), differentNamespace, pool) shouldBe None
    }

    it should "not reuse a warm container with different action name" in {
        val data = warmedData()
        val differentAction = data.action.copy(name = EntityName(data.action.name.asString + "butDifferent"))
        val pool = Map(
            'warm -> freeWorker(data))

        data.action.name should not be differentAction.name
        ContainerPool.schedule(differentAction, data.namespace, pool) shouldBe None
    }

    it should "not reuse a warm container with different action version" in {
        val data = warmedData()
        val differentAction = data.action.copy(version = data.action.version.upMajor)
        val pool = Map(
            'warm -> freeWorker(data))

        data.action.version should not be differentAction.version
        ContainerPool.schedule(differentAction, data.namespace, pool) shouldBe None
    }

    behavior of "ContainerPool remove()"

    it should "not provide a container if pool is empty" in {
        ContainerPool.remove(EntityName("anyNamespace"), Map()) shouldBe None
    }

    it should "not provide a container from busy pool with non-warm containers" in {
        val pool = Map(
            'none -> freeWorker(noData()),
            'pre -> freeWorker(preWarmedData()))

        ContainerPool.remove(EntityName("anyNamespace"), pool) shouldBe None
    }

    it should "provide a container from pool with one single container regardless of invocation namespace" in {
        val data = warmedData()
        val pool = Map('warm -> freeWorker(data))

        ContainerPool.remove(data.namespace, pool) shouldBe Some('warm)
        ContainerPool.remove(EntityName(data.namespace.asString + "butDifferent"), pool) shouldBe Some('warm)
    }

    it should "provide oldest container from busy pool with multiple containers" in {
        val commonNamespace = "commonNamespace"
        val first = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(1))
        val second = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(2))
        val oldest = warmedData(namespace = commonNamespace, lastUsed = Instant.ofEpochMilli(0))

        val pool = Map(
            'first -> freeWorker(first),
            'second -> freeWorker(second),
            'oldest -> freeWorker(oldest))

        ContainerPool.remove(EntityName(commonNamespace), pool) shouldBe Some('oldest)
    }

    it should "provide oldest container of largest namespace group from busy pool with multiple containers" in {
        val smallNamespace = "smallNamespace"
        val mediumNamespace = "mediumNamespace"
        val largeNamespace = "largeNamespace"

        // Note: We choose the oldest from the **largest** pool, although all other containers are even older.
        val myData = warmedData(namespace = smallNamespace, lastUsed = Instant.ofEpochMilli(0))
        val pool = Map(
            'my -> freeWorker(myData),
            'other -> freeWorker(warmedData(namespace = mediumNamespace, lastUsed = Instant.ofEpochMilli(1))),
            'largeYoung -> freeWorker(warmedData(namespace = largeNamespace, lastUsed = Instant.ofEpochMilli(3))),
            'largeOld -> freeWorker(warmedData(namespace = largeNamespace, lastUsed = Instant.ofEpochMilli(2))))

        ContainerPool.remove(myData.namespace, pool) shouldBe Some('largeOld)
    }
}
