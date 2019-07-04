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

class EtcdClient(val client: Client)(implicit val ec: ExecutionContext) extends EtcdLeadershipApi {

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
      Watch.listener((res: WatchResponse) => next(res), (t: Throwable) => error(t), new Runnable {
        override def run(): Unit = completed()
      }))
  }
}

trait EtcdLeadershipApi extends EtcdKeyValueApi with EtcdLeaseApi with EtcdWatchApi {
  protected[etcd] val client: Client
  val initVersion = 0

  def electLeader(key: String, value: String, timeout: Long = 60)(
    implicit ec: ExecutionContext): Future[Either[EtcdFollower, EtcdLeader]] =
    for {
      lease <- grant(timeout).map(res => Lease(res.getID, res.getTTL))
      txnResp <- putTxn(key, value, initVersion, lease)
      result <- Future {
        if (txnResp.isSucceeded) {
          Right(EtcdLeader(key, value, lease))
        } else {
          Left(EtcdFollower(key, value))
        }
      }
    } yield result

  def electLeader(key: String, value: String, lease: Lease)(
    implicit ec: ExecutionContext): Future[Either[EtcdFollower, EtcdLeader]] =
    for {
      txnResp <- putTxn(key, value, initVersion, lease)
      result <- Future {
        if (txnResp.isSucceeded) {
          Right(EtcdLeader(key, value, lease))
        } else {
          Left(EtcdFollower(key, value))
        }
      }
    } yield result

  def keepAliveLeader(lease: Lease)(implicit ec: ExecutionContext): Future[Long] =
    keepAliveOnce(lease).map(res => res.getID)

  def resignLeader(lease: Lease)(implicit ec: ExecutionContext): Future[Unit] = {
    revoke(lease).map(_ => Future.successful({}))
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
