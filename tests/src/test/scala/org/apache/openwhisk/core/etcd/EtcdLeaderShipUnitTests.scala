package org.apache.openwhisk.core.etcd

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

import com.google.protobuf.ByteString
import common.{StreamLogging, WskActorSystem}
import io.etcd.jetcd.api.KeyValue
import io.etcd.jetcd.kv.TxnResponse
import io.etcd.jetcd.lease.{LeaseGrantResponse, LeaseKeepAliveResponse}
import io.etcd.jetcd.watch.WatchEvent.EventType
import io.etcd.jetcd.watch.WatchResponse
import io.etcd.jetcd.{api, Client, Watch}
import io.grpc.{StatusRuntimeException, Status => GrpcStatus}
import org.junit.runner.RunWith
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

@RunWith(classOf[JUnitRunner])
class EtcdLeaderShipUnitTests extends FlatSpec with ScalaFutures with Matchers with WskActorSystem with StreamLogging {

  implicit val timeout = Timeout(2.seconds)
  private val leaderKey = "openwhiskleader"
  private val endpoints = "endpoints"
  private val lease = Lease(60, 5)

  class MockEtcdLeadershipApi(implicit val ec: ExecutionContext) extends EtcdLeadershipApi {
    override val client: Client = {
      val addresses: List[URI] = "172.17.0.1:2379"
        .split(",")
        .toList
        .map(hp => {
          val host :: port :: Nil = hp.split(":").toList
          URI.create(s"http://$host:$port")
        })
      Client.builder().endpoints(addresses.asJava).build()
    }

    var onNext: WatchResponse => Unit = null

    override def grant(ttl: Long): Future[LeaseGrantResponse] =
      Future.successful(new LeaseGrantResponse(api.LeaseGrantResponse.newBuilder().setID(lease.id).setTTL(ttl).build()))

    override def keepAliveOnce(lease: Lease): Future[LeaseKeepAliveResponse] =
      Future.successful(
        new LeaseKeepAliveResponse(api.LeaseKeepAliveResponse.newBuilder().setID(lease.id).setTTL(lease.ttl).build()))

    override def putTxn(key: String, value: String, cmpVersion: Int, lease: Lease): Future[TxnResponse] =
      Future.successful(new TxnResponse(api.TxnResponse.newBuilder().setSucceeded(true).build()))

    override def watch(key: String, isPrefix: Boolean)(next: WatchResponse => Unit,
                                                       error: Throwable => Unit,
                                                       completed: () => Unit): Watch.Watcher = {
      onNext = next
      () =>
        {}
    }

    def publishEvents(eventType: EventType, key: String, value: String): Unit = {
      val eType = eventType match {
        case EventType.PUT          => api.Event.EventType.PUT
        case EventType.DELETE       => api.Event.EventType.DELETE
        case EventType.UNRECOGNIZED => api.Event.EventType.UNRECOGNIZED
      }
      val event = api.Event
        .newBuilder()
        .setType(eType)
        .setKv(
          KeyValue
            .newBuilder()
            .setKey(ByteString.copyFromUtf8(key))
            .setValue(ByteString.copyFromUtf8(value))
            .build()) build ()
      onNext(new WatchResponse(api.WatchResponse.newBuilder().addEvents(event).build()))
    }
  }

  behavior of "Etcd Leadership Client"

  "Etcd LeaderShip client" should "elect leader successfully" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, lease).futureValue(timeout)
    either.right.get shouldBe EtcdLeader(leaderKey, endpoints, lease)
  }

  "Etcd LeaderShip client" should "be failed to elect leader" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn(key: String, value: String, cmpVersion: Int, lease: Lease): Future[TxnResponse] =
        Future.successful(new TxnResponse(api.TxnResponse.newBuilder().setSucceeded(false).build()))
    }

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, lease).futureValue(timeout)
    either.left.get shouldBe EtcdFollower(leaderKey, endpoints)

  }

  "Etcd LeaderShip client" should "elect leader successfully with provided lease" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, lease).futureValue(timeout)
    either.right.get shouldBe EtcdLeader(leaderKey, endpoints, lease)
  }

  "Etcd LeaderShip client" should "be failed to elect leader with provided lease" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn(key: String, value: String, cmpVersion: Int, lease: Lease): Future[TxnResponse] =
        Future.successful(new TxnResponse(api.TxnResponse.newBuilder().setSucceeded(false).build()))
    }

    val either = mockLeaderShipClient.electLeader(leaderKey, endpoints, lease).futureValue(timeout)
    either.left.get shouldBe EtcdFollower(leaderKey, endpoints)
  }

  "Etcd LeaderShip client" should "throw StatusRuntimeException when provided lease doesn't exist" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi() {
      override def putTxn(key: String, value: String, cmpVersion: Int, lease: Lease): Future[TxnResponse] =
        Future.failed(new StatusRuntimeException(GrpcStatus.NOT_FOUND))
    }

    mockLeaderShipClient
      .electLeader(leaderKey, endpoints, lease)
      .failed
      .futureValue shouldBe a[StatusRuntimeException]
  }

  "Etcd LeaderShip client" should "keep alive leader key" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    mockLeaderShipClient.keepAliveLeader(lease).futureValue(timeout) shouldBe lease.id
  }

  "Etcd LeaderShip client" should "watch leader listening on event" in {
    val mockLeaderShipClient = new MockEtcdLeadershipApi

    val resignKeyRef = new AtomicReference[String]
    val resignValueRef = new AtomicReference[String]
    val changeKeyRef = new AtomicReference[String]
    val changeValueRef = new AtomicReference[String]

    def leaderResigned(key: String, value: String): Unit = {
      resignKeyRef.set(key)
      resignValueRef.set(value)
    }

    def leaderChanged(key: String, value: String): Unit = {
      changeKeyRef.set(key)
      changeValueRef.set(value)
    }

    mockLeaderShipClient.watchLeader(leaderKey)(leaderResigned, leaderChanged)

    mockLeaderShipClient.publishEvents(EventType.DELETE, leaderKey, endpoints)
    resignKeyRef.get() shouldBe leaderKey
    resignValueRef.get() shouldBe endpoints

    mockLeaderShipClient.publishEvents(EventType.PUT, leaderKey, endpoints)
    changeKeyRef.get() shouldBe leaderKey
    changeValueRef.get() shouldBe endpoints

  }

}
