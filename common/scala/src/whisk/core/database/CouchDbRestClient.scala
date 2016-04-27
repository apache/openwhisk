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

import scala.Left
import scala.Right
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import spray.can.Http
import spray.can.Http
import spray.client.pipelining._
import spray.io.ClientSSLEngineProvider
import spray.json.DefaultJsonProtocol._
import spray.http._
import spray.httpx.RequestBuilding.addCredentials
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.SprayJsonSupport
import spray.util._
import spray.json.JsArray
import spray.json.JsBoolean
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import whisk.common.Logging

/** This class only handles the basic communication to the proper endpoints
 *  ("JSON in, JSON out"). It is up to its clients to interpret the results.
 */
class CouchDbRestClient protected(system: ActorSystem, urlBase: String, username: String, password: String, db: String) extends Logging {
    import system.dispatcher
    import SprayJsonSupport._

    private implicit val actorSystem = system
    private val creds = BasicHttpCredentials(username, password)

    // CouchDB is peculiar in that it sees '/' and '%2F' as semantically
    // distinct. This function creates URI paths from "segments"; within a
    // segment, slashes are encoded, and segments are separated with unencoded
    // slashes.
    protected def uri(segments: Any*) : Uri = {
        val encodedSegments = segments.map(s => URLEncoder.encode(s.toString, StandardCharsets.UTF_8.name))
        Uri(urlBase + "/" + encodedSegments.mkString("/"))
    }

    // For JSON-producing requests that do not expect a specific revision
    protected val simplePipeline = (
        addCredentials(creds)
        ~> addHeader("Accept", "application/json")
        ~> sendReceive
        ~> unmarshal[JsObject]
    )

    // For JSON-producing requests that apply to a specific document revision
    protected def revdPipeline(rev: String) = (
        addCredentials(creds)
        ~> addHeader("Accept", "application/json")
        ~> addHeader("If-Match", rev)
        ~> sendReceive
        ~> unmarshal[JsObject]
    )

    // Catches the exceptions spray-client throws when the response is not 2xx.
    protected def safeRequest[T](f: Future[T]) : Future[Either[StatusCode,T]] = f map { v =>
        Right(v)
    } recover {
        case e: UnsuccessfulResponseException => Left(e.response.status)
    }

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, doc: JsObject) : Future[Either[StatusCode,JsObject]] =
        safeRequest(simplePipeline(Put(uri(db, id), doc)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#put--db-docid
    def putDoc(id: String, rev: String, doc: JsObject) : Future[Either[StatusCode,JsObject]] =
        safeRequest(revdPipeline(rev)(Put(uri(db, id), doc)))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String) : Future[Either[StatusCode,JsObject]] =
        safeRequest(simplePipeline(Get(uri(db, id))))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#get--db-docid
    def getDoc(id: String, rev: String) : Future[Either[StatusCode,JsObject]] =
        safeRequest(revdPipeline(rev)(Get(uri(db, id))))

    // http://docs.couchdb.org/en/1.6.1/api/document/common.html#delete--db-docid
    def deleteDoc(id: String, rev: String) : Future[Either[StatusCode,JsObject]] =
        safeRequest(revdPipeline(rev)(Delete(uri(db, id))))


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

        val optArgMap = Map[String,Option[String]](
            "startkey"     -> list2OptJson(startKey).map(_.toString),
            "endkey"       -> list2OptJson(endKey).map(_.toString),
            "skip"         -> skip.filter(_ > 0 ).map(_.toString),
            "limit"        -> limit.filter(_ > 0).map(_.toString),
            "include_docs" -> Some(includeDocs).filter(identity).map(_.toString),
            "descending"   -> Some(descending).filter(identity).map(_.toString),
            "reduce"       -> Some(reduce).map(_.toString)
        )
        // Throw out all undefined arguments.
        val argMap: Map[String,String] = optArgMap.toSeq.filter(_._2.isDefined).map(p => (p._1, p._2.get)).toMap
        val viewUri = uri(db, "_design", designDoc, "_view", viewName).withQuery(argMap)
        safeRequest(simplePipeline(Get(viewUri)))
    }

    def shutdown() : Future[Unit] = {
        implicit val actorSystem = system
        implicit val timeout = Timeout(45 seconds)
        IO(Http).ask(Http.CloseAll).map(_ => ())
    }
}

object CouchDbRestClient extends SprayJsonSupport {
    def make(protocol: String, host: String, port: Int, username: String, password: String, db: String)(
        implicit system: ActorSystem) : CouchDbRestClient = {

        require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

        val prefix = s"$protocol://$host:$port"

        new CouchDbRestClient(system, prefix, username, password, db)
    }
}
