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

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.ibm.etcd.api._
import com.ibm.etcd.client.kv.KvClient.Watch
import com.ibm.etcd.client.kv.{KvClient, WatchUpdate}
import com.ibm.etcd.client.{EtcdClient => Client}
import io.grpc.stub.StreamObserver
import java.util.concurrent.Executors

import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.etcd.EtcdType._
import org.apache.openwhisk.core.service.Lease
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol

import scala.language.implicitConversions
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

object RichListenableFuture {
  implicit def convertToFuture[T](lf: ListenableFuture[T])(implicit ece: ExecutionContextExecutor): Future[T] = {
    val p = Promise[T]()
    Futures.addCallback(lf, new FutureCallback[T] {
      def onFailure(t: Throwable): Unit = p failure t
      def onSuccess(result: T): Unit = p success result
    }, ece)
    p.future
  }
}

object EtcdClient {
  // hostAndPorts format: {HOST}:{PORT}[,{HOST}:{PORT},{HOST}:{PORT}, ...]
  def apply(hostAndPorts: String)(implicit ece: ExecutionContextExecutor): EtcdClient = {
    require(hostAndPorts != null)
    val client: Client = Client.forEndpoints(hostAndPorts).withPlainText().build()
    new EtcdClient(client)(ece)
  }

  def apply(client: Client)(implicit ece: ExecutionContextExecutor): EtcdClient = {
    new EtcdClient(client)(ece)
  }
}

class EtcdClient(val client: Client)(override implicit val ece: ExecutionContextExecutor)
    extends EtcdKeyValueApi
    with EtcdLeaseApi
    with EtcdWatchApi
    with EtcdLeadershipApi {

  def close() = {
    client.close()
  }
}

trait EtcdKeyValueApi extends KeyValueStore {
  import RichListenableFuture._
  protected[etcd] val client: Client

  override def get(key: String): Future[RangeResponse] =
    client.getKvClient.get(key).async()

  override def getPrefix(prefixKey: String): Future[RangeResponse] = {
    client.getKvClient.get(prefixKey).asPrefix().async()
  }

  override def getCount(prefixKey: String): Future[Long] = {
    client.getKvClient.get(prefixKey).asPrefix().countOnly().async().map(_.getCount)
  }

  override def put(key: String, value: String): Future[PutResponse] =
    client.getKvClient.put(key, value).async().recoverWith {
      case t =>
        Future.failed[PutResponse](getNestedException(t))
    }

  override def put(key: String, value: String, leaseId: Long): Future[PutResponse] =
    client.getKvClient
      .put(key, value, leaseId)
      .async()
      .recoverWith {
        case t =>
          Future.failed[PutResponse](getNestedException(t))
      }

  def put(key: String, value: Boolean): Future[PutResponse] = {
    put(key, value.toString)
  }

  def put(key: String, value: Boolean, leaseId: Long): Future[PutResponse] = {
    put(key, value.toString, leaseId)
  }

  override def del(key: String): Future[DeleteRangeResponse] =
    client.getKvClient.delete(key).async().recoverWith {
      case t =>
        Future.failed[DeleteRangeResponse](getNestedException(t))
    }

  override def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse] = {
    client.getKvClient
      .txnIf()
      .cmpEqual(key)
      .version(cmpVersion)
      .`then`()
      .put(client.getKvClient
        .put(key, value.toString, leaseId)
        .asRequest())
      .async()
      .recoverWith {
        case t =>
          Future.failed[TxnResponse](getNestedException(t))
      }
  }

  @tailrec
  private def getNestedException(t: Throwable): Throwable = {
    if (t.getCause == null) t
    else getNestedException(t.getCause)
  }
}

trait KeyValueStore {

  implicit val ece: ExecutionContextExecutor

  def get(key: String): Future[RangeResponse]

  def getPrefix(prefixKey: String): Future[RangeResponse]

  def getCount(prefixKey: String): Future[Long]

  def put(key: String, value: String): Future[PutResponse]

