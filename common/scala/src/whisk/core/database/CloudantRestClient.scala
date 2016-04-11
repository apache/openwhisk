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

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout

import spray.can.Http
import spray.client.pipelining._
import spray.io.ClientSSLEngineProvider
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.http._
import spray.httpx.RequestBuilding.addCredentials
import spray.httpx.UnsuccessfulResponseException
import spray.httpx.SprayJsonSupport
import spray.util._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import whisk.common.Logging
import whisk.core.entity.DocInfo

/** This class only handles the basic communication to the proper endpoints
 *  ("JSON in, JSON out"). It is up to its clients to interpret the results.
 */
class CloudantRestClient (system: ActorSystem, urlBase: String, username: String, password: String, db: String)
    extends CouchDbRestClient(system,urlBase,username,password,db) {

    // https://cloudant.com/blog/cloudant-query-grows-up-to-handle-ad-hoc-queries/#.VvllCD-0z2C
    def simpleQuery(doc: String) : Future[Either[StatusCode,JsObject]] = {
        safeRequest(simplePipeline(Post(uri(db, "_find"), doc)))
    }
}

object CloudantRestClient extends SprayJsonSupport {
    def make(protocol: String, host: String, port: Int, username: String, password: String, db: String)(
        implicit system: ActorSystem) : CloudantRestClient = {

        require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

        val prefix = s"$protocol://$host:$port"

        new CloudantRestClient(system, prefix, username, password, db)
    }
}
