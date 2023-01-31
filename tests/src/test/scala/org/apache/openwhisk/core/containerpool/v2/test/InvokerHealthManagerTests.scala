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

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem}
import akka.testkit.{ImplicitSender, TestActor, TestFSMRef, TestKit, TestProbe}
import common.StreamLogging
import org.apache.openwhisk.common.InvokerState.{Healthy, Offline, Unhealthy}
import org.apache.openwhisk.common.{Enable, GracefulShutdown, RingBuffer}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.InvokerResourceMessage
import org.apache.openwhisk.core.containerpool.v2._
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{ExecManifest, InvokerInstanceId, WhiskEntityStore}
import org.apache.openwhisk.core.etcd.EtcdKV.InvokerKeys
import org.apache.openwhisk.core.service.UpdateDataOnChange
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpecLike, Matchers}

import scala.collection.mutable
import scala.concurrent.duration._
@RunWith(classOf[JUnitRunner])
class InvokerHealthManagerTests
    extends TestKit(ActorSystem("InvokerHealthManager"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with MockFactory
    with ScalaFutures
    with StreamLogging
    with DbUtils {

  override def afterAll = TestKit.shutdownActorSystem(system)

  implicit val ec = system.dispatcher

  val config = new WhiskConfig(ExecManifest.requiredProperties)

  ExecManifest.initialize(config) should be a 'success

  val timeout = 10.seconds

  val instanceId = InvokerInstanceId(0, userMemory = 1024.MB, tags = Seq("gpu-enabled", "high-memory"))

  val entityStore = WhiskEntityStore.datastore()

  val freeMemory: Long = 512

  val busyMemory: Long = 256

  val inProgressMemory: Long = 256

  /** Creates a sequence of containers and a factory returning this sequence. */
  def testContainers(n: Int) = {
    val containers = (0 to n).map(_ => TestProbe())
    val queue = mutable.Queue(containers: _*)
    val factory = (fac: ActorRefFactory, manager: ActorRef) => queue.dequeue().ref
    (containers, factory)
  }

  behavior of "InvokerHealthManager"

  it should "invoke health action and become healthy state" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val probe = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Offline))

    fsm ! Enable
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))
    probe.expectMsg(Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }

  it should "invoke health action again when it becomes unhealthy" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))
    val probe = TestProbe()
    var buffer = new RingBuffer[Boolean](InvokerHealthManager.bufferSize)
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      buffer.add(true)
    }
    fsm.setState(Healthy, InvokerInfo(buffer, memory = MemoryInfo(instanceId.userMemory.toMB, 0, 0)))
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Healthy))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    (1 to InvokerHealthManager.bufferErrorTolerance + 1) foreach { _ =>
      fsm ! HealthMessage(false)
    }

    probe.expectMsg(Transition(fsm, Healthy, Unhealthy))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }

  it should "publish healthy status and pool info to etcd together" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))
    val probe = TestProbe()
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Offline))

    fsm ! Enable
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))
    probe.expectMsg(Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    fsm ! MemoryInfo(freeMemory, busyMemory, inProgressMemory)
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          freeMemory,
          busyMemory,
          inProgressMemory,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }

  it should "change the invoker pool info to etcd when memory info has changes" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))
    val probe = TestProbe()
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Offline))

    fsm ! Enable
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))
    probe.expectMsg(Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    fsm ! MemoryInfo(freeMemory, busyMemory, inProgressMemory)

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          freeMemory,
          busyMemory,
          inProgressMemory,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    val changedFreeMemory = freeMemory - 256
    val changedBusyMemory = busyMemory + 256
    fsm ! MemoryInfo(changedFreeMemory, changedBusyMemory, inProgressMemory)

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          changedFreeMemory,
          changedBusyMemory,
          inProgressMemory,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }

  it should "disable and enable the invoker gracefully" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))

    val probe = TestProbe()
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Offline))

    fsm ! Enable
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))
    probe.expectMsg(Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    fsm ! GracefulShutdown

    probe.expectMsg(Transition(fsm, Healthy, Offline))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Offline.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    val mockHealthActionProxy = TestProbe()
    fsm.underlyingActor.healthActionProxy = Some(mockHealthActionProxy.ref)

    fsm ! Enable

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))

    mockHealthActionProxy.setAutoPilot((sender, msg) =>
      msg match {
        case _: Initialize =>
          (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
            sender ! HealthMessage(true)
          }

          TestActor.KeepRunning

        case GracefulShutdown =>
          TestActor.KeepRunning

    })
    probe.expectMsg(10.seconds, Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }

  it should "keep status Offline all the time in spite of receive healthMessage" in within(timeout) {
    val (_, factory) = testContainers(InvokerHealthManager.bufferSize)
    val dataManagementService = TestProbe()
    val fsm = TestFSMRef(new InvokerHealthManager(instanceId, factory, dataManagementService.ref, entityStore))

    val probe = TestProbe()
    fsm ! SubscribeTransitionCallBack(probe.ref)
    probe.expectMsg(CurrentState(fsm, Offline))

    fsm ! Enable
    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    probe.expectMsg(Transition(fsm, Offline, Unhealthy))
    probe.expectMsg(Transition(fsm, Unhealthy, Healthy))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Unhealthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Healthy.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    fsm ! GracefulShutdown

    probe.expectMsg(Transition(fsm, Healthy, Offline))

    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Offline.asString,
          instanceId.userMemory.toMB,
          0,
          0,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))

    (1 to InvokerHealthManager.bufferSize - InvokerHealthManager.bufferErrorTolerance) foreach { _ =>
      fsm ! HealthMessage(true)
    }

    // Keep the status Offline all the time unless enable it first
    probe.expectNoMessage()

    val changedFreeMemory = freeMemory - 256
    val changedBusyMemory = busyMemory + 256
    fsm ! MemoryInfo(changedFreeMemory, changedBusyMemory, inProgressMemory)

    // In spite of the status is Offline, the Memory info may be changed during zerodowntime deployment for invoker.
    dataManagementService.expectMsg(
      UpdateDataOnChange(
        InvokerKeys.health(instanceId),
        InvokerResourceMessage(
          Offline.asString,
          changedFreeMemory,
          changedBusyMemory,
          inProgressMemory,
          instanceId.tags,
          instanceId.dedicatedNamespaces).serialize))
  }
}
