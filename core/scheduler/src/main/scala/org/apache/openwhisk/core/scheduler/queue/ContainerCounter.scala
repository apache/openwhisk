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

package org.apache.openwhisk.core.scheduler.queue

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys
import org.apache.openwhisk.core.service.{DeleteEvent, PutEvent, UnwatchEndpoint, WatchEndpoint, WatchEndpointOperation}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

class ContainerCounter(invocationNamespace: String, etcdClient: EtcdClient, watcherService: ActorRef)(
  implicit val actorSystem: ActorSystem,
  ec: ExecutionContext,
  logging: Logging) {
  private[queue] var existingContainerNumByNamespace: Int = 0
  private[queue] var inProgressContainerNumByNamespace: Int = 0
  private[queue] val references = new AtomicInteger(0)
  private val watcherName = s"container-counter-$invocationNamespace"

  private val inProgressContainerPrefixKeyByNamespace =
    ContainerKeys.inProgressContainerPrefixByNamespace(invocationNamespace)
  private val existingContainerPrefixKeyByNamespace =
    ContainerKeys.existingContainersPrefixByNamespace(invocationNamespace)

  private val watchedKeys = Seq(inProgressContainerPrefixKeyByNamespace, existingContainerPrefixKeyByNamespace)

  private val watcher =
    actorSystem.actorOf(Props(new Actor {
      private var countingKeys = Set.empty[String]
      private var waitingForCountKeys = Set.empty[String]

      override def receive: Receive = {
        case operation: WatchEndpointOperation if operation.isPrefix =>
          if (countingKeys
                .contains(operation.watchKey))
            waitingForCountKeys += operation.watchKey
          else {
            countingKeys += operation.watchKey
            refreshContainerCount(operation.watchKey)
          }

        case ReadyToGetCount(key) =>
          if (waitingForCountKeys.contains(key)) {
            waitingForCountKeys -= key
            refreshContainerCount(key)
          } else
            countingKeys -= key
      }
    }))

  private def refreshContainerCount(key: String): Future[Unit] = {
    etcdClient
      .getCount(key)
      .map { count =>
        key match {
          case `inProgressContainerPrefixKeyByNamespace` => inProgressContainerNumByNamespace = count.toInt
          case `existingContainerPrefixKeyByNamespace`   => existingContainerNumByNamespace = count.toInt
        }
        watcher ! ReadyToGetCount(key)
      }
      .recover {
        case t: Throwable =>
          logging.error(
            this,
            s"failed to get the number of existing containers for ${invocationNamespace} due to ${t}.")
          watcher ! ReadyToGetCount(key)
      }
  }

  def increaseReference(): ContainerCounter = {
    if (references.incrementAndGet() == 1) {
      watchedKeys.foreach { key =>
        watcherService.tell(WatchEndpoint(key, "", true, watcherName, Set(PutEvent, DeleteEvent)), watcher)
      }

    }
    this
  }

  def close(): Unit = {
    if (references.decrementAndGet() == 0) {
      watchedKeys.foreach { key =>
        watcherService ! UnwatchEndpoint(key, true, watcherName)
      }
      NamespaceContainerCount.instances.remove(invocationNamespace)
    }
  }
}

object NamespaceContainerCount {
  private[queue] val instances = TrieMap[String, ContainerCounter]()
  def apply(namespace: String, etcdClient: EtcdClient, watcherService: ActorRef)(implicit actorSystem: ActorSystem,
                                                                                 ec: ExecutionContext,
                                                                                 logging: Logging): ContainerCounter = {
    instances
      .getOrElseUpdate(namespace, new ContainerCounter(namespace, etcdClient, watcherService))
      .increaseReference()
  }
}

case class ReadyToGetCount(key: String)
