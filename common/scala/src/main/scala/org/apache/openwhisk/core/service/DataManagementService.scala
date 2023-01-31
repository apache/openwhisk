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

import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.util.Timeout
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.etcd.{EtcdFollower, EtcdLeader}
import pureconfig.loadConfigOrThrow

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{Map, Queue}
import scala.concurrent.duration._

// messages received by the actor
// it is required to specify a recipient directly for the retryable message processing
case class ElectLeader(key: String, value: String, recipient: ActorRef, watchEnabled: Boolean = true)
case class RegisterInitialData(key: String,
                               value: String,
                               failoverEnabled: Boolean = true,
                               recipient: Option[ActorRef] = None)

case class RegisterData(key: String, value: String, failoverEnabled: Boolean = true)
case class UnregisterData(key: String)
case class UpdateDataOnChange(key: String, value: String)

// messages sent by the actor
case class ElectionResult(leadership: Either[EtcdFollower, EtcdLeader])
case class FinishWork(key: String)
case class InitialDataStorageResults(key: String, result: Either[AlreadyExist, Done])
case class Done()
case class AlreadyExist()

/**
 * This service is in charge of storing given data to ETCD.
 * In the event any issue occurs while storing data, the actor keeps trying until the data is stored guaranteeing delivery to ETCD.
 * So it guarantees the data is eventually stored.
 */
class DataManagementService(watcherService: ActorRef, workerFactory: ActorRefFactory => ActorRef)(
  implicit logging: Logging,
  actorSystem: ActorSystem)
    extends Actor {
  private implicit val ec = context.dispatcher

  implicit val requestTimeout: Timeout = Timeout(5.seconds)
  private[service] val dataCache = TrieMap[String, String]()
  private val operations = Map.empty[String, Queue[Any]]
  private var inProgressKeys = Set.empty[String]
  private val watcherName = "data-management-service"

  private val worker = workerFactory(context)

  override def receive: Receive = {
    case FinishWork(key) =>
      // send waiting operation to worker if there is any, else update the inProgressKeys
      val ops = operations.get(key)
      if (ops.nonEmpty && ops.get.nonEmpty) {
        val operation = ops.get.dequeue()
        worker ! operation
      } else {
        inProgressKeys = inProgressKeys - key
        operations.remove(key) // remove empty queue from the map to free memory
      }

    // normally these messages will be sent when queues are created.
    case request: ElectLeader =>
      if (inProgressKeys.contains(request.key)) {
        logging.info(this, s"save a request $request into a buffer")
        operations.getOrElseUpdate(request.key, Queue.empty[Any]).enqueue(request)
      } else {
        worker ! request
        inProgressKeys = inProgressKeys + request.key
      }

    case request: RegisterInitialData =>
      // send WatchEndpoint first as the put operation will be retried until success if failed
      if (request.failoverEnabled)
        watcherService ! WatchEndpoint(request.key, request.value, isPrefix = false, watcherName, Set(DeleteEvent))
      if (inProgressKeys.contains(request.key)) {
        logging.info(this, s"save request $request into a buffer")
        operations.getOrElseUpdate(request.key, Queue.empty[Any]).enqueue(request)
      } else {
        worker ! request
        inProgressKeys = inProgressKeys + request.key
      }

    case request: RegisterData =>
      // send WatchEndpoint first as the put operation will be retried until success if failed
      if (request.failoverEnabled)
        watcherService ! WatchEndpoint(request.key, request.value, isPrefix = false, watcherName, Set(DeleteEvent))
      if (inProgressKeys.contains(request.key)) {
        // the new put|delete operation will erase influences made by older operations like put&delete
        // so we can remove these old operations
        logging.info(this, s"save request $request into a buffer")
        val queue = operations.getOrElseUpdate(request.key, Queue.empty[Any]).filter { value =>
          value match {
            case _: RegisterData | _: WatcherClosed | _: RegisterInitialData => false
            case _                                                           => true
          }
        }
        queue.enqueue(request)
        operations.update(request.key, queue)
      } else {
        worker ! request
        inProgressKeys = inProgressKeys + request.key
      }

    case request: WatcherClosed =>
      if (inProgressKeys.contains(request.key)) {
        // The put|delete operations against the same key will overwrite the previous results.
        // For example, if we put a value, delete it and put a new value again, the final result will be the new value.
        // So we can remove these old operations
        logging.info(this, s"save request $request into a buffer")
        val queue = operations.getOrElseUpdate(request.key, Queue.empty[Any]).filter { value =>
          value match {
            case _: RegisterData | _: WatcherClosed | _: RegisterInitialData => false
            case _                                                           => true
          }
        }
        queue.enqueue(request)
        operations.update(request.key, queue)
      } else {
        worker ! request
        inProgressKeys = inProgressKeys + request.key
      }

    // It is required to close the watcher first before deleting etcd data
    // It is supposed to receive the WatcherClosed message after the watcher is stopped.
    case msg: UnregisterData =>
      watcherService ! UnwatchEndpoint(msg.key, isPrefix = false, watcherName, needFeedback = true)

    case WatchEndpointRemoved(_, key, value, false) =>
      self ! RegisterInitialData(key, value, failoverEnabled = false) // the watcher is already setup

    // It should not receive "prefixed" data
    case WatchEndpointRemoved(_, key, value, true) =>
      logging.error(this, s"unexpected data received: ${WatchEndpoint(key, value, isPrefix = true, watcherName)}")

    case msg: UpdateDataOnChange =>
      dataCache.get(msg.key) match {
        case Some(cached) if cached == msg.value =>
          logging.debug(this, s"skip publishing data ${msg.key} because the data is not changed.")

        case Some(cached) if cached != msg.value =>
          dataCache.update(msg.key, msg.value)
          self ! RegisterData(msg.key, msg.value, failoverEnabled = false) // the watcher is already setup

        case None =>
          dataCache.put(msg.key, msg.value)
          self ! RegisterData(msg.key, msg.value)

      }
  }
}

object DataManagementService {
  val retryInterval: FiniteDuration = loadConfigOrThrow[FiniteDuration](ConfigKeys.dataManagementServiceRetryInterval)

  def props(watcherService: ActorRef, workerFactory: ActorRefFactory => ActorRef)(implicit logging: Logging,
                                                                                  actorSystem: ActorSystem): Props = {
    Props(new DataManagementService(watcherService, workerFactory))
  }
}
