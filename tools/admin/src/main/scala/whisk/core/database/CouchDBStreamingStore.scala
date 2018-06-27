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

package whisk.core.database
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, StatusCode, Uri}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{Keep, Sink}
import pureconfig.loadConfigOrThrow
import spray.json.{JsObject, _}
import whisk.common.{Logging, LoggingMarkers, TransactionId}
import whisk.core.ConfigKeys
import whisk.core.database.StoreUtils._
import whisk.http.PoolingRestClient

import scala.concurrent.Future
import scala.reflect.ClassTag

object CouchDBStreamingStoreProvider extends StreamingArtifactStoreProvider {
  def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                     logging: Logging,
                                                     materializer: ActorMaterializer): StreamingArtifactStore = {
    val dbConfig = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
    new CouchDBStreamingStore(
      dbConfig.protocol,
      dbConfig.host,
      dbConfig.port,
      dbConfig.username,
      dbConfig.password,
      dbConfig.databaseFor[D])
  }
}

class CouchDBStreamingStore(
  protocol: String,
  host: String,
  port: Int,
  username: String,
  password: String,
  dbName: String)(implicit system: ActorSystem, val logging: Logging, val materializer: ActorMaterializer)
    extends StreamingArtifactStore {

  private implicit val executionContext = system.dispatcher

  private val client = new StreamingCouchDbRestClient(protocol, host, port, username, password, dbName)

  override protected[database] def getAll[T](sink: Sink[JsObject, Future[T]])(
    implicit transid: TransactionId): Future[(Long, T)] = {

    val start = transid.started(this, LoggingMarkers.DATABASE_GET, s"[ATT_GET] '$dbName' reading all documents")

    val f = client.getAllDocs(sink) map {
      case Right((count, result)) =>
        transid.finished(this, start, s"[GET_ALL] '$dbName' completed: read all $count docs")
        (count, result)
      case Left(code) =>
        transid.failed(this, start, s"[GET_ALL] '$dbName' failed to get all documents; http status: '$code'")
        throw new Exception("Unexpected http response code: " + code)
    }

    reportFailure(f, start, failure => s"[GET_ALL] '$dbName' internal error, failure: '${failure.getMessage}'")
  }

  override protected[database] def getCount()(implicit transid: TransactionId): Future[Option[Long]] =
    Future.successful(None)

}

class StreamingCouchDbRestClient(protocol: String,
                                 host: String,
                                 port: Int,
                                 username: String,
                                 password: String,
                                 db: String)(implicit system: ActorSystem, logging: Logging)
    extends CouchDbRestClient(protocol, host, port, username, password, db) {

  def getAllDocs[T](sink: Sink[JsObject, Future[T]]): Future[Either[StatusCode, (Long, T)]] = {
    val url = uri(db, "_all_docs").withQuery(Uri.Query(Map("include_docs" -> "true")))
    val request = PoolingRestClient.mkRequest(HttpMethods.GET, url, headers = baseHeaders)
    requestStream[T](request, sink)
  }

  def requestStream[T](futureRequest: Future[HttpRequest],
                       sink: Sink[JsObject, Future[T]]): Future[Either[StatusCode, (Long, T)]] = {
    request(futureRequest) flatMap { response =>
      if (response.status.isSuccess()) {
        val counter = Sink.fold[Long, JsValue](0)((acc, _) => acc + 1)
        val f = response.entity
          .withoutSizeLimit()
          .dataBytes
          .via(JsonReader.select("$.rows[*].doc"))
          .map(bs => JsonParser(ParserInput.apply(bs.toArray)).asJsObject) //Better to use SprayJsonByteStringParserInput ... its private :(
          .alsoToMat(counter)(Keep.right)
          .toMat(sink)(Keep.both)
          .run()
        for (count <- f._1; t <- f._2) yield Right((count, t))
      } else {
        // This is important, as it drains the entity stream.
        // Otherwise the connection stays open and the pool dries up.
        response.entity.withoutSizeLimit().dataBytes.runWith(Sink.ignore).map { _ =>
          Left(response.status)
        }
      }
    }
  }
}
