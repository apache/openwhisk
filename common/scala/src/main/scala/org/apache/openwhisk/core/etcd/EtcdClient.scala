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

package org.apache.openwhisk.core.etcd

import java.net.URI

import io.etcd.jetcd.Watch.Watcher
import io.etcd.jetcd._
import io.etcd.jetcd.kv.{DeleteResponse, GetResponse, PutResponse, TxnResponse}
import io.etcd.jetcd.lease.{LeaseGrantResponse, LeaseKeepAliveResponse, LeaseRevokeResponse}
import io.etcd.jetcd.op.Cmp.Op.EQUAL
import io.etcd.jetcd.op.CmpTarget.version
import io.etcd.jetcd.op.{Cmp, Op}
import io.etcd.jetcd.options.{DeleteOption, GetOption, PutOption, WatchOption}
import io.etcd.jetcd.watch.{WatchEvent, WatchResponse}
import org.apache.openwhisk.core.etcd.EtcdType._

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionContext, Future}

object EtcdClient {
  // hostAndPorts format: {HOST}:{PORT}[,{HOST}:{PORT},{HOST}:{PORT}, ...]
  def apply(hostAndPorts: String)(implicit ec: ExecutionContext): EtcdClient = {
    require(hostAndPorts != null)
    // A consequence of hostAndPort being merged in config.
    val addresses: List[URI] = hostAndPorts
      .split(",")
      .toList
      .map(hp => {
        val host :: port :: Nil = hp.split(":").toList
        URI.create(s"http://$host:$port")
      })

    val client: Client = Client.builder().endpoints(addresses.asJava).build()
    new EtcdClient(client)
  }

  def apply(client: Client)(implicit ec: ExecutionContext): EtcdClient = {
    new EtcdClient(client)
  }
}

class EtcdClient(val client: Client)(implicit val ec: ExecutionContext)
    extends EtcdLeadershipApi
    with EtcdKeyValueApi
    with EtcdLeaseApi
    with EtcdWatchApi {

  def close() = {
    client.close()
  }
}

trait EtcdKeyValueApi {
  protected[etcd] val client: Client

  implicit protected[etcd] val ec: ExecutionContext

  def get(key: String, option: GetOption = GetOption.DEFAULT): Future[GetResponse] =
    FutureConverters.toScala(client.getKVClient.get(key, option))

  def getRange(startKey: String, endKey: String): Future[GetResponse] = {
    val option = GetOption
      .newBuilder()
      .withRange(endKey)
      .build()

    get(startKey, option)
  }

  def getPrefix(prefixKey: String): Future[GetResponse] = {
    val option = GetOption
      .newBuilder()
      .withPrefix(prefixKey)
      .build()

    get(prefixKey, option)
  }

  def getCount(prefixKey: String): Future[Long] = {
    val option = GetOption
      .newBuilder()
      .withPrefix(prefixKey)
      .withCountOnly(true)
      .build()

    get(prefixKey, option).map(_.getCount)
  }

  def put(key: String, value: String, option: PutOption = PutOption.DEFAULT): Future[PutResponse] =
    FutureConverters.toScala(client.getKVClient.put(key, value, option)).recoverWith {
      case t =>
        Future.failed[PutResponse](if (t.getCause == null) t else t.getCause)
    }

  def put(key: String, value: String, lease: Lease): Future[PutResponse] =
    put(key, value, PutOption.newBuilder().withLeaseId(lease.id).build())

  def put(key: String, value: Boolean): Future[PutResponse] = put(key, value.toString)

  def put(key: String, value: Boolean, lease: Lease): Future[PutResponse] =
    put(key, value.toString, lease)

  def del(key: String, option: DeleteOption = DeleteOption.DEFAULT): Future[DeleteResponse] =
    FutureConverters.toScala(client.getKVClient.delete(key, option))

  def txn(): Txn = client.getKVClient.txn()

