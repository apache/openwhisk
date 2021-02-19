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

package org.apache.openwhisk.core.service

import java.{lang, util}
import java.util.concurrent.Executor

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.google.protobuf.ByteString
import com.ibm.etcd.api.{Event, KeyValue, ResponseHeader}
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.WatchUpdate
import com.ibm.etcd.client.{EtcdClient => Client}
import common.StreamLogging
import org.apache.openwhisk.core.entity.SchedulerInstanceId
import org.apache.openwhisk.core.etcd.EtcdClient
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class WatcherServiceTests
    extends TestKit(ActorSystem("WatcherService"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ece: ExecutionContextExecutor = system.dispatcher

  private val watchName = "test-watcher-service"

  val schedulerId = SchedulerInstanceId("scheduler0")

  val client: Client = {
    val hostAndPorts = "172.17.0.1:2379"
    Client.forEndpoints(hostAndPorts).withPlainText().build()
  }

  val watch = new Watch {
    override def close(): Unit = {}

    override def addListener(listener: Runnable, executor: Executor): Unit = {}

    override def cancel(mayInterruptIfRunning: Boolean): Boolean = true

    override def isCancelled: Boolean = true

    override def isDone: Boolean = true

    override def get(): lang.Boolean = true

    override def get(timeout: Long, unit: TimeUnit): lang.Boolean = true
  }

  private def watchEtcd(etcdClient: EtcdClient): Unit = {
    (etcdClient
      .watchAllKeys(_: WatchUpdate => Unit, _: Throwable => Unit, _: () => Unit))
      .expects(*, *, *)
      .returning(watch)
  }

  behavior of "WatcherService"

  it should "watch a endpoint" in {
    val etcdClient = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"

    watchEtcd(etcdClient)

    val service = TestActorRef(new WatcherService(etcdClient))
    service ! WatchEndpoint(key, value, isPrefix = false, watchName, Set(DeleteEvent))
    service.underlyingActor.deleteWatchers.size shouldBe 1

    service ! WatchEndpoint(key, value, isPrefix = false, watchName, Set(PutEvent))
    service.underlyingActor.putWatchers.size shouldBe 1

    service ! WatchEndpoint(key, value, isPrefix = false, watchName + 1, Set(DeleteEvent, PutEvent))
    service.underlyingActor.deleteWatchers.size shouldBe 2
    service.underlyingActor.putWatchers.size shouldBe 2

    service ! WatchEndpoint(key, value, isPrefix = true, watchName, Set(DeleteEvent))
    service.underlyingActor.prefixDeleteWatchers.size shouldBe 1

    service ! WatchEndpoint(key, value, isPrefix = true, watchName, Set(PutEvent))
    service.underlyingActor.prefixPutWatchers.size shouldBe 1

    service ! WatchEndpoint(key, value, isPrefix = true, watchName + 1, Set(DeleteEvent, PutEvent))
    service.underlyingActor.prefixDeleteWatchers.size shouldBe 2
    service.underlyingActor.prefixPutWatchers.size shouldBe 2
  }

  it should "close the watcher upon UnWatchEndpoint event" in {
    val etcdClient = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"

    watchEtcd(etcdClient)

    val service = TestActorRef(new WatcherService(etcdClient))

    service ! WatchEndpoint(key, value, isPrefix = false, watchName, Set(DeleteEvent))
    service.underlyingActor.deleteWatchers.size shouldBe 1

    service ! WatchEndpoint(key, value, isPrefix = false, watchName + "1", Set(DeleteEvent))
    service.underlyingActor.deleteWatchers.size shouldBe 2

    service ! UnwatchEndpoint(key, isPrefix = false, watchName)
    service.underlyingActor.deleteWatchers.size shouldBe 1

    service ! UnwatchEndpoint(key, isPrefix = false, watchName + "1")
    service.underlyingActor.deleteWatchers.size shouldBe 0
  }

  it should "notify the recipient if a deletion or put event occurs" in {
    val etcdClient = new MockWatchClient(client)(ece)
    val key = "testKey"
    val value = "testValue"

    val probe = TestProbe()
    val service = TestActorRef(new WatcherService(etcdClient))
    val request = WatchEndpoint(key, value, isPrefix = false, watchName, Set(DeleteEvent, PutEvent))

    probe.send(service, request)

    service.underlyingActor.deleteWatchers.size shouldBe 1
    service.underlyingActor.putWatchers.size shouldBe 1

    etcdClient.onNext should not be null

    etcdClient.publishEvents(EventType.DELETE, key, value)
    probe.expectMsg(WatchEndpointRemoved(request.key, key, value, request.isPrefix))

    etcdClient.publishEvents(EventType.PUT, key, value)
    probe.expectMsg(WatchEndpointInserted(request.key, key, value, request.isPrefix))

    service ! UnwatchEndpoint(key, false, watchName)

    val request2 = WatchEndpoint("test", "", isPrefix = true, watchName, Set(DeleteEvent, PutEvent))
    probe.send(service, request2)

    etcdClient.publishEvents(EventType.DELETE, key, value)
    probe.expectMsg(WatchEndpointRemoved(request2.key, key, value, request2.isPrefix))

    etcdClient.publishEvents(EventType.PUT, key, value)
    probe.expectMsg(WatchEndpointInserted(request2.key, key, value, request2.isPrefix))
  }

  it should "not notify the recipient if event type mismatched" in {
    val etcdClient = new MockWatchClient(client)(ece)
    val key = "testKey"
    val value = "testValue"

    val probe = TestProbe()
    val service = TestActorRef(new WatcherService(etcdClient))
    val request = WatchEndpoint(key, value, isPrefix = false, watchName, Set(DeleteEvent))

    probe.send(service, request)

    service.underlyingActor.deleteWatchers.size shouldBe 1

    etcdClient.onNext should not be null

    etcdClient.publishEvents(EventType.PUT, key, value)
    probe.expectNoMessage()
    service ! UnwatchEndpoint(key, false, watchName) // close the watcher for delete event

    val request2 = WatchEndpoint(key, value, isPrefix = false, watchName, Set(PutEvent))

    probe.send(service, request2)

    service.underlyingActor.putWatchers.size shouldBe 1

    etcdClient.onNext should not be null

    etcdClient.publishEvents(EventType.DELETE, key, value)
    probe.expectNoMessage()
  }

  it should "not register a watch request if there is already registered one" in {
    val etcdClient = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val numOfTries = 3

    watchEtcd(etcdClient)

    val service = TestActorRef(new WatcherService(etcdClient))

    (1 to numOfTries).foreach { _ =>
      service ! WatchEndpoint(key, value, isPrefix = false, watchName, Set(DeleteEvent))
    }

    service.underlyingActor.deleteWatchers.size shouldBe 1
  }

  it should "register a watch request if there is already registered one but with different watch name" in {
    val etcdClient = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val numOfTries = 3

    watchEtcd(etcdClient)

    val service = TestActorRef(new WatcherService(etcdClient))

    (1 to numOfTries).foreach { index =>
      service ! WatchEndpoint(key, value, isPrefix = false, watchName + index, Set(DeleteEvent))
    }

    service.underlyingActor.deleteWatchers.size shouldBe 3
  }

}

class mockWatchUpdate extends WatchUpdate {
  private val eventLists: util.List[Event] = new util.ArrayList[Event]()
  override def getHeader: ResponseHeader = ???

  def addEvents(event: Event): WatchUpdate = {
    eventLists.add(event)
    this
  }

  override def getEvents: util.List[Event] = eventLists
}

class MockWatchClient(client: Client)(ece: ExecutionContextExecutor) extends EtcdClient(client)(ece) {
  var onNext: WatchUpdate => Unit = null

  override def watchAllKeys(next: WatchUpdate => Unit, error: Throwable => Unit, completed: () => Unit): Watch = {
    onNext = next
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

  def publishEvents(eventType: EventType, key: String, value: String): Unit = {
    val eType = eventType match {
      case EventType.PUT          => EventType.PUT
      case EventType.DELETE       => EventType.DELETE
      case EventType.UNRECOGNIZED => EventType.UNRECOGNIZED
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
    onNext(new mockWatchUpdate().addEvents(event))
  }
}
