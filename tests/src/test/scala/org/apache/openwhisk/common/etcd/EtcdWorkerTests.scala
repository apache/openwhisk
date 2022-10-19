package org.apache.openwhisk.common.etcd

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestActor, TestActorRef, TestKit, TestProbe}
import akka.util.Timeout
import com.ibm.etcd.api.{DeleteRangeResponse, PutResponse, TxnResponse}
import common.StreamLogging
import io.grpc.{Status, StatusRuntimeException}
import org.apache.openwhisk.core.entity.SchedulerInstanceId
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdLeader, EtcdWorker}
import org.apache.openwhisk.core.service.{
  AlreadyExist,
  Done,
  ElectLeader,
  ElectionResult,
  FinishWork,
  GetLease,
  InitialDataStorageResults,
  Lease,
  RegisterData,
  RegisterInitialData,
  WatcherClosed
}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class EtcdWorkerTests
    extends TestKit(ActorSystem("EtcdWorker"))
    with ImplicitSender
    with FlatSpecLike
    with ScalaFutures
    with Matchers
    with MockFactory
    with BeforeAndAfterAll
    with StreamLogging {

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val ec: ExecutionContext = system.dispatcher
  val leaseService = TestProbe()
  val leaseId = 10
  val leaseTtl = 10
  leaseService.setAutoPilot((sender: ActorRef, msg: Any) =>
    msg match {
      case GetLease =>
        sender ! Lease(leaseId, leaseTtl)
        TestActor.KeepRunning

      case _ =>
        TestActor.KeepRunning
  })

  //val dataManagementService = TestProbe()
  val schedulerId = SchedulerInstanceId("scheduler0")
  val instanceId = schedulerId

  behavior of "EtcdWorker"

  it should "elect leader and send completion ack to parent" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val leader = Right(EtcdLeader(key, value, leaseId))
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)

    (mockEtcd
      .electLeader(_: String, _: String, _: Lease))
      .expects(key, value, *)
      .returns(Future.successful(leader))

    etcdWorker ! ElectLeader(key, value, recipient = self)

    expectMsg(ElectionResult(leader))
    expectMsg(FinishWork(key))
  }

  it should "register initial data when doesn't exit and send completion ack to parent" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)

    (mockEtcd
      .putTxn(_: String, _: String, _: Long, _: Long))
      .expects(key, value, *, *)
      .returns(Future.successful(TxnResponse.newBuilder().setSucceeded(true).build()))

    etcdWorker ! RegisterInitialData(key, value, recipient = Some(self))

    expectMsg(FinishWork(key))
    expectMsg(InitialDataStorageResults(key, Right(Done())))
  }

  it should "attempt to register initial data when exists and send completion ack to parent" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)

    (mockEtcd
      .putTxn(_: String, _: String, _: Long, _: Long))
      .expects(key, value, *, *)
      .returns(Future.successful(TxnResponse.newBuilder().setSucceeded(false).build()))

    etcdWorker ! RegisterInitialData(key, value, recipient = Some(self))

    expectMsg(FinishWork(key))
    expectMsg(InitialDataStorageResults(key, Left(AlreadyExist())))
  }

  it should "register data and send completion ack to parent" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val value = "testValue"
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)

    (mockEtcd
      .put(_: String, _: String, _: Long))
      .expects(key, value, leaseId)
      .returns(Future.successful(PutResponse.newBuilder().build()))

    etcdWorker ! RegisterData(key, value)

    expectMsg(FinishWork(key))
  }

  it should "delete data when watcher closed" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)

    (mockEtcd
      .del(_: String))
      .expects(key)
      .returns(Future.successful(DeleteRangeResponse.newBuilder().build()))

    etcdWorker ! WatcherClosed(key, false)

    expectMsg(FinishWork(key))
  }

  it should "retry request after failure if lease does not exist" in {
    val mockEtcd = mock[EtcdClient]

    val key = "testKey"
    val etcdWorker = TestActorRef(EtcdWorker.props(mockEtcd, leaseService.ref), self)
    var firstAttempt = true
    (mockEtcd
      .del(_: String))
      .expects(key)
      .onCall((_: String) => {
        if (firstAttempt) {
          firstAttempt = false
          Future.failed(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED))
        } else {
          Future.successful(DeleteRangeResponse.newBuilder().build())
        }
      })
      .twice()

    etcdWorker ! WatcherClosed(key, false)

    expectMsg(FinishWork(key))
  }
}
