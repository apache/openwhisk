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

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.StatusCode
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging

/**
 * This class only handles the basic communication to the proper endpoints
 *  ("JSON in, JSON out"). It is up to its clients to interpret the results.
 */
class CloudantRestClient(host: String, port: Int, username: String, password: String, db: String)(
  implicit system: ActorSystem,
  logging: Logging)
    extends CouchDbRestClient("https", host, port, username, password, db) {

  // https://cloudant.com/blog/cloudant-query-grows-up-to-handle-ad-hoc-queries/#.VvllCD-0z2C
  def simpleQuery(doc: JsObject): Future[Either[StatusCode, JsObject]] = {
    requestJson[JsObject](mkJsonRequest(HttpMethods.POST, uri(db, "_find"), doc))
  }
}
