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

package whisk.http

import org.junit.runner.RunWith
import org.scalatest.{FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.BeforeAndAfterEach

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import common.StreamLogging

import spray.json.JsObject
import spray.json.DefaultJsonProtocol._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

@RunWith(classOf[JUnitRunner])
class PoolingRestClientTests
    extends TestKit(ActorSystem("PoolingRestClientTests"))
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with StreamLogging
    with BeforeAndAfterEach {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  var requestHttpResponse = HttpResponse(OK, entity = HttpEntity.Empty)
  var requestUri = Uri./
  var requestHeaders: List[HttpHeader] = List.empty
  var requestPayload = ""

  val testFlow: Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          Unmarshal(request.entity)
            .to[String]
            .map { payload =>
              request.uri shouldBe requestUri
              request.headers shouldBe requestHeaders
              payload shouldBe requestPayload
              (Success(requestHttpResponse), userContext)
            }
            .recover {
              case e =>
                (Failure(e), userContext)
            }
      }

  def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  override def beforeEach = {
    requestHttpResponse = HttpResponse(OK)
    requestUri = Uri./
    requestHeaders = List.empty
    requestPayload = ""
  }

  behavior of "Pooling REST Client"

  it should "error when configuration protocol is invalid" in {
    a[IllegalArgumentException] should be thrownBy new PoolingRestClient("invalid", "host", 443, 1, Some(testFlow))
  }

  it should "get a non-200 status code when performing a request" in {
    requestHttpResponse = HttpResponse(InternalServerError)
    val expectedResponse = HttpResponse(InternalServerError, List.empty, HttpEntity.Empty, HttpProtocol("HTTP/1.1"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val response = poolingRestClient.request0(Future.successful(HttpRequest(GET, Uri./)))
    await(response) shouldBe expectedResponse
  }

  it should "return payload from a request" in {
    requestHttpResponse =
      HttpResponse(OK, entity = HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint))
    val expectedResponse = HttpResponse(
      OK,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    val request = HttpRequest(GET, Uri./)
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val response = poolingRestClient.request0(Future.successful(request))
    await(response) shouldBe expectedResponse
  }

  it should "send headers when making a request" in {
    requestHttpResponse =
      HttpResponse(OK, entity = HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint))
    val expectedResponse = HttpResponse(
      OK,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    requestHeaders = List(RawHeader("key", "value"))
    val request = HttpRequest(GET, Uri./, requestHeaders)
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val response = poolingRestClient.request0(Future.successful(request))
    await(response) shouldBe expectedResponse
  }

  it should "send uri when making a request" in {
    requestHttpResponse =
      HttpResponse(OK, entity = HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint))
    val expectedResponse = HttpResponse(
      OK,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    requestUri = Uri("/some/where")
    val request = HttpRequest(GET, requestUri)
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val response = poolingRestClient.request0(Future.successful(request))
    await(response) shouldBe expectedResponse
  }

  it should "send a payload when making a request" in {
    requestHttpResponse =
      HttpResponse(OK, entity = HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint))
    requestPayload = "payload"
    val expectedResponse = HttpResponse(
      OK,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    val request = HttpRequest(POST, Uri./, entity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, requestPayload))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val response = poolingRestClient.request0(Future.successful(request))
    await(response) shouldBe expectedResponse
  }

  it should "return JSON when making a request" in {
    requestHttpResponse =
      HttpResponse(OK, entity = HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint))
    requestPayload = JsObject().compactPrint
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val request = poolingRestClient.mkJsonRequest(GET, Uri./, JsObject(), List.empty)
    val response = poolingRestClient.requestJson[JsObject](request)
    await(response) shouldBe Right(JsObject())
  }

  it should "return a status code on request failure" in {
    requestHttpResponse = HttpResponse(NotFound, entity = HttpEntity.Empty)
    requestPayload = JsObject().compactPrint
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val request = poolingRestClient.mkJsonRequest(GET, Uri./, JsObject(), List.empty)
    val response = poolingRestClient.requestJson[JsObject](request)
    await(response) shouldBe Left(NotFound)
  }

  it should "create an HttpRequest without a payload" in {
    val expectedResponse = HttpRequest(GET, Uri./, List.empty, HttpEntity.Empty, HttpProtocol("HTTP/1.1"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val request = poolingRestClient.mkRequest(GET, Uri./)
    await(request) shouldBe expectedResponse
  }

  it should "create an HttpRequest with a JSON payload" in {
    val expectedResponse = HttpRequest(
      GET,
      Uri./,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val request = poolingRestClient.mkJsonRequest(GET, Uri./, JsObject(), List.empty)
    await(request) shouldBe expectedResponse
  }

  it should "create an HttpRequest with a payload" in {
    val expectedResponse = HttpRequest(
      GET,
      Uri./,
      List.empty,
      HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint),
      HttpProtocol("HTTP/1.1"))
    val poolingRestClient = new PoolingRestClient("https", "host", 443, 1, Some(testFlow))
    val request = poolingRestClient.mkRequest0(
      GET,
      Uri./,
      Future.successful(HttpEntity(ContentTypes.`application/json`, JsObject().compactPrint)),
      List.empty)
    await(request) shouldBe expectedResponse
  }

}