  def putTxn(key: String, value: String, cmpVersion: Int, lease: Lease): Future[TxnResponse] = {
    FutureConverters
      .toScala(
        txn()
          .If(new Cmp(key, EQUAL, version(cmpVersion)))
          .Then(Op.put(key, value, PutOption.newBuilder().withLeaseId(lease.id).build()))
          .commit())
      .recoverWith {
        case t =>
          Future.failed[TxnResponse](if (t.getCause == null) t else t.getCause)
      }
  }

}

trait EtcdLeaseApi {
  protected[etcd] val client: Client
  protected val DEFAULT_TTL = 2

  def grant(ttl: Long = DEFAULT_TTL): Future[LeaseGrantResponse] = {
    FutureConverters.toScala(client.getLeaseClient.grant(ttl))
  }

  def revoke(lease: Lease): Future[LeaseRevokeResponse] = {
    FutureConverters.toScala(client.getLeaseClient.revoke(lease.id))
  }

  def keepAliveOnce(lease: Lease): Future[LeaseKeepAliveResponse] = {
    FutureConverters.toScala(client.getLeaseClient.keepAliveOnce(lease.id))
  }
}

trait EtcdWatchApi {
  protected[etcd] val client: Client

  /**
   *
   * Subscribes to changes in a key.
   *
   * @param key key to be subscribed
   * @param isPrefix toggle for prefix matched of the key
   * @param next callback method that executes when an key is changed
   * @param error callback method that executes when an error occurs
   * @param completed callback method that executes when a watcher is closed
   *
   */
  def watch(key: String, isPrefix: Boolean = false)(next: WatchResponse => Unit = (_: WatchResponse) => {},
                                                    error: Throwable => Unit = (_: Throwable) => {},
                                                    completed: () => Unit = () => {}): Watcher = {
    val option = if (isPrefix) {
      WatchOption
        .newBuilder()
        .withPrefix(key)
        .build()
    } else {
      WatchOption.DEFAULT
    }

    client.getWatchClient.watch(
      key,
      option,
      Watch.listener((res: WatchResponse) => next(res), (t: Throwable) => error(t), () => completed()))
  }
}

trait EtcdLeadershipApi {
  this: EtcdKeyValueApi with EtcdLeaseApi with EtcdWatchApi =>
  protected[etcd] val client: Client
  val initVersion = 0

  def electLeader(key: String, value: String, timeout: Long = 60): Future[Either[EtcdFollower, EtcdLeader]] =
    for {
      lease <- grant(timeout).map(res => Lease(res.getID, res.getTTL))
      txnResp <- putTxn(key, value, initVersion, lease)
    } yield {
      if (txnResp.isSucceeded) {
        Right(EtcdLeader(key, value, lease))
      } else {
        Left(EtcdFollower(key, value))
      }
    }

  def electLeader(key: String, value: String, lease: Lease): Future[Either[EtcdFollower, EtcdLeader]] =
    putTxn(key, value, initVersion, lease)
      .map { res =>
        if (res.isSucceeded) {
          Right(EtcdLeader(key, value, lease))
        } else {
          Left(EtcdFollower(key, value))
        }
      }

  def keepAliveLeader(lease: Lease): Future[Lease] =
    keepAliveOnce(lease).map(res => Lease(res.getID, res.getTTL))

  def resignLeader(lease: Lease): Future[Unit] = {
    revoke(lease).map(_ => {})
  }

  def watchLeader(leaderKey: String)(leaderResigned: (String, String) => Unit,
                                     leaderChanged: (String, String) => Unit) = {
    def onChanged(res: WatchResponse): Unit = {
      val e = res.getEvents.get(0)
      e.getEventType match {
        case WatchEvent.EventType.DELETE =>
          leaderResigned(e.getKeyValue.getKey, e.getKeyValue.getValue)
        case WatchEvent.EventType.PUT =>
          leaderChanged(e.getKeyValue.getKey, e.getKeyValue.getValue)
        case _ =>
      }
    }

    watch(leaderKey)(onChanged)
  }
}
