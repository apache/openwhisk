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

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.{ Success, Failure }

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl._
import akka.util.ByteString
import spray.json._
import whisk.common.Logging

/**
 * This class only handles the basic communication to the proper endpoints
 *  ("JSON in, JSON out"). It is up to its clients to interpret the results.
 *  It is built on akka-http host-level connection pools; compared to single
 *  requests, it saves some time on each request because it doesn't need to look
 *  up the pool corresponding to the host. It is also easier to add an extra
 *  queueing mechanism.
 */
class CouchDbRestClient(protocol: String, host: String, port: Int, username: String, password: String, db: String)(implicit system: ActorSystem, logging: Logging) {
    require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

    private implicit val context = system.dispatcher
    private implicit val materializer = ActorMaterializer()

    // Creates or retrieves a connection pool for the host.
    private val pool = if (protocol == "http") {
        Http().cachedHostConnectionPool[Promise[HttpResponse]](host = host, port = port)
    } else {
        Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](host = host, port = port)
    }

    private val poolPromise = Promise[HostConnectionPool]

    // Additional queue in case all connections are busy. Should hardly ever be
    // filled in practice but can be useful, e.g., in tests starting many
    // asynchronous requests in a very short period of time.
    private val QUEUE_SIZE = 16 * 1024;
    private val requestQueue = Source.queue(QUEUE_SIZE, OverflowStrategy.dropNew)
        .via(pool.mapMaterializedValue { x => poolPromise.success(x); x })
        .toMat(Sink.foreach({
            case ((Success(response), p)) => p.success(response)
            case ((Failure(error), p))    => p.failure(error)
        }))(Keep.left)
        .run

    // Properly encodes the potential slashes in each segment.
    protected def uri(segments: Any*): Uri = {
        val encodedSegments = segments.map(s => URLEncoder.encode(s.toString, StandardCharsets.UTF_8.name))
        Uri(s"/${encodedSegments.mkString("/")}")
    }

    // Headers common to all requests.
    private val baseHeaders = List(
        Authorization(BasicHttpCredentials(username, password)),
        Accept(MediaTypes.`application/json`))

    // Prepares a request with the proper headers.
    private def mkRequest0(
        method: HttpMethod,
        uri: Uri,
        body: Future[MessageEntity],
        forRev: Option[String] = None): Future[HttpRequest] = {
        val revHeader = forRev.map(r => `If-Match`(EntityTagRange(EntityTag(r)))).toList
        val headers = revHeader ::: baseHeaders
        body.map { b => HttpRequest(method = method, uri = uri, headers = headers, entity = b) }
    }

    protected def mkRequest(method: HttpMethod, uri: Uri, forRev: Option[String] = None): Future[HttpRequest] = {
        mkRequest0(method, uri, Future.successful(HttpEntity.Empty), forRev = forRev)
    }

    protected def mkJsonRequest(method: HttpMethod, uri: Uri, body: JsValue, forRev: Option[String] = None): Future[HttpRequest] = {
        val b = Marshal(body).to[MessageEntity]
        mkRequest0(method, uri, b, forRev = forRev)
    }

    // Enqueue a request, and return a future capturing the corresponding response.
    // WARNING: make sure that if the future response is not failed, its entity
    // be drained entirely or the connection will be kept open until timeouts kick in.
    private def request0(futureRequest: Future[HttpRequest]): Future[HttpResponse] = {
        require(!materializer.isShutdown, "db client was shutdown and cannot be used again")
        futureRequest flatMap { request =>
            val promise = Promise[HttpResponse]

            // When the future completes, we know whether the request made it
            // through the queue.
            requestQueue.offer(request -> promise).flatMap { buffered =>
                buffered match {
                    case QueueOfferResult.Enqueued =>
                        promise.future

                    case QueueOfferResult.Dropped =>
                        Future.failed(new Exception("DB request queue is full."))

                    case QueueOfferResult.QueueClosed =>
                        Future.failed(new Exception("DB request queue was closed."))

                    case QueueOfferResult.Failure(f) =>
                        Future.failed(f)
                }
            }
        }
    }

    // Runs a request and returns either a JsObject, or a StatusCode if not 2xx.
    protected def requestJson[T: RootJsonReader](futureRequest: Future[HttpRequest]): Future[Either[StatusCode, T]] = {
        request0(futureRequest) flatMap { response =>
            if (response.status.isSuccess()) {
                Unmarshal(response.entity.withoutSizeLimit()).to[T].map { o => Right(o) }
            } else {
                // This is important, as it drains the entity stream.
                // Otherwise the connection stays open and the pool dries up.
                response.entity.withoutSizeLimit().dataBytes.runWith(Sink.ignore).map { _ => Left(response.status) }
            }
        }
    }

    import spray.json.DefaultJsonProtocol._

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, doc: JsObject): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkJsonRequest(HttpMethods.PUT, uri(db, id), doc))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, rev: String, doc: JsObject): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkJsonRequest(HttpMethods.PUT, uri(db, id), doc, forRev = Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.GET, uri(db, id)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String, rev: String): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.GET, uri(db, id), forRev = Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#delete--db-docid
    def deleteDoc(id: String, rev: String): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.DELETE, uri(db, id), forRev = Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/ddoc/views.html
    def executeView(designDoc: String, viewName: String)(
        startKey: List[Any] = Nil,
        endKey: List[Any] = Nil,
        skip: Option[Int] = None,
        limit: Option[Int] = None,
        stale: StaleParameter = StaleParameter.No,
        includeDocs: Boolean = false,
        descending: Boolean = false,
        reduce: Boolean = false,
        group: Boolean = false): Future[Either[StatusCode, JsObject]] = {

        require(reduce || !group, "Parameter 'group=true' cannot be used together with the parameter 'reduce=false'.")

        def any2json(any: Any): JsValue = any match {
            case b: Boolean => JsBoolean(b)
            case i: Int     => JsNumber(i)
            case l: Long    => JsNumber(l)
            case d: Double  => JsNumber(d)
            case f: Float   => JsNumber(f)
            case s: String  => JsString(s)
            case _ =>
                logging.warn(this, s"Serializing uncontrolled type '${any.getClass}' to string in JSON conversion ('${any.toString}').")
                JsString(any.toString)
        }

        def list2OptJson(lst: List[Any]): Option[JsValue] = {
            lst match {
                case Nil => None
                case _   => Some(JsArray(lst.map(any2json): _*))
            }
        }

        val args = Seq[(String, Option[String])](
            "startkey" -> list2OptJson(startKey).map(_.toString),
            "endkey" -> list2OptJson(endKey).map(_.toString),
            "skip" -> skip.filter(_ > 0).map(_.toString),
            "limit" -> limit.filter(_ > 0).map(_.toString),
            "stale" -> stale.value,
            "include_docs" -> Some(includeDocs).filter(identity).map(_.toString),
            "descending" -> Some(descending).filter(identity).map(_.toString),
            "reduce" -> Some(reduce).map(_.toString),
            "group" -> Some(group).filter(identity).map(_.toString))

        // Throw out all undefined arguments.
        val argMap: Map[String, String] = args.collect({
            case (l, Some(r)) => (l, r)
        }).toMap

        val viewUri = uri(db, "_design", designDoc, "_view", viewName).withQuery(Uri.Query(argMap))

        requestJson[JsObject](mkRequest(HttpMethods.GET, viewUri))
    }

    // Streams an attachment to the database
    // http://docs.couchdb.org/en/1.6.1/api/document/attachments.html#put--db-docid-attname
    def putAttachment(id: String, rev: String, attName: String, contentType: ContentType, source: Source[ByteString, _]): Future[Either[StatusCode, JsObject]] = {
        val entity = HttpEntity.Chunked(contentType, source.map(bs => HttpEntity.ChunkStreamPart(bs)))
        val request = mkRequest0(HttpMethods.PUT, uri(db, id, attName), Future.successful(entity), forRev = Some(rev))
        requestJson[JsObject](request)
    }

    // Retrieves and streams an attachment into a Sink, producing a result of type T.
    // http://docs.couchdb.org/en/1.6.1/api/document/attachments.html#get--db-docid-attname
    def getAttachment[T](id: String, rev: String, attName: String, sink: Sink[ByteString, Future[T]]): Future[Either[StatusCode, (ContentType, T)]] = {
        val request = mkRequest(HttpMethods.GET, uri(db, id, attName), forRev = Some(rev))

        request0(request) flatMap { response =>
            if (response.status.isSuccess()) {
                response.entity.withoutSizeLimit().dataBytes.runWith(sink).map(r => Right(response.entity.contentType, r))
            } else {
                response.entity.withoutSizeLimit().dataBytes.runWith(Sink.ignore).map(_ => Left(response.status))
            }
        }
    }

    def shutdown(): Future[Unit] = {
        materializer.shutdown()
        // The code below shuts down the pool, but is apparently not tolerant
        // to multiple clients shutting down the same pool (the second one just
        // hangs). Given that shutdown is only relevant for tests (unused pools
        // close themselves anyway after some time) and that they can call
        // Http().shutdownAllConnectionPools(), this is not a major issue.
        /* Reintroduce below if they ever make HostConnectionPool.shutdown()
         * safe to call >1x.
         * val poolOpt = poolPromise.future.value.map(_.toOption).flatten
         * poolOpt.map(_.shutdown().map(_ => ())).getOrElse(Future.successful(()))
         */
        Future.successful(())
    }
}
