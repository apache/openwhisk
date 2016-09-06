/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

import scala.util.{ Success, Failure }
import scala.concurrent.Future
import scala.concurrent.Promise

import spray.json._

import whisk.common.Logging

import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl._

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._

/** This class only handles the basic communication to the proper endpoints
 *  ("JSON in, JSON out"). It is up to its clients to interpret the results.
 *  It is built on akka-http host-level connection pools; compared to single
 *  requests, it saves some time on each request because it doesn't need to look
 *  up the pool corresponding to the host. It is also easier to add an extra
 *  queueing mechanism.
 */
class CouchDbRestClient protected(system: ActorSystem, protocol: String, host: String, port: Int, username: String, password: String, db: String) extends Logging {
    private implicit val actorSystem  = system
    private implicit val context      = system.dispatcher
    private implicit val materializer = ActorMaterializer()

    // Creates or retrieves a connection pool for the host.
    private val pool = if(protocol == "http") {
        Http().cachedHostConnectionPool[Promise[HttpResponse]](host=host, port=port)
    } else {
        Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](host=host, port=port)
    }

    private val poolPromise = Promise[HostConnectionPool]

    // Additional queue in case all connections are busy. Should hardly ever be
    // filled in practice but can be useful, e.g., in tests starting many
    // asynchronous requests in a very short period of time.
    private val requestQueue = Source.queue(128, OverflowStrategy.dropNew)
        .via(pool.mapMaterializedValue { x => poolPromise.success(x); x })
        .toMat(Sink.foreach({
            case ((Success(response), p)) => p.success(response)
            case ((Failure(error),    p)) => p.failure(error)
        }))(Keep.left)
        .run

    // Properly encodes the potential slashes in each segment.
    protected def uri(segments: Any*) : Uri = {
        val encodedSegments = segments.map(s => URLEncoder.encode(s.toString, StandardCharsets.UTF_8.name))
        Uri(s"/${encodedSegments.mkString("/")}")
    }

    // Headers common to all requests.
    private val baseHeaders = List(
        Authorization(BasicHttpCredentials(username, password)),
        Accept(MediaTypes.`application/json`))

    // Prepares a request with the proper headers and body.
    protected def mkRequest(method: HttpMethod, uri: Uri, body: Option[JsValue]=None, forRev: Option[String]=None) : Future[HttpRequest] = {
        val headers = forRev.map { r =>
            `If-Match`(EntityTagRange(EntityTag(r))) :: baseHeaders
        } getOrElse {
            baseHeaders
        }

        val futureBody = body.map { jsValue =>
            Marshal(jsValue).to[MessageEntity]
        } getOrElse {
            Future.successful(HttpEntity.Empty)
        }

        futureBody.map { body =>
            HttpRequest(method=method, uri=uri, headers=headers, entity=body)
        }
    }

    // Runs a request and returns either a JsObject, or a StatusCode if not 2xx.
    protected def request(futureRequest: Future[HttpRequest]) : Future[Either[StatusCode,JsObject]] = {
        futureRequest flatMap { request =>
            val promise = Promise[HttpResponse]

            // When the future completes, we know whether the request made it
            // through the queue.
            val futureResponse: Future[HttpResponse] = requestQueue.offer(request -> promise).flatMap { buffered =>
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

            futureResponse.flatMap { response =>
                if(response.status.isSuccess()) {
                    // Importing this in an outer scope seems to make it eager
                    // to always unmarshal.
                    import spray.json.DefaultJsonProtocol._
                    Unmarshal(response.entity.withoutSizeLimit()).to[JsObject].map { o => Right(o) }
                } else {
                    // This is important, even though the response is ignored:
                    // Otherwise the connection stays open and the pool dries up.
                    response.entity.dataBytes.runWith(Sink.ignore).map { _ => Left(response.status) }
                }
            }
        }
    }

    // http://docs.couchdb.org/en/1.6.1/api/server/common.html#get--
    def instanceInfo() : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.GET, Uri./))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, doc: JsObject) : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.PUT, uri(db, id), body=Some(doc)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, rev: String, doc: JsObject) : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.PUT, uri(db, id), body=Some(doc), forRev=Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String) : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.GET, uri(db, id)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String, rev: String) : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.GET, uri(db, id), forRev=Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#delete--db-docid
    def deleteDoc(id: String, rev: String) : Future[Either[StatusCode,JsObject]] =
        request(mkRequest(HttpMethods.DELETE, uri(db, id), forRev=Some(rev)))

    // http://docs.couchdb.org/en/1.6.1/api/ddoc/views.html
    def executeView(designDoc: String, viewName: String)(
        startKey: List[Any] = Nil,
        endKey: List[Any] = Nil,
        skip: Option[Int] = None,
        limit: Option[Int] = None,
        includeDocs: Boolean = false,
        descending: Boolean = false,
        reduce: Boolean = false
    ) : Future[Either[StatusCode,JsObject]] = {
        def any2json(any: Any) : JsValue = any match {
            case b: Boolean => JsBoolean(b)
            case i: Int     => JsNumber(i)
            case l: Long    => JsNumber(l)
            case d: Double  => JsNumber(d)
            case f: Float   => JsNumber(f)
            case s: String  => JsString(s)
            case _          =>
                warn(this, s"Serializing uncontrolled type '${any.getClass}' to string in JSON conversion ('${any.toString}').")
                JsString(any.toString)
        }

        def list2OptJson(lst: List[Any]) : Option[JsValue] = {
            lst match {
                case Nil => None
                case _ => Some(JsArray(lst.map(any2json) : _*))
            }
        }

        val args = Seq[(String,Option[String])](
            "startkey"     -> list2OptJson(startKey).map(_.toString),
            "endkey"       -> list2OptJson(endKey).map(_.toString),
            "skip"         -> skip.filter(_ > 0 ).map(_.toString),
            "limit"        -> limit.filter(_ > 0).map(_.toString),
            "include_docs" -> Some(includeDocs).filter(identity).map(_.toString),
            "descending"   -> Some(descending).filter(identity).map(_.toString),
            "reduce"       -> Some(reduce).map(_.toString)
        )

        // Throw out all undefined arguments.
        val argMap: Map[String,String] = args.collect({
            case (l, Some(r)) => (l, r)
        }).toMap

        val viewUri = uri(db, "_design", designDoc, "_view", viewName).withQuery(Uri.Query(argMap))

        request(mkRequest(HttpMethods.GET, viewUri))
    }

    def shutdown() : Future[Unit] = {
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

object CouchDbRestClient {
    def make(protocol: String, host: String, port: Int, username: String, password: String, db: String)(
        implicit system: ActorSystem) : CouchDbRestClient = {

        require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

        new CouchDbRestClient(system, protocol, host, port, username, password, db)
    }
}
