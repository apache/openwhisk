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

package org.apache.openwhisk.http

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, NotFound}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshaller.UnsupportedContentTypeException
import common.StreamLogging
import spray.json.JsObject
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Success, Try}
import org.apache.openwhisk.http.PoolingRestClient._

@RunWith(classOf[JUnitRunner])
class PoolingRestClientTests
    extends TestKit(ActorSystem("PoolingRestClientTests"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StreamLogging {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  def testFlow(httpResponse: HttpResponse = HttpResponse(), httpRequest: HttpRequest = HttpRequest())
    : Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          request shouldBe httpRequest
          Future.successful((Success(httpResponse), userContext))
      }

  def failFlow(httpResponse: HttpResponse = HttpResponse(), httpRequest: HttpRequest = HttpRequest())
    : Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          Future.failed(new Exception)
      }

  def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  behavior of "Pooling REST Client"

  it should "error when configuration protocol is invalid" in {
    a[IllegalArgumentException] should be thrownBy new PoolingRestClient("invalid", "host", 443, 1)
  }

  it should "get a non-200 status code when performing a request" in {
    val httpResponse = HttpResponse(InternalServerError)
    val httpRequest = HttpRequest()
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse)))

    await(poolingRestClient.request(Future.successful(httpRequest))) shouldBe httpResponse
  }

  it should "return payload from a request" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest()
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse)))

    await(poolingRestClient.request(Future.successful(httpRequest))) shouldBe httpResponse
  }

  it should "send headers when making a request" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest(headers = List(RawHeader("key", "value")))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))

    await(poolingRestClient.request(Future.successful(httpRequest))) shouldBe httpResponse
  }

  it should "send uri when making a request" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest(uri = Uri("/some/where"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))

    await(poolingRestClient.request(Future.successful(httpRequest))) shouldBe httpResponse
  }

  it should "send a payload when making a request" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest(POST, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "payload"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))

    await(poolingRestClient.request(Future.successful(httpRequest))) shouldBe httpResponse
  }

  it should "return JSON when making a request" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))
    val request = mkJsonRequest(GET, Uri./, JsObject.empty, List.empty)

    await(poolingRestClient.requestJson[JsObject](request)) shouldBe Right(JsObject.empty)
  }

  it should "throw timeout exception when Future fails in httpFlow" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(failFlow(httpResponse, httpRequest)))
    val request = mkJsonRequest(GET, Uri./, JsObject.empty, List.empty)

    a[TimeoutException] should be thrownBy await(poolingRestClient.requestJson[JsObject](request))
  }

  it should "return a status code on request failure" in {
    val body = "Limit is too large, must not exceed 268435456"
    val httpResponse = HttpResponse(NotFound, entity = body)
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))
    val request = mkJsonRequest(GET, Uri./, JsObject.empty, List.empty)

    val reqResult = await(poolingRestClient.requestJson[JsObject](request))
    reqResult shouldBe Left(NotFound)
    reqResult.left.get.reason shouldBe s"${NotFound.reason} (details: ${body})"
  }

  it should "throw an unsupported content-type exception when unexpected content-type is returned" in {
    val httpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "plain text"))
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow(httpResponse, httpRequest)))
    val request = mkJsonRequest(GET, Uri./, JsObject.empty, List.empty)

    a[UnsupportedContentTypeException] should be thrownBy await(poolingRestClient.requestJson[JsObject](request))
  }

  it should "create an HttpRequest without a payload" in {
    val httpRequest = HttpRequest()

    await(mkRequest(GET, Uri./)) shouldBe httpRequest
  }

  it should "create an HttpRequest with a JSON payload" in {
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))

    await(mkJsonRequest(GET, Uri./, JsObject.empty, List.empty)) shouldBe httpRequest
  }

  it should "create an HttpRequest with a payload" in {
    val httpRequest = HttpRequest(entity = HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint))
    val request = mkRequest(
      GET,
      Uri./,
      Future.successful(HttpEntity(ContentTypes.`application/json`, JsObject.empty.compactPrint)),
      List.empty)

    await(request) shouldBe httpRequest
  }

}
