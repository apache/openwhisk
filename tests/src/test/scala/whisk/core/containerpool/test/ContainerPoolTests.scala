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

package whisk.core.containerpool.docker.test

import org.scalatest.FlatSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import whisk.core.containerpool.ContainerPool
import whisk.core.entity.WhiskAction
import whisk.core.entity.CodeExecAsString
import whisk.core.entity.ExecManifest.RuntimeManifest
import whisk.core.entity.EntityPath
import whisk.core.entity.EntityName
import whisk.core.containerpool.Container
import java.time.Instant
import whisk.core.containerpool.WarmedData
import org.scalatest.Matchers
import org.scalamock.scalatest.MockFactory
import whisk.core.containerpool.NoData
import whisk.core.containerpool.PreWarmedData
import whisk.core.containerpool.WorkerData
import whisk.core.containerpool.Free
import whisk.core.containerpool.ContainerData

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class ContainerPoolScheduleTests extends FlatSpec with Matchers with MockFactory {

    val actionExec = CodeExecAsString(RuntimeManifest("actionKind"), "testCode", None)

    def createAction(namespace: String = "actionNS", name: String = "actionName") =
        WhiskAction(EntityPath(namespace), EntityName(name), actionExec)

    def warmedData(action: WhiskAction = createAction(), namespace: String = "anyNamespace", lastUsed: Instant = Instant.now) =
        WarmedData(stub[Container], EntityName(namespace), action, lastUsed)

    def preWarmedData(kind: String = "anyKind", lastUsed: Instant = Instant.EPOCH) =
        PreWarmedData(stub[Container], kind, lastUsed)

    def noData(lastUsed: Instant = Instant.EPOCH) =
        NoData(lastUsed)

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
