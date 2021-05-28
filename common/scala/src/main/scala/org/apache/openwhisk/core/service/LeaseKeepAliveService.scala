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

import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{ActorRef, ActorSystem, Cancellable, FSM, Props, Stash}
import akka.pattern.pipe
import org.apache.openwhisk.common.{Logging, LoggingMarkers, MetricEmitter}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.InstanceId
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.InstanceKeys.instanceLease
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

// States
sealed trait KeepAliveServiceState
case object Ready extends KeepAliveServiceState
case object Active extends KeepAliveServiceState

// Data
sealed trait KeepAliveServiceData
case object NoData extends KeepAliveServiceData
case class Lease(id: Long, ttl: Long) extends KeepAliveServiceData
case class ActiveStates(worker: Cancellable, lease: Lease) extends KeepAliveServiceData

// Events received by the actor
case object RegrantLease
case object GetLease
case object GrantLease

// Events internally used
case class SetLease(lease: Lease)
case class SetWatcher(worker: Cancellable)

class LeaseKeepAliveService(etcdClient: EtcdClient, instanceId: InstanceId, watcherService: ActorRef)(
  implicit logging: Logging,
  actorSystem: ActorSystem)
    extends FSM[KeepAliveServiceState, KeepAliveServiceData]
    with Stash {

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  private val leaseTimeout = loadConfigOrThrow[Int](ConfigKeys.etcdLeaseTimeout).seconds
  private val key = instanceLease(instanceId)
  private val watcherName = "lease-service"

  self ! GrantLease
  startWith(Ready, NoData)

  when(Ready) {
    case Event(GrantLease, NoData) =>
      etcdClient
        .grant(leaseTimeout.toSeconds)
        .map { res =>
          SetLease(Lease(res.getID, res.getTTL))
        }
        .pipeTo(self)
      stay

    case Event(SetLease(lease), NoData) =>
      startKeepAliveService(lease)
        .pipeTo(self)
      logging.info(this, s"Granted a new lease $lease")
      stay using lease

    case Event(SetWatcher(w), l: Lease) =>
      goto(Active) using ActiveStates(w, l)

    case Event(t: FailureMessage, _) =>
      logging.warn(this, s"Failed to grant new lease caused by: $t")
      self ! GrantLease
      stay()

    case _ => delay
  }

  when(Active) {
    case Event(WatchEndpointRemoved(`key`, `key`, _, false), ActiveStates(worker, lease)) =>
      logging.info(this, s"endpoint ie removed so recreate a lease")
      recreateLease(worker, lease)

    case Event(RegrantLease, ActiveStates(worker, lease)) =>
      logging.info(this, s"ReGrant a lease, old lease:${lease}")
      recreateLease(worker, lease)

    case Event(GetLease, ActiveStates(_, lease)) =>
      logging.info(this, s"send the lease(${lease}) to ${sender()}")
      sender() ! lease
      stay()

    case _ => delay
  }

  initialize()

  private def startKeepAliveService(lease: Lease): Future[SetWatcher] = {
    val worker =
      actorSystem.scheduler.scheduleAtFixedRate(initialDelay = 0.second, interval = 500.milliseconds)(() =>
        keepAliveOnce(lease))

    /**
     * To verify that lease has been deleted since timeout,
     * create a key using lease, watch the key, and receive an event for deletion.
     */
    etcdClient.put(key, s"${lease.id}", lease.id).map { _ =>
      watcherService ! WatchEndpoint(key, s"${lease.id}", false, watcherName, Set(DeleteEvent))
      SetWatcher(worker)
    }
  }

  private def keepAliveOnce(lease: Lease): Future[Long] = {
    etcdClient
      .keepAliveOnce(lease.id)
      .map(_.getID)
      .andThen {
        case Success(_) => MetricEmitter.emitCounterMetric(LoggingMarkers.SCHEDULER_KEEP_ALIVE(lease.id))
        case Failure(t) =>
          logging.warn(this, s"Failed to keep-alive of ${lease.id} caused by ${t}")
          self ! RegrantLease
      }
  }

  private def recreateLease(worker: Cancellable, lease: Lease) = {
    logging.info(this, s"recreate a lease, old lease: $lease")
    worker.cancel() // stop scheduler
    watcherService ! UnwatchEndpoint(key, false, watcherName) // stop watcher
    etcdClient
      .revoke(lease.id) // delete lease
      .onComplete(_ => self ! GrantLease) // create lease
    goto(Ready) using NoData
  }

  // Unstash all messages stashed while in intermediate state
  onTransition {
    case _ -> Ready  => unstashAll()
    case _ -> Active => unstashAll()
  }

  /** Delays all incoming messages until unstashAll() is called */
  def delay = {
    stash()
    stay
  }

  override def postStop(): Unit = {
    stateData match {
      case ActiveStates(w, _) => w.cancel() // stop scheduler if that exist
      case _                  => // do nothing
    }
    watcherService ! UnwatchEndpoint(key, false, watcherName)
  }
}

object LeaseKeepAliveService {
  def props(etcdClient: EtcdClient, instanceId: InstanceId, watcherService: ActorRef)(
    implicit logging: Logging,
    actorSystem: ActorSystem): Props = {
    Props(new LeaseKeepAliveService(etcdClient, instanceId, watcherService))
      .withDispatcher("dispatchers.lease-service-dispatcher")
  }
}
