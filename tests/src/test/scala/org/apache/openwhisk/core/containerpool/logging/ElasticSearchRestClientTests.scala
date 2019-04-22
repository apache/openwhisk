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

import spray.json._
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.scaladsl.Flow
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.http.scaladsl.model.HttpMethods.POST
import common.StreamLogging
import org.apache.openwhisk.core.containerpool.logging.ElasticSearchJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Success, Try}

@RunWith(classOf[JUnitRunner])
class ElasticSearchRestClientTests
    extends TestKit(ActorSystem("ElasticSearchRestClient"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StreamLogging {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val defaultResponseSource =
    """{"stream":"stdout","activationId":"197d60b33137424ebd60b33137d24ea3","action":"guest/someAction","@version":"1","@timestamp":"2018-03-27T15:48:09.112Z","type":"user_logs","tenant":"19bc46b1-71f6-4ed5-8c54-816aa4f8c502","message":"namespace     : user@email.com\n","time_date":"2018-03-27T15:48:08.716152793Z"}"""
  private val defaultResponse =
    s"""{"took":2,"timed_out":false,"_shards":{"total":5,"successful":5,"failed":0},"hits":{"total":1375,"max_score":1.0,"hits":[{"_index":"whisk_user_logs","_type":"user_logs","_id":"AWJoJSwAMGbzgxiD1jr9","_score":1.0,"_source":$defaultResponseSource}]}}"""
  private val defaultHttpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, defaultResponse))
  private val defaultHttpRequest = HttpRequest(
    POST,
    headers = List(Accept(MediaTypes.`application/json`)),
    entity = HttpEntity(ContentTypes.`application/json`, EsQuery(EsQueryAll()).toJson.toString))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def testFlow(httpResponse: HttpResponse = HttpResponse(), httpRequest: HttpRequest = HttpRequest())
    : Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          request shouldBe httpRequest
          Future.successful((Success(httpResponse), userContext))
      }

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  behavior of "ElasticSearch Rest Client"

  it should "construct a query with must" in {
    val queryTerms = Vector(EsQueryBoolMatch("someKey1", "someValue1"), EsQueryBoolMatch("someKey2", "someValue2"))
    val queryMust = EsQueryMust(queryTerms)

    EsQuery(queryMust).toJson shouldBe JsObject(
      "query" ->
        JsObject(
          "bool" ->
            JsObject(
              "must" ->
                JsArray(
                  JsObject("match" -> JsObject("someKey1" -> JsString("someValue1"))),
                  JsObject("match" -> JsObject("someKey2" -> JsString("someValue2")))))),
      "from" -> 0.toJson)

    // Test must with ranges
    Seq((EsRangeGte, "gte"), (EsRangeGt, "gt"), (EsRangeLte, "lte"), (EsRangeLt, "lt")).foreach {
      case (rangeArg, rangeValue) =>
        val queryRange1 = EsQueryRange("someKey1", rangeArg, "someValue1")
        val queryRange2 = EsQueryRange("someKey2", rangeArg, "someValue2")
        val queryTerms = Vector(EsQueryBoolMatch("someKey1", "someValue1"), EsQueryBoolMatch("someKey2", "someValue2"))
        val queryMust = EsQueryMust(queryTerms, Vector(queryRange1, queryRange2))

        EsQuery(queryMust).toJson shouldBe JsObject(
          "query" ->
            JsObject(
              "bool" ->
                JsObject(
                  "must" ->
                    JsArray(
                      JsObject("match" -> JsObject("someKey1" -> JsString("someValue1"))),
                      JsObject("match" -> JsObject("someKey2" -> JsString("someValue2")))),
                  "filter" ->
                    JsArray(
                      JsObject("range" -> JsObject("someKey1" -> JsObject(rangeValue -> "someValue1".toJson))),
                      JsObject("range" -> JsObject("someKey2" -> JsObject(rangeValue -> "someValue2".toJson)))))),
          "from" -> 0.toJson)
    }
  }

  it should "construct a query with aggregations" in {
    Seq((EsAggMax, "max"), (EsAggMin, "min")).foreach {
      case (aggArg, aggValue) =>
        val queryAgg = EsQueryAggs("someAgg", aggArg, "someField")

        EsQuery(EsQueryAll(), aggs = Some(queryAgg)).toJson shouldBe JsObject(
          "query" -> JsObject("match_all" -> JsObject.empty),
          "aggs" -> JsObject("someAgg" -> JsObject(aggValue -> JsObject("field" -> "someField".toJson))),
          "from" -> 0.toJson)
    }
  }

  it should "construct a query with match" in {
    val queryMatch = EsQueryMatch("someField", "someValue")

    EsQuery(queryMatch).toJson shouldBe JsObject(
      "query" -> JsObject("match" -> JsObject("someField" -> JsObject("query" -> "someValue".toJson))),
      "from" -> 0.toJson)

    // Test match with types
    Seq((EsMatchPhrase, "phrase"), (EsMatchPhrasePrefix, "phrase_prefix")).foreach {
      case (typeArg, typeValue) =>
        val queryMatch = EsQueryMatch("someField", "someValue", Some(typeArg))

        EsQuery(queryMatch).toJson shouldBe JsObject(
          "query" -> JsObject(
            "match" -> JsObject("someField" -> JsObject("query" -> "someValue".toJson, "type" -> typeValue.toJson))),
          "from" -> 0.toJson)
    }
  }

  it should "construct a query with term" in {
    val queryTerm = EsQueryTerm("user", "someUser")

    EsQuery(queryTerm).toJson shouldBe JsObject(
      "query" -> JsObject("term" -> JsObject("user" -> JsString("someUser"))),
      "from" -> 0.toJson)
  }

  it should "construct a query with query string" in {
    val queryString = EsQueryString("_type: someType")

    EsQuery(queryString).toJson shouldBe JsObject(
      "query" -> JsObject("query_string" -> JsObject("query" -> JsString("_type: someType"))),
      "from" -> 0.toJson)
  }

  it should "construct a query with order" in {
    Seq((EsOrderAsc, "asc"), (EsOrderDesc, "desc")).foreach {
      case (orderArg, orderValue) =>
        val queryOrder = EsQueryOrder("someField", orderArg)

        EsQuery(EsQueryAll(), Some(queryOrder)).toJson shouldBe JsObject(
          "query" -> JsObject("match_all" -> JsObject.empty),
          "sort" -> JsArray(JsObject("someField" -> JsObject("order" -> orderValue.toJson))),
          "from" -> 0.toJson)
    }
  }

  it should "construct query with size" in {
    EsQuery(EsQueryAll(), size = Some(1)).toJson shouldBe JsObject(
      "query" -> JsObject("match_all" -> JsObject.empty),
      "size" -> 1.toJson,
      "from" -> 0.toJson)
  }

  it should "construct query with from" in {
    EsQuery(EsQueryAll(), from = 1).toJson shouldBe JsObject(
      "query" -> JsObject("match_all" -> JsObject.empty),
      "from" -> 1.toJson)
  }

  it should "error when search response does not match expected type" in {
    val esClient = new ElasticSearchRestClient("https", "host", 443, Some(testFlow(httpRequest = defaultHttpRequest)))

    a[RuntimeException] should be thrownBy await(esClient.search[JsObject]("/"))
  }

  it should "parse search response into EsSearchResult" in {
    val esClient =
      new ElasticSearchRestClient("https", "host", 443, Some(testFlow(defaultHttpResponse, defaultHttpRequest)))
    val response = await(esClient.search[EsSearchResult]("/"))

    response shouldBe 'right
    response.right.get.hits.hits should have size 1
    response.right.get.hits.total shouldBe 1375
    response.right.get.hits.hits(0).source shouldBe defaultResponseSource.parseJson.asJsObject
  }

  it should "return status code when HTTP error occurs" in {
    val httpResponse = HttpResponse(StatusCodes.InternalServerError)
    val esClient = new ElasticSearchRestClient("https", "host", 443, Some(testFlow(httpResponse, defaultHttpRequest)))
    val response = await(esClient.search[JsObject]("/"))

    response shouldBe 'left
    response.left.get shouldBe StatusCodes.InternalServerError
  }

  it should "perform info request" in {
    val responseBody = s"""{"cluster_name" : "elasticsearch"}"""
    val httpRequest = HttpRequest(headers = List(Accept(MediaTypes.`application/json`)))
    val httpResponse = HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, responseBody))
    val esClient =
      new ElasticSearchRestClient("https", "host", 443, Some(testFlow(httpResponse, httpRequest)))
    val response = await(esClient.info())

    response shouldBe 'right
    response.right.get shouldBe responseBody.parseJson.asJsObject
  }

  it should "perform index request" in {
    val responseBody = s"""{"some_index" : {}}"""
    val httpRequest = HttpRequest(uri = Uri("some_index"), headers = List(Accept(MediaTypes.`application/json`)))
    val httpResponse = HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, responseBody))
    val esClient =
      new ElasticSearchRestClient("https", "host", 443, Some(testFlow(httpResponse, httpRequest)))
    val response = await(esClient.index("some_index"))

    response shouldBe 'right
    response.right.get shouldBe responseBody.parseJson.asJsObject
  }
}
