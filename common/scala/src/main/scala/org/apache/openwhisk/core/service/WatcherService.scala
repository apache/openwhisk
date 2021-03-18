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

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.ibm.etcd.api.Event.EventType
import com.ibm.etcd.client.kv.WatchUpdate
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.etcd.EtcdClient
import org.apache.openwhisk.core.etcd.EtcdType._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

// messages received by this actor
case class WatchEndpoint(key: String,
                         value: String,
                         isPrefix: Boolean,
                         name: String,
                         listenEvents: Set[EtcdEvent] = Set.empty)
case class UnwatchEndpoint(watchKey: String, isPrefix: Boolean, watchName: String, needFeedback: Boolean = false)

// the watchKey is the string user want to watch, it can be a prefix, the key is a record's key in Etcd
// so if `isPrefix = true`, the `watchKey != key`, else the `watchKey == key`
sealed abstract class WatchEndpointOperation(val watchKey: String,
                                             val key: String,
                                             val value: String,
                                             val isPrefix: Boolean)
case class WatchEndpointRemoved(override val watchKey: String,
                                override val key: String,
                                override val value: String,
                                override val isPrefix: Boolean)
    extends WatchEndpointOperation(watchKey, key, value, isPrefix)
case class WatchEndpointInserted(override val watchKey: String,
                                 override val key: String,
                                 override val value: String,
                                 override val isPrefix: Boolean)
    extends WatchEndpointOperation(watchKey, key, value, isPrefix)
case class WatcherClosed(key: String, isPrefix: Boolean)

// These are abstraction for event from ETCD.
sealed trait EtcdEvent
case object PutEvent extends EtcdEvent
case object DeleteEvent extends EtcdEvent

// there may be several watchers for a same watcher key, so add a watcherName to distinguish them
case class WatcherKey(watchKey: String, watchName: String)

class WatcherService(etcdClient: EtcdClient)(implicit logging: Logging, actorSystem: ActorSystem) extends Actor {

  implicit val ec = context.dispatcher

  private[service] val putWatchers = TrieMap[WatcherKey, ActorRef]()
  private[service] val deleteWatchers = TrieMap[WatcherKey, ActorRef]()
  private[service] val prefixPutWatchers = TrieMap[WatcherKey, ActorRef]()
  private[service] val prefixDeleteWatchers = TrieMap[WatcherKey, ActorRef]()

  private val watcher = etcdClient.watchAllKeys { res: WatchUpdate =>
    res.getEvents.asScala.foreach { event =>
      event.getType match {
        case EventType.DELETE =>
          val key = ByteStringToString(event.getPrevKv.getKey)
          val value = ByteStringToString(event.getPrevKv.getValue)
          val watchEvent = WatchEndpointRemoved(key, key, value, false)
          deleteWatchers
            .foreach { watcher =>
              if (watcher._1.watchKey == key) {
                watcher._2 ! watchEvent
              }
            }
          prefixDeleteWatchers
            .foreach { watcher =>
              if (key.startsWith(watcher._1.watchKey)) {
                watcher._2 ! WatchEndpointRemoved(watcher._1.watchKey, key, value, true)
              }
            }
        case EventType.PUT =>
          val key = ByteStringToString(event.getKv.getKey)
          val value = ByteStringToString(event.getKv.getValue)
          val watchEvent = WatchEndpointInserted(key, key, value, false)
          putWatchers
            .foreach { watcher =>
              if (watcher._1.watchKey == key) {
                watcher._2 ! watchEvent
              }
            }
          prefixPutWatchers
            .foreach { watcher =>
              if (key.startsWith(watcher._1.watchKey)) {
                watcher._2 ! WatchEndpointInserted(watcher._1.watchKey, key, value, true)
              }
            }
        case msg =>
          logging.debug(this, s"watch event received: $msg.")
      }
    }

  }

  override def receive: Receive = {
    case request: WatchEndpoint =>
      logging.info(this, s"watch endpoint: $request")
      val watcherKey = WatcherKey(request.key, request.name)
      if (request.listenEvents.contains(PutEvent))
        if (request.isPrefix)
          prefixPutWatchers.update(watcherKey, sender())
        else
          putWatchers.update(watcherKey, sender())

      if (request.listenEvents.contains(DeleteEvent))
        if (request.isPrefix)
          prefixDeleteWatchers.update(watcherKey, sender())
        else
          deleteWatchers.update(watcherKey, sender())

    case request: UnwatchEndpoint =>
      val watcherKey = WatcherKey(request.watchKey, request.watchName)
      if (request.isPrefix) {
        prefixPutWatchers.remove(watcherKey)
        prefixDeleteWatchers.remove(watcherKey)
      } else {
        putWatchers.remove(watcherKey)
        deleteWatchers.remove(watcherKey)
      }

      // always send WatcherClosed back to sender if it need a feedback
      if (request.needFeedback)
        sender ! WatcherClosed(request.watchKey, request.isPrefix)
  }
}

object WatcherService {
  def props(etcdClient: EtcdClient)(implicit logging: Logging, actorSystem: ActorSystem): Props = {
    Props(new WatcherService(etcdClient))
  }
}
