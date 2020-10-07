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

package org.apache.openwhisk.core.cli.test

import java.util.Base64

import scala.util.Failure
import scala.util.Try
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import io.restassured.RestAssured
import io.restassured.http.Header
import common._
import common.rest.WskRestOperations
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil
import org.apache.openwhisk.common.PrintStreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Subject

/**
 * Tests web actions.
 */
@RunWith(classOf[JUnitRunner])
class WskWebActionsTests extends TestHelpers with WskTestHelpers with RestUtil with WskActorSystem {
  val MAX_URL_LENGTH = 8192 // 8K matching nginx default

  private implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations
  lazy val namespace = wsk.namespace.whois()

  protected val testRoutePath: String = "/api/v1/web"

  behavior of "Wsk Web Actions"

  it should "ensure __ow_headers contains the proper content-type" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webContenttype"
    val file = Some(TestUtils.getTestActionFilename("echo.js"))
    val bodyContent = JsObject("key" -> "value".toJson)
    val host = getServiceURL()
    val url = s"$host$testRoutePath/$namespace/default/$name.json"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("true"))
    }

    val resWithContentType =
      RestAssured.given().contentType("application/json").body(bodyContent.compactPrint).config(sslconfig).post(url)

    resWithContentType.statusCode shouldBe 200
    resWithContentType.header("Content-type") shouldBe "application/json"
    resWithContentType.body.asString.parseJson.asJsObject
      .fields("__ow_headers")
      .asJsObject
      .fields("content-type") shouldBe "application/json".toJson

    val resWithoutContentType =
      RestAssured.given().config(sslconfig).get(url)

    resWithoutContentType.statusCode shouldBe 200
    resWithoutContentType.header("Content-type") shouldBe "application/json"
    resWithoutContentType.body.asString.parseJson.asJsObject
      .fields("__ow_headers")
      .toString should not include ("content-type")
  }

  /**
   * Tests web actions, plus max url limit.
   */
  it should "create a web action accessible via HTTPS" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webaction"
    val file = Some(TestUtils.getTestActionFilename("echo.js"))
    val host = getServiceURL()
    val requestPath = host + s"$testRoutePath/$namespace/default/$name.json/a?a="
    val padAmount = MAX_URL_LENGTH - requestPath.length

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("true"))
    }

    Seq(
      ("A", 200),
      ("A" * padAmount, 200),
      // ideally the bad case is just +1 but there's some differences
      // in how characters are counted i.e., whether these count "https://:443"
      // or not; it seems sufficient to test right around the boundary
      ("A" * (padAmount + 100), 414))
      .foreach {
        case (pad, code) =>
          val url = (requestPath + pad)
          val response = RestAssured.given().config(sslconfig).get(url)
          val responseCode = response.statusCode

          withClue(s"response code: $responseCode, url length: ${url.length}, pad amount: ${pad.length}, url: $url") {
            responseCode shouldBe code
            if (code == 200) {
              response.body.asString.parseJson.asJsObject.fields("a").convertTo[String] shouldBe pad
            } else {
              response.body.asString should include("414 Request-URI Too Large") // from nginx
            }
          }
      }
  }

  /**
   * Tests web action requiring authentication.
   */
  it should "create a web action requiring authentication accessible via HTTPS" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))
      val host = getServiceURL()
      val url = s"$host$testRoutePath/$namespace/default/$name.json"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"), annotations = Map("require-whisk-auth" -> true.toJson))
      }

      val unauthorizedResponse = RestAssured.given().config(sslconfig).get(url)
      unauthorizedResponse.statusCode shouldBe 401

      val authorizedResponse = RestAssured
        .given()
        .config(sslconfig)
        .auth()
        .preemptive()
        .basic(wskprops.authKey.split(":")(0), wskprops.authKey.split(":")(1))
        .get(url)

      authorizedResponse.statusCode shouldBe 200
      authorizedResponse.body.asString.parseJson.asJsObject.fields("__ow_user").convertTo[String] shouldBe namespace
  }

  /**
   * Tests web action not requiring authentication.
   */
  it should "create a web action not requiring authentication accessible via HTTPS" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))
      val host = getServiceURL()
      val url = s"$host$testRoutePath/$namespace/default/$name.json"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"), annotations = Map("require-whisk-auth" -> false.toJson))
      }

      val unauthorizedResponse = RestAssured.given().config(sslconfig).get(url)
      unauthorizedResponse.statusCode shouldBe 200
  }

  it should "ensure that CORS header is preserved for custom options" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("corsHeaderMod.js"))
      val host = getServiceURL()
      val url = host + s"$testRoutePath/$namespace/default/$name.http"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"), annotations = Map("web-custom-options" -> true.toJson))
      }

      val response = RestAssured.given().config(sslconfig).options(url)

      response.statusCode shouldBe 200
      response.header("Access-Control-Allow-Origin") shouldBe "Origin set from Web Action"
      response.header("Access-Control-Allow-Methods") shouldBe "Methods set from Web Action"
      response.header("Access-Control-Allow-Headers") shouldBe "Headers set from Web Action"
      response.header("Location") shouldBe "openwhisk.org"
      response.header("Set-Cookie") shouldBe "cookie-cookie-cookie"
  }

  it should "ensure that default CORS header is preserved" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webaction"
    val file = Some(TestUtils.getTestActionFilename("corsHeaderMod.js"))
    val host = getServiceURL()
    val url = host + s"$testRoutePath/$namespace/default/$name"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("true"))
    }

    Seq(
      RestAssured
        .given()
        .config(sslconfig)
        .header("Access-Control-Request-Headers", "x-custom-header")
        .options(s"$url.http"),
      RestAssured
        .given()
        .config(sslconfig)
        .header("Access-Control-Request-Headers", "x-custom-header")
        .get(s"$url.json")).foreach { response =>
      response.statusCode shouldBe 200
      response.header("Access-Control-Allow-Origin") shouldBe "*"
      response.header("Access-Control-Allow-Methods") shouldBe "OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
      response.header("Access-Control-Allow-Headers") shouldBe "x-custom-header"
      response.header("Location") shouldBe null
      response.header("Set-Cookie") shouldBe null
    }

    Seq(
      RestAssured.given().config(sslconfig).options(s"$url.http"),
      RestAssured.given().config(sslconfig).get(s"$url.json")).foreach { response =>
      response.statusCode shouldBe 200
      response.header("Access-Control-Allow-Origin") shouldBe "*"
      response.header("Access-Control-Allow-Methods") shouldBe "OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
      response.header("Access-Control-Allow-Headers") shouldBe "Authorization, Origin, X-Requested-With, Content-Type, Accept, User-Agent"
      response.header("Location") shouldBe null
      response.header("Set-Cookie") shouldBe null
    }
  }

  it should "invoke web action to ensure the returned body argument is correct" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("echo.js"))
      val bodyContent = "This is the body"
      val host = getServiceURL()
      val url = s"$host$testRoutePath/$namespace/default/webaction.json"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"))
      }

      val paramRes = RestAssured.given().contentType("text/html").param("key", "value").config(sslconfig).post(url)
      paramRes.statusCode shouldBe 200
      paramRes.body.asString().parseJson.asJsObject.fields("__ow_body").convertTo[String] shouldBe "key=value"

      val bodyRes = RestAssured.given().contentType("text/html").body(bodyContent).config(sslconfig).post(url)
      bodyRes.statusCode shouldBe 200
      bodyRes.body.asString().parseJson.asJsObject.fields("__ow_body").convertTo[String] shouldBe bodyContent
  }

  it should "reject invocation of web action with invalid accept header" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "webaction"
      val file = Some(TestUtils.getTestActionFilename("textBody.js"))
      val host = getServiceURL()
      val url = host + s"$testRoutePath/$namespace/default/$name.http"

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"))
      }

      val response = RestAssured.given().header("accept", "application/json").config(sslconfig).get(url)
      response.statusCode shouldBe 406
      response.body.asString should include("Resource representation is only available with these types:\\ntext/html")
  }

  it should "support multiple response header values" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webaction"
    val file = Some(TestUtils.getTestActionFilename("multipleHeaders.js"))
    val host = getServiceURL()
    val url = host + s"$testRoutePath/$namespace/default/$name.http"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("true"), annotations = Map("web-custom-options" -> true.toJson))
    }

    val response = RestAssured.given().config(sslconfig).options(url)

    response.statusCode shouldBe 200
    val cookieHeaders = response.headers.getList("Set-Cookie")
    cookieHeaders should contain allOf (new Header("Set-Cookie", "a=b"),
    new Header("Set-Cookie", "c=d"))
  }

  it should "handle http web action returning JSON as string" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "jsonStringWebAction"
    val file = Some(TestUtils.getTestActionFilename("jsonStringWebAction.js"))
    val host = getServiceURL
    val url = host + s"$testRoutePath/$namespace/default/$name.http"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("raw"))
    }

    val response = RestAssured.given().config(sslconfig).get(url)

    response.statusCode shouldBe 200
    response.header("Content-type") shouldBe "application/json"
    response.body.asString.parseJson.asJsObject shouldBe JsObject("status" -> "success".toJson)
  }

  it should "handle http web action with base64 encoded binary response" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val name = "binaryWeb"
      val file = Some(TestUtils.getTestActionFilename("pngWeb.js"))
      val host = getServiceURL
      val url = host + s"$testRoutePath/$namespace/default/$name.http"
      val png = "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAGCAYAAAD68A/GAAAA/klEQVQYGWNgAAEHBxaG//+ZQMyyn581Pfas+cRQnf1LfF" +
        "Ljf+62smUgcUbt0FA2Zh7drf/ffMy9vLn3RurrW9e5hCU11i2azfD4zu1/DHz8TAy/foUxsXBrFzHzC7r8+M9S1vn1qxQT07dDjL" +
        "9fdemrqKxlYGT6z8AIMo6hgeUfA0PUvy9fGFh5GWK3z7vNxSWt++jX99+8SoyiGQwsW38w8PJEM7x5v5SJ8f+/xv8MDAzffv9hev" +
        "fkWjiXBGMpMx+j2awovjcMjFztDO8+7GF49LkbZDCDeXLTWnZO7qDfn1/+5jbw/8pjYWS4wZLztXnuEuYTk2M+MzIw/AcA36Vewa" +
        "D6fzsAAAAASUVORK5CYII="

      assetHelper.withCleaner(wsk.action, name) { (action, _) =>
        action.create(name, file, web = Some("true"))
      }

      val response = RestAssured.given().config(sslconfig).get(url)

      response.statusCode shouldBe 200
      response.header("Content-type") shouldBe "image/png"
      response.body.asByteArray shouldBe Base64.getDecoder().decode(png)
  }

  /**
   * Tests web action for HEAD requests
   */
  it should "create a web action making a HEAD request" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "webactionHead"
    val file = Some(TestUtils.getTestActionFilename("echo-web-http-head.js"))
    val host = getServiceURL()
    val url = s"$host$testRoutePath/$namespace/default/$name"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, file, web = Some("true"))
    }

    val authorizedResponse = RestAssured
      .given()
      .config(sslconfig)
      .auth()
      .preemptive()
      .basic(wskprops.authKey.split(":")(0), wskprops.authKey.split(":")(1))
      .head(url)

    authorizedResponse.statusCode shouldBe 200
    authorizedResponse.body.asString() shouldBe ""
    authorizedResponse.getHeader("Request-type") shouldBe "head"
  }

  private val subdomainRegex = Seq.fill(WhiskProperties.getPartsInVanitySubdomain)("[a-zA-Z0-9]+").mkString("-")

  private lazy val (vanitySubdomain, vanityNamespace, makeTestSubject) = {
    if (namespace.matches(subdomainRegex)) {
      (namespace, namespace, false)
    } else {
      val s = Subject().asString.toLowerCase // this will generate two confirming parts
      (s, s.replace("-", "_"), true)
    }
  }

  private lazy val wskPropsForSubdomainTest = if (makeTestSubject) {
    getAdditionalTestSubject(vanityNamespace) // create new subject for the test
  } else {
    WskProps()
  }

  override def afterAll() = {
    if (makeTestSubject) {
      disposeAdditionalTestSubject(vanityNamespace)
    }
  }

  "test subdomain" should "have conforming parts" in {
    vanitySubdomain should fullyMatch regex subdomainRegex.r
    vanitySubdomain.length should be <= 63
  }

  "vanity subdomain" should "access a web action via namespace subdomain" in withAssetCleaner(wskPropsForSubdomainTest) {
    (wp, assetHelper) =>
      val actionName = "webaction"

      val file = Some(TestUtils.getTestActionFilename("echo.js"))
      assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
        action.create(actionName, file, web = Some(true.toString))(wp)
      }

      val url = getServiceApiHost(vanitySubdomain, true) + s"/default/$actionName.json/a?a=A"
      println(s"url: $url")

      // try the rest assured path first, failing that, try curl with explicit resolve
      Try {
        val response = RestAssured.given().config(sslconfig).get(url)
        val responseCode = response.statusCode
        responseCode shouldBe 200
        response.body.asString.parseJson.asJsObject.fields("a").convertTo[String] shouldBe "A"
      } match {
        case Failure(t) =>
          println(s"RestAssured path failed, trying curl: $t")
          implicit val tid = TransactionId.testing
          implicit val logger = new PrintStreamLogging(Console.out)
          val host = getServiceApiHost(vanitySubdomain, false)
          // if the edge host is a name, try to resolve it, otherwise, it should be an ip address already
          val edgehost = WhiskProperties.getEdgeHost
          val ip = Try(java.net.InetAddress.getByName(edgehost).getHostAddress) getOrElse "???"
          println(s"edge: $edgehost, ip: $ip")
          val cmd = Seq("curl", "-k", url, "--resolve", s"$host:$ip")
          val (stdout, stderr, exitCode) = SimpleExec.syncRunCmd(cmd)
          withClue(s"\n$stderr\n") {
            stdout.parseJson.asJsObject.fields("a").convertTo[String] shouldBe "A"
            exitCode shouldBe 0
          }

        case _ =>
      }
  }
}
