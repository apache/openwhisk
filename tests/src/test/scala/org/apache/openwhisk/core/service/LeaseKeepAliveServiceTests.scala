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

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestFSMRef, TestKit, TestProbe}
import akka.util.Timeout
import com.ibm.etcd.api.{LeaseGrantResponse, LeaseKeepAliveResponse, LeaseRevokeResponse, PutResponse}
import common.StreamLogging
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.{ExecManifest, SchedulerInstanceId}
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdKV}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@RunWith(classOf[JUnitRunner])
class LeaseKeepAliveServiceTests
    extends TestKit(ActorSystem("LeaseKeepAliveService"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = system.dispatcher
  val config = new WhiskConfig(ExecManifest.requiredProperties)
  val testInstanceId = SchedulerInstanceId("0")
  val testLeaseId = 10
  val newTestLeaseId = 20
  val testTtl = 1
  val testLease = Lease(testLeaseId, testTtl)
  val newTestLease = Lease(newTestLeaseId, testTtl)
  val testKey = EtcdKV.InstanceKeys.instanceLease(testInstanceId)
  val newTestKey = EtcdKV.InstanceKeys.instanceLease(testInstanceId)

  val watcherName = "lease-service"

  def grant(etcd: EtcdClient): Unit = {
    (etcd
      .grant(_: Long))
      .expects(*)
      .returning(Future.successful(LeaseGrantResponse.newBuilder().setID(testLeaseId).setTTL(testTtl).build()))
  }

  def put(etcd: EtcdClient): Unit = {
    (etcd
      .put(_: String, _: String, _: Long))
      .expects(testKey, *, *)
      .returning(Future.successful(PutResponse.newBuilder().build()))
  }

  def keepAliveOnce(etcd: EtcdClient): Unit = {
    (etcd
      .keepAliveOnce(_: Long))
      .expects(testLeaseId)
      .returning(Future.successful(LeaseKeepAliveResponse.newBuilder().setID(testLeaseId).build()))
      .anyNumberOfTimes()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  behavior of "LeaseKeepAliveService"

  it should "grant new lease" in {

    val mockEtcd = mock[EtcdClient]
    grant(mockEtcd)
    put(mockEtcd)
    keepAliveOnce(mockEtcd)

    val watcher = TestProbe()
    val service = TestFSMRef(new LeaseKeepAliveService(mockEtcd, testInstanceId, watcher.ref))

    Thread.sleep(1000)
    service.stateName shouldBe Active
    service.stateData shouldBe a[ActiveStates]
    service.stateData match {
      case ActiveStates(_, lease) => lease shouldBe testLease
      case _                      => fail()
    }
    watcher.expectMsg(WatchEndpoint(testKey, testLease.id.toString, false, watcherName, Set(DeleteEvent)))

  }

  it should "regrant a new lease while old lease is deleted" in {
    val mockEtcd = mock[EtcdClient]
    grant(mockEtcd)
    put(mockEtcd)
    keepAliveOnce(mockEtcd)

    (mockEtcd
      .revoke(_: Long))
      .expects(testLeaseId)
      .returning(Future.successful(LeaseRevokeResponse.newBuilder().build()))
    (mockEtcd
      .grant(_: Long))
      .expects(*)
      .returning(Future.successful(LeaseGrantResponse.newBuilder().setID(newTestLeaseId).setTTL(testTtl).build()))
    (mockEtcd
      .put(_: String, _: String, _: Long))
      .expects(newTestKey, *, *)
      .returning(Future.successful(PutResponse.newBuilder().build()))
    (mockEtcd
      .keepAliveOnce(_: Long))
      .expects(newTestLeaseId)
      .returning(Future.successful(LeaseKeepAliveResponse.newBuilder().setID(newTestLeaseId).build()))
      .anyNumberOfTimes()

    val watcher = TestProbe()
    val service = TestFSMRef(new LeaseKeepAliveService(mockEtcd, testInstanceId, watcher.ref))

    service.stateName shouldBe Active
    service.stateData shouldBe a[ActiveStates]
    service.stateData match {
      case ActiveStates(_, lease) => lease shouldBe testLease
      case _                      => fail()
    }
    watcher.expectMsg(WatchEndpoint(testKey, testLease.id.toString, false, watcherName, Set(DeleteEvent)))

    service ! WatchEndpointRemoved(testKey, testKey, testLease.id.toString, false)

    watcher.expectMsg(UnwatchEndpoint(testKey, false, watcherName))
    Thread.sleep(500) //wait for the lease to be granted

    service.stateName shouldBe Active
    service.stateData shouldBe a[ActiveStates]
    service.stateData match {
      case ActiveStates(_, lease) => lease shouldBe newTestLease
      case _                      => fail()
    }
    watcher.expectMsg(WatchEndpoint(newTestKey, newTestLease.id.toString, false, watcherName, Set(DeleteEvent)))
  }

  it should "get lease" in {
    val mockEtcd = mock[EtcdClient]
    grant(mockEtcd)
    put(mockEtcd)
    keepAliveOnce(mockEtcd)

    val service = TestFSMRef(new LeaseKeepAliveService(mockEtcd, testInstanceId, TestProbe().ref))

    (service ? GetLease).mapTo[Lease].futureValue shouldBe testLease
  }

  it should "regrant a new lease when keepalive is failed" in {
    val mockEtcd = mock[EtcdClient]
    grant(mockEtcd)
    put(mockEtcd)

    (mockEtcd
      .keepAliveOnce(_: Long))
      .expects(testLeaseId)
      .returning(Future.successful(LeaseKeepAliveResponse.newBuilder().setID(testLeaseId).build()))
      .noMoreThanTwice()

    (mockEtcd
      .keepAliveOnce(_: Long))
      .expects(testLeaseId)
      .returning(Future.failed(new RuntimeException("failed to keep alive the lease")))
      .noMoreThanOnce()

    (mockEtcd
      .revoke(_: Long))
      .expects(testLeaseId)
      .returning(Future.successful(LeaseRevokeResponse.newBuilder().build()))

    (mockEtcd
      .keepAliveOnce(_: Long))
      .expects(newTestLeaseId)
      .returning(Future.successful(LeaseKeepAliveResponse.newBuilder().setID(newTestLeaseId).build()))
      .anyNumberOfTimes()

    (mockEtcd
      .grant(_: Long))
      .expects(*)
      .returning(Future.successful(LeaseGrantResponse.newBuilder().setID(newTestLeaseId).setTTL(testTtl).build()))
    (mockEtcd
      .put(_: String, _: String, _: Long))
      .expects(newTestKey, *, *)
      .returning(Future.successful(PutResponse.newBuilder().build()))

    val watcher = TestProbe()
    val service = TestFSMRef(new LeaseKeepAliveService(mockEtcd, testInstanceId, watcher.ref))
    service.stateName shouldBe Active
    service.stateData shouldBe a[ActiveStates]
    service.stateData match {
      case ActiveStates(_, lease) => lease shouldBe testLease
      case _                      => fail()
    }
    watcher.expectMsg(WatchEndpoint(testKey, testLease.id.toString, false, watcherName, Set(DeleteEvent)))

    watcher.expectMsg(UnwatchEndpoint(testKey, false, watcherName))
    Thread.sleep(1500) //wait for the lease to be granted

    service.stateName shouldBe Active
    service.stateData shouldBe a[ActiveStates]
    service.stateData match {
      case ActiveStates(_, lease) => lease shouldBe newTestLease
      case _                      => fail()
    }
    watcher.expectMsg(WatchEndpoint(newTestKey, newTestLease.id.toString, false, watcherName, Set(DeleteEvent)))
  }

}
