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
package whisk.core.database.test

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.core.database.CouchDbRestClient

/**
 * Implementation of additional endpoints that should only be used in testing.
 */
class ExtendedCouchDbRestClient(protocol: String, host: String, port: Int, username: String, password: String, db: String)(implicit system: ActorSystem, logging: Logging)
    extends CouchDbRestClient(protocol, host, port, username, password, db) {

    // http://docs.couchdb.org/en/1.6.1/api/server/common.html#get--
    def instanceInfo(): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.GET, Uri./))

    // http://docs.couchdb.org/en/1.6.1/api/server/common.html#all-dbs
    def dbs(): Future[Either[StatusCode, List[String]]] = {
        implicit val ec = system.dispatcher
        requestJson[JsArray](mkRequest(HttpMethods.GET, uri("_all_dbs"))).map { either =>
            either.right.map(_.convertTo[List[String]])
        }
    }

    // http://docs.couchdb.org/en/1.6.1/api/database/common.html#put--db
    def createDb(): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.PUT, uri(db)))

    // http://docs.couchdb.org/en/1.6.1/api/database/common.html#delete--db
    def deleteDb(): Future[Either[StatusCode, JsObject]] =
        requestJson[JsObject](mkRequest(HttpMethods.DELETE, uri(db)))

    // http://docs.couchdb.org/en/1.6.1/api/database/bulk-api.html#get--db-_all_docs
    def getAllDocs(skip: Option[Int] = None, limit: Option[Int] = None, includeDocs: Option[Boolean] = None): Future[Either[StatusCode, JsObject]] = {
        val args = Seq[(String, Option[String])](
            "skip" -> skip.filter(_ > 0).map(_.toString),
            "limit" -> limit.filter(_ > 0).map(_.toString),
            "include_docs" -> includeDocs.map(_.toString))

        // Throw out all undefined arguments.
        val argMap: Map[String, String] = args.collect({
            case (l, Some(r)) => (l, r)
        }).toMap

        val url = uri(db, "_all_docs").withQuery(Uri.Query(argMap))
        requestJson[JsObject](mkRequest(HttpMethods.GET, url))
    }
}
