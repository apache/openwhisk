package org.apache.openwhisk.core.etcd

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Timers}
import io.grpc.StatusRuntimeException
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.etcd.EtcdWorker.GetLeaseAndRetry
import org.apache.openwhisk.core.service.DataManagementService.retryInterval
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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.Success

class EtcdWorker(etcdClient: EtcdClient, leaseService: ActorRef)(implicit val ec: ExecutionContext,
                                                                 actorSystem: ActorSystem,
                                                                 logging: Logging)
    extends Actor
    with Timers {

  private val dataManagementService = context.parent
  private var lease: Option[Lease] = None
  leaseService ! GetLease

  override def receive: Receive = {
    case msg: Lease =>
      lease = Some(msg)
    case msg: GetLeaseAndRetry =>
      logging.warn(this, msg.log)
      if (!msg.skipLeaseRefresh) {
        if (msg.clearLease) {
          lease = None
        }
        leaseService ! GetLease
      }
      sendMessageToSelfAfter(msg.request, retryInterval)
    // leader election + endpoint management
    case request: ElectLeader =>
      lease match {
        case Some(l) =>
          etcdClient
            .electLeader(request.key, request.value, l)
            .andThen {
              case Success(msg) =>
                request.recipient ! ElectionResult(msg)
                dataManagementService ! FinishWork(request.key)
            }
            .recover {
              // if there is no lease, reissue it and retry immediately
              case t: StatusRuntimeException =>
                self ! GetLeaseAndRetry(request, s"a lease is expired while leader election, reissue it: $t")
              // it should retry forever until the data is stored
              case t: Throwable =>
                self ! GetLeaseAndRetry(
                  request,
                  s"unexpected error happened: $t, retry storing data",
                  skipLeaseRefresh = true)
            }
        case None =>
          self ! GetLeaseAndRetry(request, s"lease not found, retry storing data ${request.key}", clearLease = false)
      }

    // only endpoint management
    case request: RegisterData =>
      lease match {
        case Some(l) =>
          etcdClient
            .put(request.key, request.value, l.id)
            .andThen {
              case Success(_) =>
                dataManagementService ! FinishWork(request.key)
            }
            .recover {
              // if there is no lease, reissue it and retry immediately
              case t: StatusRuntimeException =>
                self ! GetLeaseAndRetry(
                  request,
                  s"a lease is expired while registering data ${request.key}, reissue it: $t")
              // it should retry forever until the data is stored
              case t: Throwable =>
                self ! GetLeaseAndRetry(
                  request,
                  s"unexpected error happened: $t, retry storing data ${request.key}",
                  skipLeaseRefresh = true)
            }
        case None =>
          self ! GetLeaseAndRetry(request, s"lease not found, retry storing data ${request.key}", clearLease = false)
      }
    // it stores the data iif there is no such one
    case request: RegisterInitialData =>
      lease match {
        case Some(l) =>
          etcdClient
            .putTxn(request.key, request.value, 0, l.id)
            .map { res =>
              dataManagementService ! FinishWork(request.key)
              if (res.getSucceeded) {
                logging.info(this, s"initial data storing succeeds for ${request.key}")
                request.recipient.map(_ ! InitialDataStorageResults(request.key, Right(Done())))
              } else {
                logging.info(this, s"data is already stored for: $request, cancel the initial data storing")
                request.recipient.map(_ ! InitialDataStorageResults(request.key, Left(AlreadyExist())))
              }
            }
            .recover {
              // if there is no lease, reissue it and retry immediately
              case t: StatusRuntimeException =>
                self ! GetLeaseAndRetry(
                  request,
                  s"a lease is expired while registering an initial data ${request.key}, reissue it: $t")
              // it should retry forever until the data is stored
              case t: Throwable =>
                self ! GetLeaseAndRetry(
                  request,
                  s"unexpected error happened: $t, retry storing data ${request.key}",
                  skipLeaseRefresh = true)
            }
        case None =>
          self ! GetLeaseAndRetry(request, s"lease not found, retry storing data ${request.key}", clearLease = false)
      }

    case msg: WatcherClosed =>
      etcdClient
        .del(msg.key)
        .andThen {
          case Success(_) =>
            dataManagementService ! FinishWork(msg.key)
        }
        .recover {
          // if there is no lease, reissue it and retry immediately
          case t: StatusRuntimeException =>
            self ! GetLeaseAndRetry(msg, s"a lease is expired while deleting data ${msg.key}, reissue it: $t")
          // it should retry forever until the data is stored
          case t: Throwable =>
            self ! GetLeaseAndRetry(
              msg,
              s"unexpected error happened: $t, retry storing data for ${msg.key}",
              skipLeaseRefresh = true)
        }
  }

  private def sendMessageToSelfAfter(msg: Any, retryInterval: FiniteDuration) = {
    timers.startSingleTimer(msg, msg, retryInterval)
  }
}

object EtcdWorker {
  case class GetLeaseAndRetry(request: Any, log: String, clearLease: Boolean = true, skipLeaseRefresh: Boolean = false)

  def props(etcdClient: EtcdClient, leaseService: ActorRef)(implicit ec: ExecutionContext,
                                                            actorSystem: ActorSystem,
                                                            logging: Logging): Props = {
    Props(new EtcdWorker(etcdClient, leaseService))
  }
}
