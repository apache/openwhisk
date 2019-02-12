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

package org.apache.openwhisk.core.containerpool.logging

import scala.concurrent.Future
import scala.util.{Either, Try}

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model.headers.Accept
import akka.stream.scaladsl.Flow

import scala.concurrent.Promise
import scala.util.Try

import spray.json._

import org.apache.openwhisk.http.PoolingRestClient
import org.apache.openwhisk.http.PoolingRestClient._

trait EsQueryMethod
trait EsOrder
trait EsRange
trait EsAgg
trait EsMatch

// Schema of ES query operators
case object EsOrderAsc extends EsOrder { override def toString = "asc" }
case object EsOrderDesc extends EsOrder { override def toString = "desc" }
case object EsRangeGte extends EsRange { override def toString = "gte" }
case object EsRangeGt extends EsRange { override def toString = "gt" }
case object EsRangeLte extends EsRange { override def toString = "lte" }
case object EsRangeLt extends EsRange { override def toString = "lt" }
case object EsAggMax extends EsAgg { override def toString = "max" }
case object EsAggMin extends EsAgg { override def toString = "min" }
case object EsMatchPhrase extends EsMatch { override def toString = "phrase" }
case object EsMatchPhrasePrefix extends EsMatch { override def toString = "phrase_prefix" }

// Schema of ES queries
case class EsQueryAggs(aggField: String, agg: EsAgg, field: String)
case class EsQueryRange(key: String, range: EsRange, value: String)
case class EsQueryBoolMatch(key: String, value: String)
case class EsQueryOrder(field: String, kind: EsOrder)
case class EsQueryAll() extends EsQueryMethod
case class EsQueryMust(matches: Vector[EsQueryBoolMatch], range: Vector[EsQueryRange] = Vector.empty)
    extends EsQueryMethod
case class EsQueryMatch(field: String, value: String, matchType: Option[EsMatch] = None) extends EsQueryMethod
case class EsQueryTerm(key: String, value: String) extends EsQueryMethod
case class EsQueryString(queryString: String) extends EsQueryMethod
case class EsQuery(query: EsQueryMethod,
                   sort: Option[EsQueryOrder] = None,
                   size: Option[Int] = None,
                   from: Int = 0,
                   aggs: Option[EsQueryAggs] = None)

// Schema of ES query results
case class EsSearchHit(source: JsObject)
case class EsSearchHits(hits: Vector[EsSearchHit], total: Int)
case class EsSearchResult(hits: EsSearchHits)

object ElasticSearchJsonProtocol extends DefaultJsonProtocol {

  implicit object EsQueryMatchJsonFormat extends RootJsonFormat[EsQueryMatch] {
    def read(query: JsValue) = ???
    def write(query: EsQueryMatch) = {
      val matchQuery = Map("query" -> query.value.toJson) ++ query.matchType.map(m => "type" -> m.toString.toJson)
      JsObject("match" -> JsObject(query.field -> matchQuery.toJson))
    }
  }

  implicit object EsQueryTermJsonFormat extends RootJsonFormat[EsQueryTerm] {
    def read(query: JsValue) = ???
    def write(query: EsQueryTerm) = JsObject("term" -> JsObject(query.key -> query.value.toJson))
  }

  implicit object EsQueryStringJsonFormat extends RootJsonFormat[EsQueryString] {
    def read(query: JsValue) = ???
    def write(query: EsQueryString) =
      JsObject("query_string" -> JsObject("query" -> query.queryString.toJson))
  }

  implicit object EsQueryRangeJsonFormat extends RootJsonFormat[EsQueryRange] {
    def read(query: JsValue) = ???
    def write(query: EsQueryRange) =
      JsObject("range" -> JsObject(query.key -> JsObject(query.range.toString -> query.value.toJson)))
  }

  implicit object EsQueryBoolMatchJsonFormat extends RootJsonFormat[EsQueryBoolMatch] {
    def read(query: JsValue) = ???
    def write(query: EsQueryBoolMatch) = JsObject("match" -> JsObject(query.key -> query.value.toJson))
  }

  implicit object EsQueryMustJsonFormat extends RootJsonFormat[EsQueryMust] {
    def read(query: JsValue) = ???
    def write(query: EsQueryMust) = {
      val boolQuery = Map("must" -> query.matches.toJson) ++ Map("filter" -> query.range.toJson)
        .filter(_._2 != JsArray.empty)
      JsObject("bool" -> boolQuery.toJson)
    }
  }

  implicit object EsQueryOrderJsonFormat extends RootJsonFormat[EsQueryOrder] {
    def read(query: JsValue) = ???
    def write(query: EsQueryOrder) =
      JsArray(JsObject(query.field -> JsObject("order" -> query.kind.toString.toJson)))
  }

  implicit object EsQueryAggsJsonFormat extends RootJsonFormat[EsQueryAggs] {
    def read(query: JsValue) = ???
    def write(query: EsQueryAggs) =
      JsObject(query.aggField -> JsObject(query.agg.toString -> JsObject("field" -> query.field.toJson)))
  }

  implicit object EsQueryAllJsonFormat extends RootJsonFormat[EsQueryAll] {
    def read(query: JsValue) = ???
    def write(query: EsQueryAll) = JsObject("match_all" -> JsObject.empty)
  }

  implicit object EsQueryMethod extends RootJsonFormat[EsQueryMethod] {
    def read(query: JsValue) = ???
    def write(method: EsQueryMethod) = method match {
      case queryTerm: EsQueryTerm     => queryTerm.toJson
      case queryString: EsQueryString => queryString.toJson
      case queryMatch: EsQueryMatch   => queryMatch.toJson
      case queryMust: EsQueryMust     => queryMust.toJson
      case queryAll: EsQueryAll       => queryAll.toJson
    }
  }

  implicit val esQueryFormat = jsonFormat5(EsQuery.apply)
  implicit val esSearchHitFormat = jsonFormat(EsSearchHit.apply _, "_source")
  implicit val esSearchHitsFormat = jsonFormat2(EsSearchHits.apply)
  implicit val esSearchResultFormat = jsonFormat1(EsSearchResult.apply)
}

class ElasticSearchRestClient(
  protocol: String,
  host: String,
  port: Int,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None)(
  implicit system: ActorSystem)
    extends PoolingRestClient(protocol, host, port, 16 * 1024, httpFlow) {

  import ElasticSearchJsonProtocol._

  private val baseHeaders: List[HttpHeader] = List(Accept(MediaTypes.`application/json`))

  def info(headers: List[HttpHeader] = List.empty): Future[Either[StatusCode, JsObject]] = {
    requestJson[JsObject](mkRequest(GET, Uri./, headers = baseHeaders ++ headers))
  }

  def index(index: String, headers: List[HttpHeader] = List.empty): Future[Either[StatusCode, JsObject]] = {
    requestJson[JsObject](mkRequest(GET, Uri(index), headers = baseHeaders ++ headers))
  }

  def search[T: RootJsonReader](index: String,
                                payload: EsQuery = EsQuery(EsQueryAll()),
                                headers: List[HttpHeader] = List.empty): Future[Either[StatusCode, T]] =
    requestJson[T](mkJsonRequest(POST, Uri(index), payload.toJson.asJsObject, baseHeaders ++ headers))
}
