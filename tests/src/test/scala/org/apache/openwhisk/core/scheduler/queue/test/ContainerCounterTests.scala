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

package org.apache.openwhisk.core.scheduler.queue.test

import java.{lang, util}
import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import com.google.protobuf.ByteString
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.api.{Event, KeyValue, LeaseKeepAliveResponse, ResponseHeader, TxnResponse}
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.WatchUpdate
import com.ibm.etcd.client.{EtcdClient => Client}
import common.StreamLogging
import org.apache.openwhisk.core.entity.{
  CreationId,
  DocRevision,
  EntityName,
  EntityPath,
  FullyQualifiedEntityName,
  SchedulerInstanceId
}
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.inProgressContainer
import org.apache.openwhisk.core.scheduler.queue.NamespaceContainerCount
import org.apache.openwhisk.core.service.{DeleteEvent, PutEvent, UnwatchEndpoint, WatchEndpoint, WatcherService}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Future
import scala.concurrent.duration.TimeUnit

@RunWith(classOf[JUnitRunner])
class ContainerCounterTests
    extends TestKit(ActorSystem("ContainerCounter"))
    with FlatSpecLike
    with Matchers
    with MockFactory
    with ScalaFutures
    with StreamLogging {

  private implicit val ec = system.dispatcher

  private val namespace = "testNamespace"
  private val namespace2 = "testNamespace2"
  private val action = "testAction"
  private val action2 = "testAction2"
  private val schedulerId = SchedulerInstanceId("0")
  private val fqn = FullyQualifiedEntityName(EntityPath(namespace), EntityName(action))
  private val revision = DocRevision("1-testRev1")
  private val fqn2 = FullyQualifiedEntityName(EntityPath(namespace), EntityName(action2))
  private val revision2 = DocRevision("1-testRev2")
  private val fqn3 = FullyQualifiedEntityName(EntityPath(namespace2), EntityName(action2))
  private val revision3 = DocRevision("1-testRev3")
  private val watcherName = s"container-counter-$namespace"
  private val inProgressContainerPrefixKeyByNamespace =
    ContainerKeys.inProgressContainerPrefixByNamespace(namespace)
  private val existingContainerPrefixKeyByNamespace =
    ContainerKeys.existingContainersPrefixByNamespace(namespace)

  val client: Client = {
    val hostAndPorts = "172.17.0.1:2379"
    Client.forEndpoints(hostAndPorts).withPlainText().build()
  }

  it should "be shared for a same namespace" in {
    val etcd = mock[EtcdClient]
    val watcher = TestProbe()
    val res = Future.sequence {
      (0 to 99).map { _ =>
        Future {
          NamespaceContainerCount(namespace, etcd, watcher.ref)
        }
      }
    }.futureValue

    // only create one instance
    res.toSet.size shouldBe 1
    res.head.references.intValue shouldBe 100

    // only register watch endpoint once
    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerPrefixKeyByNamespace, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerPrefixKeyByNamespace, "", true, watcherName, Set(PutEvent, DeleteEvent)))
    watcher.expectNoMessage()
    NamespaceContainerCount.instances.size shouldBe 1
    NamespaceContainerCount.instances.clear()
  }

  it should "and only should be closed when all references are closed" in {
    val etcd = mock[EtcdClient]
    val watcher = TestProbe()
    val res = Future.sequence {
      (0 to 99).map { _ =>
        Future {
          NamespaceContainerCount(namespace, etcd, watcher.ref)
        }
      }
    }.futureValue

    // only create one instance
    res.toSet.size shouldBe 1
    res.head.references.intValue shouldBe 100

    // only register watch endpoint once
    watcher.expectMsgAllOf(
      WatchEndpoint(inProgressContainerPrefixKeyByNamespace, "", true, watcherName, Set(PutEvent, DeleteEvent)),
      WatchEndpoint(existingContainerPrefixKeyByNamespace, "", true, watcherName, Set(PutEvent, DeleteEvent)))
    watcher.expectNoMessage()
    NamespaceContainerCount.instances.size shouldBe 1

    // close 50 times
    Future.sequence {
      (0 to 49).map { _ =>
        Future(res.head.close())
      }
    }.futureValue
    res.head.references.intValue shouldBe 50

    // should not unregister watch endpoint
    watcher.expectNoMessage()
    NamespaceContainerCount.instances.size shouldBe 1

    // close left 50 times
    Future.sequence {
      (0 to 49).map { _ =>
        Future(res.head.close())
      }
    }.futureValue
    res.head.references.intValue shouldBe 0

    // only unregister watch endpoint once
    watcher.expectMsgAllOf(
      UnwatchEndpoint(inProgressContainerPrefixKeyByNamespace, true, watcherName),
      UnwatchEndpoint(existingContainerPrefixKeyByNamespace, true, watcherName))
    watcher.expectNoMessage()
    NamespaceContainerCount.instances.size shouldBe 0
  }

  it should "update the number of containers based on Watch event" in {
    val mockEtcdClient = new MockEtcdClient(client, true)
    val watcher = system.actorOf(WatcherService.props(mockEtcdClient))

    val ns = NamespaceContainerCount(namespace, mockEtcdClient, watcher)
    Thread.sleep(1000)

    ns.inProgressContainerNumByNamespace shouldBe 0
    ns.existingContainerNumByNamespace shouldBe 0

    val invoker = "invoker0"

    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(namespace, fqn, revision, schedulerId, CreationId("testId")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      s"${ContainerKeys.existingContainers(namespace, fqn, DocRevision.empty)}/${invoker}/test-container",
      "test-value")

    Thread.sleep(1000)
    ns.inProgressContainerNumByNamespace shouldBe 1
    ns.existingContainerNumByNamespace shouldBe 1

    // other action's containers under same namespace should have effect
    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(namespace, fqn2, revision2, schedulerId, CreationId("testId2")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      s"${ContainerKeys.existingContainers(namespace, fqn2, DocRevision.empty)}/${invoker}/test-container2",
      "test-value")

    Thread.sleep(1000)
    ns.inProgressContainerNumByNamespace shouldBe 2
    ns.existingContainerNumByNamespace shouldBe 2

    // other namespace's containers should have no influence
    mockEtcdClient.publishEvents(
      EventType.PUT,
      inProgressContainer(namespace2, fqn3, revision3, schedulerId, CreationId("testId3")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.PUT,
      s"${ContainerKeys.existingContainers(namespace2, fqn3, DocRevision.empty)}/${invoker}/test-container3",
      "test-value")

    Thread.sleep(1000)
    ns.inProgressContainerNumByNamespace shouldBe 2
    ns.existingContainerNumByNamespace shouldBe 2

    // inProgress containers should have no effect on existing containers
    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(namespace, fqn, revision, schedulerId, CreationId("testId")),
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      inProgressContainer(namespace, fqn2, revision2, schedulerId, CreationId("testId2")),
      "test-value")

    Thread.sleep(1000)
    ns.inProgressContainerNumByNamespace shouldBe 0
    ns.existingContainerNumByNamespace shouldBe 2

    // existing containers should have no effect on inProgress containers
    mockEtcdClient.publishEvents(
      EventType.DELETE,
      s"${ContainerKeys.existingContainers(namespace, fqn, DocRevision.empty)}/${invoker}/test-container",
      "test-value")

    mockEtcdClient.publishEvents(
      EventType.DELETE,
      s"${ContainerKeys.existingContainers(namespace, fqn2, DocRevision.empty)}/${invoker}/test-container2",
      "test-value")

    Thread.sleep(1000)
    ns.inProgressContainerNumByNamespace shouldBe 0
    ns.existingContainerNumByNamespace shouldBe 0

    NamespaceContainerCount.instances.clear()
  }

  class MockEtcdClient(client: Client, isLeader: Boolean, leaseNotFound: Boolean = false, failedCount: Int = 1)
      extends EtcdClient(client)(ec) {
    var count = 0
    var storedValues = List.empty[(String, String, Long, Long)]
    var dataMap = Map[String, String]()

    override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] = {
      if (isLeader) {
        storedValues = (key, value.toString, cmpVersion, leaseId) :: storedValues
      }
      Future.successful(TxnResponse.newBuilder().setSucceeded(isLeader).build())
    }

    /*
     * this method count the number of entries whose key starts with the given prefix
     */
    override def getCount(prefixKey: String): Future[Long] = {
      Future.successful { dataMap.count(data => data._1.startsWith(prefixKey)) }
    }

    var watchCallbackMap = Map[String, WatchUpdate => Unit]()

    override def keepAliveOnce(leaseId: Long): Future[LeaseKeepAliveResponse] =
      Future.successful(LeaseKeepAliveResponse.newBuilder().setID(leaseId).build())

    /*
     * this method adds one callback for the given key in watchCallbackMap.
     *
     * Note: Currently it only supports prefix-based watch.
     */
    override def watchAllKeys(next: WatchUpdate => Unit, error: Throwable => Unit, completed: () => Unit): Watch = {

      watchCallbackMap += "" -> next
      new Watch {
        override def close(): Unit = {}

        override def addListener(listener: Runnable, executor: Executor): Unit = {}

        override def cancel(mayInterruptIfRunning: Boolean): Boolean = true

        override def isCancelled: Boolean = true

        override def isDone: Boolean = true

        override def get(): lang.Boolean = true

        override def get(timeout: Long, unit: TimeUnit): lang.Boolean = true
      }
    }

    /*
     * This method stores the data in dataMap to simulate etcd.put()
     * After then, it calls the registered watch callback for the given key
     * So we don't need to call put() to simulate watch API.
     * Expected order of calls is 1. watch(), 2.publishEvents(). Data will be stored in dataMap and
     * callbacks in the callbackMap for the given prefix will be called by publishEvents()
     *
     * Note: watch callback is currently registered based on prefix only.
     */
    def publishEvents(eventType: EventType, key: String, value: String): Unit = {
      val eType = eventType match {
        case EventType.PUT =>
          dataMap += key -> value
          EventType.PUT

        case EventType.DELETE =>
          dataMap -= key
          EventType.DELETE

        case EventType.UNRECOGNIZED => Event.EventType.UNRECOGNIZED
      }
      val event = Event
        .newBuilder()
        .setType(eType)
        .setPrevKv(
          KeyValue
            .newBuilder()
            .setKey(ByteString.copyFromUtf8(key))
            .setValue(ByteString.copyFromUtf8(value))
            .build())
        .setKv(
          KeyValue
            .newBuilder()
            .setKey(ByteString.copyFromUtf8(key))
            .setValue(ByteString.copyFromUtf8(value))
            .build())
        .build()

      // find the callbacks which has the proper prefix for the given key
      watchCallbackMap.filter(callback => key.startsWith(callback._1)).foreach { callback =>
        callback._2(new mockWatchUpdate().addEvents(event))
      }
    }
  }

  class mockWatchUpdate extends WatchUpdate {
    private var eventLists: util.List[Event] = new util.ArrayList[Event]()
    override def getHeader: ResponseHeader = ???

    def addEvents(event: Event): WatchUpdate = {
      eventLists.add(event)
      this
    }

    override def getEvents: util.List[Event] = eventLists
  }
}
