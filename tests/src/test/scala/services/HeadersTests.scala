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

package services

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.collection.immutable.Seq
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.Span.convertDurationToSpan
import common.TestUtils
import common.WhiskProperties
import common.rest.{HttpConnection, WskRestOperations}
import common.WskProps
import common.WskTestHelpers
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes.Accepted
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.HttpMethods.DELETE
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpHeader
import common.WskActorSystem
import pureconfig._

@RunWith(classOf[JUnitRunner])
class HeadersTests extends FlatSpec with Matchers with ScalaFutures with WskActorSystem with WskTestHelpers {

  behavior of "Headers at general API"

  val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")
  val whiskAuth = WhiskProperties.getBasicAuth
  val creds = BasicHttpCredentials(whiskAuth.fst, whiskAuth.snd)
  val allMethods = Some(Set(DELETE.name, GET.name, POST.name, PUT.name))
  val allowOrigin = `Access-Control-Allow-Origin`.*
  val allowHeaders = `Access-Control-Allow-Headers`(
    "Authorization",
    "Origin",
    "X-Requested-With",
    "Content-Type",
    "Accept",
    "User-Agent")
  val url = Uri(s"$controllerProtocol://${WhiskProperties.getBaseControllerAddress()}")

  def request(method: HttpMethod, uri: Uri, headers: Option[Seq[HttpHeader]] = None): Future[HttpResponse] = {
    val httpRequest = headers match {
      case Some(headers) => HttpRequest(method, uri, headers)
      case None          => HttpRequest(method, uri)
    }

    val connectionContext = HttpConnection.getContext(controllerProtocol)
    Http().singleRequest(httpRequest, connectionContext = connectionContext)
  }

  implicit val config = PatienceConfig(10 seconds, 100 milliseconds)

  val basePath = Path("/api/v1")
  implicit val wskprops = WskProps()
  val wsk = new WskRestOperations

  /**
   * Checks, if the required headers are in the list of all headers.
   * For the allowed method, it checks, if only the allowed methods are in the response headers.
   */
  def containsHeaders(headers: Seq[HttpHeader], allowedMethods: Option[Set[String]] = None) = {
    headers should contain allOf (allowOrigin, allowHeaders)

    // TODO: commented out for now as allowed methods are not supported currently
    //        val headersMap = headers map { header =>
    //            header.name -> header.value.split(",").map(_.trim).toSet
    //        } toMap
    //        allowedMethods map { allowedMethods =>
    //            headersMap should contain key "Access-Control-Allow-Methods"
    //            headersMap("Access-Control-Allow-Methods") should contain theSameElementsAs (allowedMethods)
    //        }
  }

  it should "respond to OPTIONS with all headers" in {
    request(OPTIONS, url.withPath(basePath)).futureValue.headers should contain allOf (allowOrigin, allowHeaders)
  }

  ignore should "not respond to OPTIONS for non existing path" in {
    val path = basePath / "foo" / "bar"

    request(OPTIONS, url.withPath(path)).futureValue.status should not be OK
  }

  // Actions
  it should "respond to OPTIONS for listing actions" in {
    val path = basePath / "namespaces" / "barfoo" / "actions"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for actions path" in {
    val path = basePath / "namespaces" / "barfoo" / "actions" / "foobar"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, allMethods)
  }

  it should "respond to POST action with headers" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val packageName = "samples"
    val actionName = "helloWorld"
    val fullActionName = s"$packageName/$actionName"
    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))
    }

    assetHelper.withCleaner(wsk.action, fullActionName) { (action, _) =>
      action.create(fullActionName, Some(TestUtils.getTestActionFilename("hello.js")))
    }
    val path = basePath / "namespaces" / "_" / "actions" / packageName / actionName
    val response = request(POST, url.withPath(path), Some(List(Authorization(creds)))).futureValue

    response.status shouldBe Accepted
    containsHeaders(response.headers)
  }

  // Activations
  it should "respond to OPTIONS for listing activations" in {
    val path = basePath / "namespaces" / "barfoo" / "activations"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for activations get" in {
    val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for activations logs" in {
    val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar" / "logs"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for activations results" in {
    val path = basePath / "namespaces" / "barfoo" / "activations" / "foobar" / "result"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to GET for listing activations with Headers" in {
    val path = basePath / "namespaces" / "_" / "activations"
    val response = request(GET, url.withPath(path), Some(List(Authorization(creds)))).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers)
  }

  // Namespaces
  it should "respond to OPTIONS for listing namespaces" in {
    val path = basePath / "namespaces"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  // Packages
  it should "respond to OPTIONS for listing packages" in {
    val path = basePath / "namespaces" / "barfoo" / "packages"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for packages path" in {
    val path = basePath / "namespaces" / "barfoo" / "packages" / "foobar"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("DELETE", "GET", "PUT")))
  }

  it should "respond to GET for listing packages with headers" in {
    val path = basePath / "namespaces" / "_" / "packages"
    val response = request(GET, url.withPath(path), Some(List(Authorization(creds)))).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers)
  }

  // Rules
  it should "respond to OPTIONS for listing rules" in {
    val path = basePath / "namespaces" / "barfoo" / "rules"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for rules path" in {
    val path = basePath / "namespaces" / "barfoo" / "rules" / "foobar"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, allMethods)
  }

  it should "respond to GET for listing rules with headers" in {
    val path = basePath / "namespaces" / "_" / "rules"
    val response = request(GET, url.withPath(path), Some(List(Authorization(creds)))).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers)
  }

  // Triggers
  it should "respond to OPTIONS for listing triggers" in {
    val path = basePath / "namespaces" / "barfoo" / "triggers"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, Some(Set("GET")))
  }

  it should "respond to OPTIONS for triggers path" in {
    val path = basePath / "namespaces" / "barfoo" / "triggers" / "foobar"
    val response = request(OPTIONS, url.withPath(path)).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers, allMethods)
  }

  it should "respond to GET for listing triggers with headers" in {
    val path = basePath / "namespaces" / "_" / "triggers"
    val response = request(GET, url.withPath(path), Some(List(Authorization(creds)))).futureValue

    response.status shouldBe OK
    containsHeaders(response.headers)
  }
}
