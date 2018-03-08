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

package whisk.core.containerpool.logging

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.scaladsl.Flow

import scala.concurrent.Promise
import scala.util.Try

import spray.json._
import spray.json.DefaultJsonProtocol._

import whisk.http.PoolingRestClient

trait EsQueryMethod
trait EsOrder

case object EsOrderAsc extends EsOrder { override def toString = "asc" }
case object EsOrderDesc extends EsOrder { override def toString = "desc" }

// Schema of ES queries
case class EsQueryTerm(key: String, value: String) extends EsQueryMethod
case class EsQueryString(queryString: String) extends EsQueryMethod
case class EsQueryOrder(field: String, kind: EsOrder)
case class EsQuery(query: EsQueryMethod, sort: Option[EsQueryOrder] = None)

// Schema of ES query results
case class EsSearchHit(source: JsObject)
case class EsSearchHits(hits: Vector[EsSearchHit])
case class EsSearchResult(hits: EsSearchHits)

object ElasticSearchJsonProtocol extends DefaultJsonProtocol {
  implicit object EsQueryTermJsonFormat extends RootJsonFormat[EsQueryTerm] {
    def read(value: JsValue) = ???
    def write(term: EsQueryTerm) = JsObject("term" -> JsObject(term.key -> term.value.toJson))
  }

  implicit object EsQueryStringJsonFormat extends RootJsonFormat[EsQueryString] {
    def read(value: JsValue) = ???
    def write(string: EsQueryString) =
      JsObject("query_string" -> JsObject("query" -> string.queryString.toJson))
  }

  implicit object EsQueryOrderJsonFormat extends RootJsonFormat[EsQueryOrder] {
    def read(value: JsValue) = ???
    def write(order: EsQueryOrder) =
      JsArray(JsObject(order.field -> JsObject("order" -> order.kind.toString.toJson)))
  }

  implicit object EsQueryMethod extends RootJsonFormat[EsQueryMethod] {
    def read(value: JsValue) = ???
    def write(method: EsQueryMethod) = method match {
      case term: EsQueryTerm  => term.toJson
      case str: EsQueryString => str.toJson
    }
  }

  implicit val esQueryFormat = jsonFormat2(EsQuery.apply)
  implicit val esSearchHitFormat = jsonFormat(EsSearchHit.apply _, "_source")
  implicit val esSearchHitsFormat = jsonFormat1(EsSearchHits.apply)
  implicit val esSearchResultFormat = jsonFormat1(EsSearchResult.apply)
}

class ElasticSearchRestClient(
  protocol: String,
  host: String,
  port: Int,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None)(
  implicit system: ActorSystem)
    extends PoolingRestClient(protocol, host, port, 16 * 1024, httpFlow) {

  private val baseHeaders: List[HttpHeader] = List(Accept(MediaTypes.`application/json`))

  /**
   * Method to perform an ElasticSearch query that expects JSON response. If a payload is provided, a POST request will
   * be performed. Otherwise, a GET request will be performed.
   *
   * @param uri     relative path to be used in the request to Elasticsearch
   * @param headers list of HTTP request headers
   * @param payload Optional JSON to be sent in the request
   * @return Future with either the JSON response or the status code of the request, if the request is unsuccessful
   */
  def query(uri: Uri,
            headers: List[HttpHeader] = List.empty,
            payload: Option[JsObject] = None): Future[Either[StatusCode, JsObject]] = {
    payload match {
      case Some(payload) => requestJson[JsObject](mkJsonRequest(HttpMethods.POST, uri, payload, baseHeaders ++ headers))
      case None          => requestJson[JsObject](mkRequest(HttpMethods.GET, uri, baseHeaders ++ headers))
    }
  }
}
