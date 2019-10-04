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

package org.apache.openwhisk.standalone

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Accept, Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, MediaTypes, Uri}
import org.apache.openwhisk.core.database.PutException
import org.apache.openwhisk.http.PoolingRestClient
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

class Wsk(host: String, port: Int, authKey: String)(implicit system: ActorSystem) extends DefaultJsonProtocol {
  import PoolingRestClient._
  private implicit val ec: ExecutionContext = system.dispatcher
  private val client = new PoolingRestClient("http", host, port, 10)
  private val baseHeaders: List[HttpHeader] = {
    val Array(username, password) = authKey.split(':')
    List(Authorization(BasicHttpCredentials(username, password)), Accept(MediaTypes.`application/json`))
  }

  def updatePgAction(name: String, content: String): Future[Done] = {
    val js = actionJson(name, content)
    val params = Map("overwrite" -> "true")
    val uri = Uri(s"/api/v1/namespaces/_/actions/$name").withQuery(Query(params))
    client.requestJson[JsObject](mkJsonRequest(HttpMethods.PUT, uri, js, baseHeaders)).map {
      case Right(_)     => Done
      case Left(status) => throw PutException(s"Error creating action $name " + status)
    }
  }

  private def actionJson(name: String, code: String) = {
    s"""{
      |    "namespace": "_",
      |    "name": "$name",
      |    "exec": {
      |        "kind": "nodejs:default",
      |        "code": ${quote(code)}
      |    },
      |    "annotations": [{
      |        "key": "provide-api-key",
      |        "value": true
      |    }, {
      |        "key": "web-export",
      |        "value": true
      |    }, {
      |        "key": "raw-http",
      |        "value": false
      |    }, {
      |        "key": "final",
      |        "value": true
      |    }],
      |    "parameters": [{
      |        "key": "__ignore_certs",
      |        "value": true
      |    }]
      |}""".stripMargin.parseJson.asJsObject
  }

  private def quote(code: String) = {
    JsString(code).compactPrint
  }
}