  def put(key: String, value: String, leaseId: Long): Future[PutResponse]

  def del(key: String): Future[DeleteRangeResponse]

  def putTxn[T](key: String, value: T, cmpVersion: Long, leaseId: Long): Future[TxnResponse]
}

trait EtcdLeaseApi {
  import RichListenableFuture._
  implicit val ece: ExecutionContextExecutor

  protected[etcd] val client: Client
  protected val DEFAULT_TTL = 2

  def grant(ttl: Long = DEFAULT_TTL): Future[LeaseGrantResponse] = {
    client.getLeaseClient.grant(ttl).async()
  }

  def revoke(leaseId: Long): Future[LeaseRevokeResponse] = {
    client.getLeaseClient.revoke(leaseId)
  }

  def keepAliveOnce(leaseId: Long): Future[LeaseKeepAliveResponse] = {
    client.getLeaseClient.keepAliveOnce(leaseId)
  }
}

trait EtcdWatchApi {
  val nThreads = loadConfigOrThrow[Int](ConfigKeys.etcdPoolThreads)
  val threadpool = Executors.newFixedThreadPool(nThreads);
  protected[etcd] val client: Client

  def watchAllKeys(next: WatchUpdate => Unit = (_: WatchUpdate) => {},
                   error: Throwable => Unit = (_: Throwable) => {},
                   completed: () => Unit = () => {}): Watch = {
    client.getKvClient
      .watch(KvClient.ALL_KEYS)
      .prevKv()
      .executor(threadpool)
      .start(new StreamObserver[WatchUpdate]() {
        override def onNext(value: WatchUpdate): Unit = {
          next(value)
        }

        override def onError(t: Throwable): Unit = {
          error(t)
        }

        override def onCompleted(): Unit = {
          completed()
        }
      })
  }

  def watch(key: String, isPrefix: Boolean = false)(next: WatchUpdate => Unit = (_: WatchUpdate) => {},
                                                    error: Throwable => Unit = (_: Throwable) => {},
                                                    completed: () => Unit = () => {}): Watch = {
    val watchRequest = if (isPrefix) {
      client.getKvClient.watch(key).asPrefix().prevKv()
    } else {
      client.getKvClient.watch(key).prevKv()
    }
    watchRequest
      .executor(threadpool)
      .start(new StreamObserver[WatchUpdate]() {
        override def onNext(value: WatchUpdate): Unit = {
          next(value)
        }

        override def onError(t: Throwable): Unit = {
          error(t)
        }

        override def onCompleted(): Unit = {
          completed()
        }
      })
  }

}

trait EtcdLeadershipApi extends EtcdKeyValueApi with EtcdLeaseApi with EtcdWatchApi {

  protected[etcd] val client: Client

  val initVersion = 0

  def electLeader(key: String, value: String, timeout: Long = 60): Future[Either[EtcdFollower, EtcdLeader]] =
    for {
      leaseResp <- grant(timeout)
      txnResp <- putTxn(key, value, initVersion, leaseResp.getID)
      result <- Future {
        if (txnResp.getSucceeded) {
          Right(EtcdLeader(key, value, leaseResp.getID))
        } else {
          Left(EtcdFollower(key, value))
        }
      }
    } yield result

  def electLeader(key: String, value: String, lease: Lease): Future[Either[EtcdFollower, EtcdLeader]] =
    for {
      txnResp <- putTxn(key, value, initVersion, lease.id)
      result <- Future {
        if (txnResp.getSucceeded) {
          Right(EtcdLeader(key, value, lease.id))
        } else {
          Left(EtcdFollower(key, value))
        }
      }
    } yield result

  def keepAliveLeader(leaseId: Long): Future[Long] =
    keepAliveOnce(leaseId).map(res => res.getID)

}
case class EtcdLeader(key: String, value: String, leaseId: Long)

object EtcdLeader extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat3(EtcdLeader.apply)
}

case class EtcdFollower(key: String, value: String)

object EtcdFollower extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(EtcdFollower.apply)
}
