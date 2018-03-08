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

import spray.json._

import org.junit.runner.RunWith
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import common.StreamLogging

import whisk.core.containerpool.logging.ElasticSearchJsonProtocol._

@RunWith(classOf[JUnitRunner])
class ElasticSearchRestClientTests extends FlatSpecLike with Matchers with ScalaFutures with StreamLogging {

  it should "construct a query with term" in {
    val queryTerm = EsQueryTerm("user", "someUser")
    val query = EsQuery(queryTerm)

    query.toJson shouldBe JsObject("query" -> JsObject("term" -> JsObject("user" -> JsString("someUser"))))
  }

  it should "construct a query with query string" in {
    val queryString = EsQueryString("_type: someType")
    val query = EsQuery(queryString)

    query.toJson shouldBe JsObject(
      "query" -> JsObject("query_string" -> JsObject("query" -> JsString("_type: someType"))))
  }

  it should "construct a query with term and order" in {
    val queryTerm = EsQueryTerm("user", "someUser")
    val queryOrder = EsQueryOrder("time_date", EsOrderDesc)
    val query = EsQuery(queryTerm, Some(queryOrder))

    query.toJson shouldBe JsObject(
      "query" -> JsObject("term" -> JsObject("user" -> JsString("someUser"))),
      "sort" -> JsArray(JsObject("time_date" -> JsObject("order" -> JsString("desc")))))
  }

  it should "construct a query with query string and order" in {
    val queryString = EsQueryString("_type: someType")
    val queryOrder = EsQueryOrder("time_date", EsOrderAsc)
    val query = EsQuery(queryString, Some(queryOrder))

    query.toJson shouldBe JsObject(
      "query" -> JsObject("query_string" -> JsObject("query" -> JsString("_type: someType"))),
      "sort" -> JsArray(JsObject("time_date" -> JsObject("order" -> JsString("asc")))))
  }
}
