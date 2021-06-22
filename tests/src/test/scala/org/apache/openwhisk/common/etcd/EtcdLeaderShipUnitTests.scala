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

package org.apache.openwhisk.common.etcd

import java.util.concurrent.Executor
import java.{lang, util}

import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.api._
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.WatchUpdate
import com.ibm.etcd.client.{EtcdClient => Client}
import common.{StreamLogging, WskActorSystem}
import io.grpc.{StatusRuntimeException, Status => GrpcStatus}
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.etcd.{EtcdFollower, EtcdLeader, EtcdLeadershipApi}
import org.apache.openwhisk.core.service.Lease
import org.junit.runner.RunWith
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

@RunWith(classOf[JUnitRunner])
class EtcdLeaderShipUnitTests extends FlatSpec with ScalaFutures with Matchers with WskActorSystem with StreamLogging {

  implicit val timeout = Timeout(2.seconds)
  private val leaderKey = "openwhiskleader"
  private val endpoints = "endpoints"
  private val leaseId = 60

  class mockWatchUpdate extends WatchUpdate {
    private var eventLists: util.List[Event] = new util.ArrayList[Event]()
    override def getHeader: ResponseHeader = ???

    def addEvents(event: Event): WatchUpdate = {
      eventLists.add(event)
      this
    }

    override def getEvents: util.List[Event] = eventLists
  }

  class MockEtcdLeadershipApi extends EtcdLeadershipApi {

    override implicit val ece: ExecutionContextExecutor = actorSystem.dispatcher

    override val client: Client = {
      val hostAndPorts = "172.17.0.1:2379"
      Client.forEndpoints(hostAndPorts).withPlainText().build()
    }

    var onNext: WatchUpdate => Unit = null

    override def grant(ttl: Long): Future[LeaseGrantResponse] =
      Future.successful(LeaseGrantResponse.newBuilder().setID(leaseId).setTTL(ttl).build())

    override def keepAliveOnce(leaseId: Long): Future[LeaseKeepAliveResponse] =
      Future.successful(LeaseKeepAliveResponse.newBuilder().setID(leaseId).build())

    override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] =
      Future.successful(TxnResponse.newBuilder().setSucceeded(true).build())

    override def watch(key: String, isPrefix: Boolean)(next: WatchUpdate => Unit,
                                                       error: Throwable => Unit,
                                                       completed: () => Unit): Watch = {
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
        .setKv(
          KeyValue
            .newBuilder()
            .setKey(key)
            .setValue(value)
            .build())
        .build()
      onNext(new mockWatchUpdate().addEvents(event))
    }
  }

  behavior of "Etcd Leadership Client"

  "Etcd LeaderShip client" should "elect leader successfully" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints).futureValue(timeout)
    either.right.get shouldBe EtcdLeader(leaderKey, endpoints, leaseId)
  }

  "Etcd LeaderShip client" should "be failed to elect leader" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] =
        Future.successful(TxnResponse.newBuilder().setSucceeded(false).build())
    }

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints).futureValue(timeout)
    either.left.get shouldBe EtcdFollower(leaderKey, endpoints)

  }

  "Etcd LeaderShip client" should "elect leader successfully with provided lease" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, Lease(leaseId, 60)).futureValue(timeout)
    either.right.get shouldBe EtcdLeader(leaderKey, endpoints, leaseId)
  }

  "Etcd LeaderShip client" should "be failed to elect leader with provided lease" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] =
        Future.successful(TxnResponse.newBuilder().setSucceeded(false).build())
    }

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, Lease(leaseId, 60)).futureValue(timeout)
    either.left.get shouldBe EtcdFollower(leaderKey, endpoints)
  }

  "Etcd LeaderShip client" should "throw StatusRuntimeException when provided lease doesn't exist" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] =
        Future.failed(new StatusRuntimeException(GrpcStatus.NOT_FOUND))
    }

    mockLeaderShipClient
      .electLeader(leaderKey, endpoints, Lease(leaseId, 60))
      .failed
      .futureValue shouldBe a[StatusRuntimeException]
  }

  "Etcd LeaderShip client" should "keep alive leader key" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    mockLeaderShipClient.keepAliveLeader(leaseId).futureValue(timeout) shouldBe leaseId
  }

}
