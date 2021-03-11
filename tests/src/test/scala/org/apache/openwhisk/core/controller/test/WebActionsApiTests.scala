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

package org.apache.openwhisk.core.controller.test

import java.time.Instant
import java.util.Base64

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.HttpCharsets
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.headers.{`Access-Control-Request-Headers`, `Content-Type`, RawHeader}
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.MediaType
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.controller._
import org.apache.openwhisk.core.entitlement.EntitlementProvider
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entitlement.Resource
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.loadBalancer.LoadBalancer
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.Messages

import scala.collection.immutable.Set

/**
 * Tests web actions API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */

@RunWith(classOf[JUnitRunner])
class WebActionsApiCommonTests extends FlatSpec with Matchers {
  "extension splitter" should "split action name and extension" in {
    Seq(".http", ".json", ".text", ".html", ".svg").foreach { ext =>
      Seq(s"t$ext", s"tt$ext", s"t.wxyz$ext", s"tt.wxyz$ext").foreach { s =>
        Seq(true, false).foreach { enforce =>
          val (n, e) = WhiskWebActionsApi.mediaTranscoderForName(s, enforce)
          val i = s.lastIndexOf(".")
          n shouldBe s.substring(0, i)
          e.get.extension shouldBe ext
        }
      }
    }

    Seq(s"t", "tt", "abcde", "abcdef", "t.wxyz").foreach { s =>
      val (n, e) = WhiskWebActionsApi.mediaTranscoderForName(s, false)
      n shouldBe s
      e.get.extension shouldBe ".http"
    }

    Seq(s"t", "tt", "abcde", "abcdef", "t.wxyz").foreach { s =>
      val (n, e) = WhiskWebActionsApi.mediaTranscoderForName(s, true)
      n shouldBe s
      e shouldBe empty
    }
  }
}

@RunWith(classOf[JUnitRunner])
class WebActionsApiTests extends FlatSpec with Matchers with WebActionsApiBaseTests {
  override lazy val webInvokePathSegments = Seq("web")
  override lazy val webApiDirectives = new WebApiDirectives()

  "properties" should "match verion" in {
    webApiDirectives.method shouldBe "__ow_method"
    webApiDirectives.headers shouldBe "__ow_headers"
    webApiDirectives.path shouldBe "__ow_path"
    webApiDirectives.namespace shouldBe "__ow_user"
    webApiDirectives.query shouldBe "__ow_query"
    webApiDirectives.body shouldBe "__ow_body"
    webApiDirectives.statusCode shouldBe "statusCode"
    webApiDirectives.enforceExtension shouldBe false
    webApiDirectives.reservedProperties shouldBe {
      Set("__ow_method", "__ow_headers", "__ow_path", "__ow_user", "__ow_query", "__ow_body")
    }
  }
}

trait WebActionsApiBaseTests extends ControllerTestCommon with BeforeAndAfterEach with WhiskWebActionsApi {
  val uuid = UUID()
  val systemId = Subject()
  val systemKey = BasicAuthenticationAuthKey(uuid, Secret())
  val systemIdentity =
    Future.successful(
      Identity(systemId, Namespace(EntityName(systemId.asString), uuid), systemKey, rights = Privilege.ALL))
  val namespace = EntityPath(systemId.asString)
  val proxyNamespace = namespace.addPath(EntityName("proxy"))
  override lazy val entitlementProvider = new TestingEntitlementProvider(whiskConfig, loadBalancer)
  protected val testRoutePath = webInvokePathSegments.mkString("/", "/", "")
  def aname() = MakeName.next("web_action_tests")

  behavior of "Web actions API"

  var failActivation = 0 // toggle to cause action to fail
  var failThrottleForSubject: Option[Subject] = None // toggle to cause throttle to fail for subject
  var failCheckEntitlement = false // toggle to cause entitlement to fail
  var actionResult: Option[JsObject] = None
  var testParametersInInvokeAction = true // toggle to test parameter in invokeAction
  var requireAuthenticationKey = "example-web-action-api-key"
  var invocationCount = 0
  var invocationsAllowed = 0
  lazy val testFixturesToGc = {
    implicit val tid = transid()
    Seq(
      stubPackage,
      stubAction(namespace, EntityName("export_c")),
      stubAction(proxyNamespace, EntityName("export_c")),
      stubAction(proxyNamespace, EntityName("raw_export_c"))).map { f =>
      put(entityStore, f, garbageCollect = false)
    }
  }

  override def beforeAll() = {
    testFixturesToGc.foreach(f => ())
  }

  override def beforeEach() = {
    invocationCount = 0
    invocationsAllowed = 0
  }

  override def afterEach() = {
    failActivation = 0
    failThrottleForSubject = None
    failCheckEntitlement = false
    actionResult = None
    testParametersInInvokeAction = true
    assert(invocationsAllowed == invocationCount, "allowed invoke count did not match actual")
    cleanup()
  }

  override def afterAll() = {
    implicit val tid = transid()
    testFixturesToGc.foreach(delete(entityStore, _))
  }

  val allowedMethodsWithEntity = {
    val nonModifierMethods = Seq(Get, Options)
    val modifierMethods = Seq(Post, Put, Delete, Patch)
    modifierMethods ++ nonModifierMethods
  }

  val allowedMethods = {
    allowedMethodsWithEntity ++ Seq(Head)
  }

  val pngSample = "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAGCAYAAAD68A/GAAAA/klEQVQYGWNgAAEHBxaG//+ZQMyyn581Pfas+cRQnf1LfF" +
    "Ljf+62smUgcUbt0FA2Zh7drf/ffMy9vLn3RurrW9e5hCU11i2azfD4zu1/DHz8TAy/foUxsXBrFzHzC7r8+M9S1vn1qxQT07dDjL" +
    "9fdemrqKxlYGT6z8AIMo6hgeUfA0PUvy9fGFh5GWK3z7vNxSWt++jX99+8SoyiGQwsW38w8PJEM7x5v5SJ8f+/xv8MDAzffv9hev" +
    "fkWjiXBGMpMx+j2awovjcMjFztDO8+7GF49LkbZDCDeXLTWnZO7qDfn1/+5jbw/8pjYWS4wZLztXnuEuYTk2M+MzIw/AcA36Vewa" +
    "D6fzsAAAAASUVORK5CYII="

  // there is only one package that is predefined 'proxy'
  val stubPackage = WhiskPackage(
    EntityPath(systemId.asString),
    EntityName("proxy"),
    parameters = Parameters("x", JsString("X")) ++ Parameters("z", JsString("z")))

  val packages = Seq(stubPackage)

  val defaultActionParameters = {
    Parameters("y", JsString("Y")) ++ Parameters("z", JsString("Z")) ++ Parameters("empty", JsNull)
  }

  // action names that start with 'export_' will automatically have an web-export annotation added by the test harness
  protected def stubAction(namespace: EntityPath,
                           name: EntityName,
                           customOptions: Boolean = true,
                           requireAuthentication: Boolean = false,
                           requireAuthenticationAsBoolean: Boolean = true) = {

    val annotations = Parameters(Annotations.FinalParamsAnnotationName, JsTrue)
    WhiskAction(
      namespace,
      name,
      jsDefault("??"),
      defaultActionParameters,
      annotations = {
        if (name.asString.startsWith("export_")) {
          annotations ++
            Parameters("web-export", JsTrue) ++ {
            if (requireAuthentication) {
              Parameters(
                "require-whisk-auth",
                (if (requireAuthenticationAsBoolean) JsTrue else JsString(requireAuthenticationKey)))
            } else Parameters()
          } ++ {
            if (customOptions) {
              Parameters("web-custom-options", JsTrue)
            } else Parameters()
          }
        } else if (name.asString.startsWith("raw_export_")) {
          annotations ++
            Parameters("web-export", JsTrue) ++
            Parameters("raw-http", JsTrue) ++ {
            if (requireAuthentication) {
              Parameters(
                "require-whisk-auth",
                (if (requireAuthenticationAsBoolean) JsTrue else JsString(requireAuthenticationKey)))
            } else Parameters()
          } ++ {
            if (customOptions) {
              Parameters("web-custom-options", JsTrue)
            } else Parameters()
          }
        } else annotations
      })
  }

  // there is only one identity defined for the fully qualified name of the web action: 'systemId'
  override protected def getIdentity(namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
    if (namespace.asString == systemId.asString) {
      systemIdentity
    } else {
      logging.info(this, s"namespace has no identity")
      Future.failed(RejectRequest(BadRequest))
    }
  }

  override protected[controller] def invokeAction(
    user: Identity,
    action: WhiskActionMetaData,
    payload: Option[JsObject],
    waitForResponse: Option[FiniteDuration],
    cause: Option[ActivationId])(implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {
    invocationCount = invocationCount + 1

    if (failActivation == 0) {
      // construct a result stub that includes:
      // 1. the package name for the action (to confirm that this resolved to systemId)
      // 2. the action name (to confirm that this resolved to the expected action)
      // 3. the payload received by the action which consists of the action.params + payload
      val result = actionResult getOrElse JsObject(
        "pkg" -> action.namespace.toJson,
        "action" -> action.name.toJson,
        "content" -> action.parameters.merge(payload).get)

      val activation = WhiskActivation(
        action.namespace,
        action.name,
        user.subject,
        ActivationId.generate(),
        start = Instant.now,
        end = Instant.now,
        response = {
          actionResult.flatMap { r =>
            r.fields.get("application_error").map { e =>
              ActivationResponse.applicationError(e)
            } orElse r.fields.get("developer_error").map { e =>
              ActivationResponse.developerError(e, None)
            } orElse r.fields.get("whisk_error").map { e =>
              ActivationResponse.whiskError(e)
            } orElse None // for clarity
          } getOrElse ActivationResponse.success(Some(result))
        })

      // check that action parameters were merged with package
      // all actions have default parameters (see stubAction)
      if (testParametersInInvokeAction) {
        if (!action.namespace.defaultPackage) {
          action.parameters shouldBe (stubPackage.parameters ++ defaultActionParameters)
        } else {
          action.parameters shouldBe defaultActionParameters
        }
        action.parameters.get("z") shouldBe defaultActionParameters.get("z")
      }

      Future.successful(Right(activation))
    } else if (failActivation == 1) {
      Future.successful(Left(ActivationId.generate()))
    } else {
      Future.failed(new IllegalStateException("bad activation"))
    }
  }

  def metaPayload(method: String,
                  params: JsObject,
                  identity: Option[Identity],
                  path: String = "",
                  body: Option[JsObject] = None,
                  pkgName: String = null,
                  headers: List[HttpHeader] = List.empty) = {
    val packageActionParams = Option(pkgName)
      .filter(_ != null)
      .flatMap(n => packages.find(_.name == EntityName(n)))
      .map(_.parameters)
      .getOrElse(Parameters())

    (packageActionParams ++ defaultActionParameters).merge {
      Some {
        JsObject(
          params.fields ++
            body.map(_.fields).getOrElse(Map.empty) ++
            Context(webApiDirectives, HttpMethods.getForKey(method.toUpperCase).get, headers, path, Query.Empty)
              .metadata(identity))
      }
    }.get
  }

  def confirmErrorWithTid(error: JsObject, message: Option[String] = None) = {
    error.fields.size shouldBe 2
    error.fields.get("error") shouldBe defined
    message.foreach { m =>
      error.fields.get("error").get shouldBe JsString(m)
    }
    error.fields.get("code") shouldBe defined
    error.fields.get("code").get shouldBe an[JsString]
  }

  Seq(None, Some(WhiskAuthHelpers.newIdentity())).foreach { creds =>
    it should s"not match invalid routes (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // none of these should match a route
      Seq("a", "a/b", "/a", s"$systemId/c", s"$systemId/export_c").foreach { path =>
        allowedMethods.foreach { m =>
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(NotFound)
          }
        }
      }
    }

    it should s"reject requests when Identity, package or action lookup fail or missing annotation (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      put(entityStore, stubAction(namespace, EntityName("c")))

      // the first of these fails in the identity lookup,
      // the second in the package lookup (does not exist),
      // the third fails the annotation check (no web-export annotation because name doesn't start with export_c)
      // the fourth fails the action lookup
      Seq("guest/proxy/c", s"$systemId/doesnotexist/c", s"$systemId/default/c", s"$systemId/proxy/export_fail")
        .foreach { path =>
          allowedMethods.foreach { m =>
            m(s"$testRoutePath/${path}.json") ~> Route.seal(routes(creds)) ~> check {
              status should be(NotFound)
            }

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              if (webApiDirectives.enforceExtension) {
                status should be(NotAcceptable)
                confirmErrorWithTid(
                  responseAs[JsObject],
                  Some(Messages.contentTypeExtensionNotSupported(WhiskWebActionsApi.allowedExtensions)))
              } else {
                status should be(NotFound)
              }
            }
          }
        }
    }

    it should s"reject requests when whisk authentication is required but none given (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val entityName = MakeName.next("export")
      val action =
        stubAction(
          proxyNamespace,
          entityName,
          customOptions = false,
          requireAuthentication = true,
          requireAuthenticationAsBoolean = true)
      val path = action.fullyQualifiedName(false)
      put(entityStore, action)

      allowedMethods.foreach { m =>
        m(s"$testRoutePath/${path}.json") ~> Route.seal(routes(creds)) ~> check {
          if (m === Options) {
            status should be(OK) // options response is always present regardless of auth
            header("Access-Control-Allow-Origin").get.toString shouldBe "Access-Control-Allow-Origin: *"
            header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
            header("Access-Control-Request-Headers") shouldBe empty
          } else if (creds.isEmpty) {
            status should be(Unauthorized) // if user is not authenticated, reject all requests
          } else {
            invocationsAllowed += 1
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> entityName.asString.toJson,
              "content" -> metaPayload(m.method.name.toLowerCase, JsObject.empty, creds, pkgName = "proxy"))
            response
              .fields("content")
              .asJsObject
              .fields(webApiDirectives.namespace) shouldBe creds.get.namespace.name.toJson
          }
        }
      }
    }

    it should s"reject requests when x-authentication is required but none given (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val entityName = MakeName.next("export")
      val action =
        stubAction(
          proxyNamespace,
          entityName,
          customOptions = false,
          requireAuthentication = true,
          requireAuthenticationAsBoolean = false)
      val path = action.fullyQualifiedName(false)
      put(entityStore, action)

      allowedMethods.foreach { m =>
        // web action require-whisk-auth is set, but the header X-Require-Whisk-Auth value does not match
        m(s"$testRoutePath/${path}.json") ~> addHeader(
          WhiskAction.requireWhiskAuthHeader,
          requireAuthenticationKey + "-bad") ~> Route
          .seal(routes(creds)) ~> check {
          if (m == Options) {
            status should be(OK) // options should always respond
            header("Access-Control-Allow-Origin").get.toString shouldBe "Access-Control-Allow-Origin: *"
            header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
            header("Access-Control-Request-Headers") shouldBe empty
          } else {
            status should be(Unauthorized)
          }
        }

        // web action require-whisk-auth is set, but the header X-Require-Whisk-Auth value is not set
        m(s"$testRoutePath/${path}.json") ~> Route.seal(routes(creds)) ~> check {
          if (m == Options) {
            status should be(OK) // options should always respond
            header("Access-Control-Allow-Origin").get.toString shouldBe "Access-Control-Allow-Origin: *"
            header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
            header("Access-Control-Request-Headers") shouldBe empty
          } else {
            status should be(Unauthorized)
          }
        }

        m(s"$testRoutePath/${path}.json") ~> addHeader(WhiskAction.requireWhiskAuthHeader, requireAuthenticationKey) ~> Route
          .seal(routes(creds)) ~> check {
          if (m == Options) {
            status should be(OK) // options should always respond
            header("Access-Control-Allow-Origin").get.toString shouldBe "Access-Control-Allow-Origin: *"
            header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
            header("Access-Control-Request-Headers") shouldBe empty
          } else {
            invocationsAllowed += 1
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> entityName.asString.toJson,
              "content" -> metaPayload(
                m.method.name.toLowerCase,
                JsObject.empty,
                creds,
                pkgName = "proxy",
                headers = List(RawHeader(WhiskAction.requireWhiskAuthHeader, requireAuthenticationKey))))
            if (creds.isDefined) {
              response
                .fields("content")
                .asJsObject
                .fields(webApiDirectives.namespace) shouldBe creds.get.namespace.name.toJson
            }
          }
        }
      }
    }

    it should s"invoke action that times out and provide a code (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      failActivation = 1

      allowedMethods.foreach { m =>
        invocationsAllowed += 1

        m(s"$testRoutePath/$systemId/proxy/export_c.json") ~> Route.seal(routes(creds)) ~> check {
          status should be(Accepted)
          val response = responseAs[JsObject]
          confirmErrorWithTid(response, Some("Response not yet ready."))
        }
      }
    }

    it should s"invoke action that errors and respond with error and code (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      failActivation = 2

      allowedMethods.foreach { m =>
        invocationsAllowed += 1

        m(s"$testRoutePath/$systemId/proxy/export_c.json") ~> Route.seal(routes(creds)) ~> check {
          status should be(InternalServerError)
          val response = responseAs[JsObject]
          confirmErrorWithTid(response)
        }
      }
    }

    it should s"invoke action and merge query parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json?a=b&c=d").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(
                m.method.name.toLowerCase,
                Map("a" -> "b", "c" -> "d").toJson.asJsObject,
                creds,
                pkgName = "proxy"))
          }
        }
      }
    }

    it should s"invoke action and merge body parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // both of these should produce full result objects (trailing slash is ok)
      Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/").foreach { path =>
        allowedMethodsWithEntity.foreach { m =>
          val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
          val p = if (path.endsWith("/")) "/" else ""
          invocationsAllowed += 1
          m(s"$testRoutePath/$path", content) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(
                m.method.name.toLowerCase,
                JsObject.empty,
                creds,
                body = Some(content),
                path = p,
                pkgName = "proxy",
                headers = List(`Content-Type`(ContentTypes.`application/json`))))
          }
        }
      }
    }

    it should s"invoke action which receives an empty entity (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq("", JsArray.empty.compactPrint, JsObject.empty.compactPrint, JsNull.compactPrint).foreach { arg =>
        Seq(s"$systemId/proxy/export_c.json").foreach { path =>
          allowedMethodsWithEntity.foreach { m =>
            invocationsAllowed += 1
            m(s"$testRoutePath/$path", HttpEntity(ContentTypes.`application/json`, arg)) ~> Route.seal(routes(creds)) ~> check {
              status should be(OK)
              val response = responseAs[JsObject]
              response shouldBe JsObject(
                "pkg" -> s"$systemId/proxy".toJson,
                "action" -> "export_c".toJson,
                "content" -> metaPayload(
                  m.method.name.toLowerCase,
                  if (arg.nonEmpty && arg != "{}") JsObject(webApiDirectives.body -> arg.parseJson) else JsObject.empty,
                  creds,
                  pkgName = "proxy",
                  headers = List(`Content-Type`(ContentTypes.`application/json`))))
            }
          }
        }
      }
    }

    it should s"invoke action and merge query and body parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json?a=b&c=d").foreach { path =>
        allowedMethodsWithEntity.foreach { m =>
          val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
          invocationsAllowed += 1

          m(s"$testRoutePath/$path", content) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(
                m.method.name.toLowerCase,
                Map("a" -> "b", "c" -> "d").toJson.asJsObject,
                creds,
                body = Some(content),
                pkgName = "proxy",
                headers = List(`Content-Type`(ContentTypes.`application/json`))))
          }
        }
      }
    }

    it should s"invoke action in default package (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/default/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(m.method.name.toLowerCase, JsObject.empty, creds))
          }
        }
      }
    }

    it should s"invoke action in a binding of private package (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath(systemId.asString), aname(), None, stubPackage.parameters)
      val reference = WhiskPackage(EntityPath(systemId.asString), aname(), provider.bind)
      val action = stubAction(provider.fullPath, EntityName("export_c"))

      put(entityStore, provider)
      put(entityStore, reference)
      put(entityStore, action)

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
          }
        }
      }
    }

    it should s"invoke action in a binding of public package (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath("guest"), aname(), None, stubPackage.parameters, publish = true)
      val reference = WhiskPackage(EntityPath(systemId.asString), aname(), provider.bind)
      val action = stubAction(provider.fullPath, EntityName("export_c"))

      put(entityStore, provider)
      put(entityStore, reference)
      put(entityStore, action)

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
          }
        }
      }
    }

    it should s"invoke action relative to a binding where the action doesn't exist (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath("guest"), aname(), None, stubPackage.parameters, publish = true)
      val reference = WhiskPackage(EntityPath(systemId.asString), aname(), provider.bind)

      put(entityStore, provider)
      put(entityStore, reference)
      // action is not created

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(NotFound)
          }
        }
      }
    }

    it should s"invoke action in non-existing binding (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath("guest"), aname(), None, stubPackage.parameters, publish = true)
      val action = stubAction(provider.fullPath, EntityName("export_c"))
      val reference = WhiskPackage(EntityPath(systemId.asString), aname(), provider.bind)

      put(entityStore, provider)
      put(entityStore, action)
      // reference is not created

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(NotFound)
          }
        }
      }
    }

    it should s"not inherit annotations of package binding (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath("guest"), aname(), None, stubPackage.parameters, publish = true)
      val reference = WhiskPackage(
        EntityPath(systemId.asString),
        aname(),
        provider.bind,
        annotations = Parameters("web-export", JsFalse))
      val action = stubAction(provider.fullPath, EntityName("export_c"))

      put(entityStore, provider)
      put(entityStore, reference)
      put(entityStore, action)

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
          }
        }
      }
    }

    it should s"reject request that tries to override final parameters of action in package binding (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val provider = WhiskPackage(EntityPath("guest"), aname(), None, publish = true)
      val reference = WhiskPackage(EntityPath(systemId.asString), aname(), provider.bind, stubPackage.parameters)
      val action = stubAction(provider.fullPath, EntityName("export_c"))

      put(entityStore, provider)
      put(entityStore, reference)
      put(entityStore, action)

      val contentX = JsObject("x" -> "overridden".toJson)
      val contentZ = JsObject("z" -> "overridden".toJson)

      allowedMethodsWithEntity.foreach { m =>
        invocationsAllowed += 1

        m(s"$testRoutePath/$systemId/${reference.name}/export_c.json?x=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/${reference.name}/export_c.json?y=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/${reference.name}/export_c.json", contentX) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/${reference.name}/export_c.json?y=overridden", contentZ) ~> Route.seal(
          routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/${reference.name}/export_c.json?empty=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[JsObject]
          response shouldBe JsObject(
            "pkg" -> s"guest/${provider.name}".toJson,
            "action" -> "export_c".toJson,
            "content" -> metaPayload(
              m.method.name.toLowerCase,
              Map("empty" -> "overridden").toJson.asJsObject,
              creds,
              pkgName = "proxy"))
        }
      }
    }

    it should s"match precedence order for merging parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      testParametersInInvokeAction = false

      val provider = WhiskPackage(
        EntityPath("guest"),
        aname(),
        None,
        Parameters("a", JsString("A")) ++ Parameters("b", JsString("b")),
        publish = true)
      val reference = WhiskPackage(
        EntityPath(systemId.asString),
        aname(),
        provider.bind,
        Parameters("a", JsString("a")) ++ Parameters("c", JsString("c")))

      // stub action has defaultActionParameters
      val action = stubAction(provider.fullPath, EntityName("export_c"))

      put(entityStore, provider)
      put(entityStore, reference)
      put(entityStore, action)

      Seq(s"$systemId/${reference.name}/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]

            response shouldBe JsObject(
              "pkg" -> s"guest/${provider.name}".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(
                m.method.name.toLowerCase,
                Map("a" -> "a", "b" -> "b", "c" -> "c").toJson.asJsObject,
                creds))
          }
        }
      }
    }

    it should s"pass the unmatched segment to the action (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json/content").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject].fields("content")
            response shouldBe metaPayload(
              m.method.name.toLowerCase,
              JsObject.empty,
              creds,
              path = "/content",
              pkgName = "proxy")
          }
        }
      }
    }

    it should s"respond with error when expected text property does not exist (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.text").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(NotFound)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.propertyNotFound))
            // ensure that error message is pretty printed as { error, code }
            responseAs[String].linesIterator should have size 4
          }
        }
      }
    }

    it should s"use action status code and headers to terminate an http response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("location" -> "http://openwhisk.org".toJson),
              webApiDirectives.statusCode -> Found.intValue.toJson))
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(Found)
            header("location").get.toString shouldBe "location: http://openwhisk.org"
          }
        }
      }
    }

    it should s"use default field projection for extension (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("location" -> "http://openwhisk.org".toJson),
              webApiDirectives.statusCode -> Found.intValue.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(Found)
            header("location").get.toString shouldBe "location: http://openwhisk.org"
          }
        }
      }

      Seq(s"$systemId/proxy/export_c.text").foreach { path =>
        allowedMethods.foreach { m =>
          val text = "default text"
          invocationsAllowed += 1
          actionResult = Some(JsObject("text" -> JsString(text)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            contentType shouldBe MediaTypes.`text/plain`.withCharset(HttpCharsets.`UTF-8`)
            val response = responseAs[String]
            response shouldBe text
          }
        }
      }

      Seq(s"$systemId/proxy/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject("foobar" -> JsString("foobar")))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe actionResult.get

            // ensure response is pretty printed
            responseAs[String] shouldBe {
              """{
                |  "foobar": "foobar"
                |}""".stripMargin
            }
          }
        }
      }

      Seq(s"$systemId/proxy/export_c.html").foreach { path =>
        allowedMethods.foreach { m =>
          val html = "<html>hi</htlml>"
          invocationsAllowed += 1
          actionResult = Some(JsObject("html" -> JsString(html)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            contentType shouldBe MediaTypes.`text/html`.withCharset(HttpCharsets.`UTF-8`)
            val response = responseAs[String]
            response shouldBe html
          }
        }
      }

      Seq(s"$systemId/proxy/export_c.svg").foreach { path =>
        allowedMethods.foreach { m =>
          val svg = """<svg><circle cx="3" cy="3" r="3" fill="blue"/></svg>"""
          invocationsAllowed += 1
          actionResult = Some(JsObject("svg" -> JsString(svg)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            //contentType shouldBe MediaTypes.`image/svg+xml`.withCharset(HttpCharsets.`UTF-8`)
            val response = responseAs[String]
            response shouldBe svg
          }
        }
      }
    }

    it should s"handle http web action and provide defaults (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      def confirmEmptyResponse() = {
        status should be(NoContent)
        response.entity shouldBe HttpEntity.Empty
        withClue(headers) {
          headers.length shouldBe 1
          headers.exists(_.is(ActivationIdHeader)) should be(true)
        }
      }

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        Set(JsObject.empty, JsObject("body" -> "".toJson), JsObject("body" -> JsNull)).foreach { bodyResult =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 2
            actionResult = Some(bodyResult)

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              withClue(s"failed for: $bodyResult") {
                confirmEmptyResponse()
              }
            }

            // repeat with accept header, which should be ignored for content-negotiation
            m(s"$testRoutePath/$path") ~> addHeader("Accept", "application/json") ~> Route.seal(routes(creds)) ~> check {
              withClue(s"with accept header, failed for: $bodyResult") {
                confirmEmptyResponse()
              }
            }
          }
        }
      }
    }

    it should s"handle all JSON values with .text extension (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(JsObject("a" -> "A".toJson), JsArray("a".toJson), JsString("a"), JsTrue, JsNumber(1), JsNull)
        .foreach { jsval =>
          val path = s"$systemId/proxy/export_c.text"
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(JsObject("body" -> jsval))

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              responseAs[String] shouldBe {
                jsval match {
                  case _: JsObject  => jsval.prettyPrint
                  case _: JsArray   => jsval.prettyPrint
                  case JsString(s)  => s
                  case JsBoolean(b) => b.toString
                  case JsNumber(n)  => n.toString
                  case _            => "null"
                }
              }
            }
          }
        }
    }

    it should s"handle http web action with JSON object as string response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        Seq(OK, Created).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(
              JsObject(
                "headers" -> JsObject("content-type" -> "application/json".toJson),
                webApiDirectives.statusCode -> statusCode.intValue.toJson,
                "body" -> JsObject("field" -> "value".toJson).compactPrint.toJson))

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
              mediaType shouldBe MediaTypes.`application/json`
              responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
            }
          }
        }
      }
    }

    it should s"handle http web action with partially specified result (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        // omit status code
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("content-type" -> "application/json".toJson),
              "body" -> JsObject("field" -> "value".toJson)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
          }
        }

        // omit status code and headers
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject("body" -> JsObject("field" -> "value".toJson).compactPrint.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[String] shouldBe actionResult.get.fields("body").convertTo[String]
            contentType shouldBe MediaTypes.`text/html`.withCharset(HttpCharsets.`UTF-8`)
          }
        }

        // omit headers only
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              webApiDirectives.statusCode -> Created.intValue.toJson,
              "body" -> JsObject("field" -> "value".toJson).compactPrint.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(Created)
            responseAs[String] shouldBe actionResult.get.fields("body").convertTo[String]
            contentType shouldBe MediaTypes.`text/html`.withCharset(HttpCharsets.`UTF-8`)
          }
        }

        // omit body and headers
        Seq(OK, Created, NoContent).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(JsObject(webApiDirectives.statusCode -> statusCode.intValue.toJson))

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
              headers.size shouldBe 1
              headers.exists(_.is(ActivationIdHeader)) should be(true)
              response.entity shouldBe HttpEntity.Empty
            }
          }
        }

        // omit body but include headers
        Seq(OK, Created, NoContent).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(
              JsObject(
                "headers" -> JsObject("Set-Cookie" -> "a=b".toJson, "content-type" -> "application/json".toJson),
                webApiDirectives.statusCode -> statusCode.intValue.toJson))

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
              headers should contain(RawHeader("Set-Cookie", "a=b"))
              headers.exists(_.is(ActivationIdHeader)) should be(true)
              response.entity shouldBe HttpEntity.Empty
            }
          }
        }
      }
    }

    it should s"handle http web action with no body when status code is set (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        // omit body and headers, but add accept header on the request
        Seq(OK, Created, NoContent).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(JsObject(webApiDirectives.statusCode -> statusCode.intValue.toJson))

            m(s"$testRoutePath/$path") ~> addHeader("Accept", "application/json") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
              headers.size shouldBe 1
              headers.exists(_.is(ActivationIdHeader)) should be(true)
              response.entity shouldBe HttpEntity.Empty
            }
          }
        }

        // omit body but include headers, and add accept header on the request
        Seq(OK, Created, NoContent).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(
              JsObject(
                "headers" -> JsObject("Set-Cookie" -> "a=b".toJson, "content-type" -> "application/json".toJson),
                webApiDirectives.statusCode -> statusCode.intValue.toJson))

            m(s"$testRoutePath/$path") ~> addHeader("Accept", "application/json") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
              headers should contain(RawHeader("Set-Cookie", "a=b"))
              headers.exists(_.is(ActivationIdHeader)) should be(true)
              response.entity shouldBe HttpEntity.Empty
            }
          }
        }
      }
    }

    it should s"handle http web action with JSON object response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(
        (JsObject("content-type" -> "application/json".toJson), OK),
        (JsObject.empty, OK),
        (JsObject("content-type" -> "text/html".toJson), BadRequest)).foreach {
        case (headers, expectedCode) =>
          Seq(s"$systemId/proxy/export_c.http").foreach { path =>
            allowedMethods.foreach { m =>
              invocationsAllowed += 1
              actionResult = Some(
                JsObject(
                  "headers" -> headers,
                  webApiDirectives.statusCode -> OK.intValue.toJson,
                  "body" -> JsObject("field" -> "value".toJson)))

              m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
                status should be(expectedCode)

                if (expectedCode == OK) {
                  header("content-type").map(_.toString shouldBe "content-type: application/json")
                  responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
                } else {
                  confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
                }
              }
            }
          }
      }
    }
    it should s"handle http web action with base64 encoded known '+json' response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("content-type" -> "application/json-patch+json".toJson),
              webApiDirectives.statusCode -> OK.intValue.toJson,
              "body" -> Base64.getEncoder.encodeToString {
                JsObject("field" -> "value".toJson).compactPrint.getBytes
              }.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            mediaType.value shouldBe "application/json-patch+json"
            responseAs[String].parseJson shouldBe JsObject("field" -> "value".toJson)
          }
        }
      }
    }

    it should s"handle http web action for known '+json' response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(
        (JsObject("content-type" -> "application/json-patch+json".toJson), OK),
        (JsObject("content-type" -> "text/html".toJson), BadRequest)).foreach {
        case (headers, expectedCode) =>
          Seq(s"$systemId/proxy/export_c.http").foreach { path =>
            allowedMethods.foreach { m =>
              invocationsAllowed += 1
              actionResult = Some(
                JsObject(
                  "headers" -> headers,
                  webApiDirectives.statusCode -> OK.intValue.toJson,
                  "body" -> JsObject("field" -> "value".toJson)))

              m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
                status should be(expectedCode)

                if (expectedCode == OK) {
                  mediaType.value shouldBe "application/json-patch+json"
                  responseAs[String].parseJson shouldBe JsObject("field" -> "value".toJson)
                } else {
                  confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
                }
              }
            }
          }
      }
    }

    it should s"handle http web action for unknown '+json' response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(
        (JsObject("content-type" -> "application/hal+json".toJson), OK),
        (JsObject("content-type" -> "text/html".toJson), BadRequest)).foreach {
        case (headers, expectedCode) =>
          Seq(s"$systemId/proxy/export_c.http").foreach { path =>
            allowedMethods.foreach { m =>
              invocationsAllowed += 1
              actionResult = Some(
                JsObject(
                  "headers" -> headers,
                  webApiDirectives.statusCode -> OK.intValue.toJson,
                  "body" -> JsObject("field" -> "value".toJson)))

              m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
                status should be(expectedCode)

                if (expectedCode == OK) {
                  mediaType.value shouldBe "application/hal+json"
                  responseAs[String].parseJson shouldBe JsObject("field" -> "value".toJson)
                } else {
                  confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
                }
              }
            }
          }
      }

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject(webApiDirectives.statusCode -> OK.intValue.toJson, "body" -> JsNumber(3)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            header("content-type").map(_.toString shouldBe "content-type: application/json")
            responseAs[String].toInt shouldBe 3
          }
        }
      }
    }

    it should s"handle http web action with base64 encoded binary response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      val expectedEntity = HttpEntity(ContentType(MediaTypes.`image/png`), Base64.getDecoder().decode(pngSample))

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject(`Content-Type`.lowercaseName -> MediaTypes.`image/png`.toString.toJson),
              "body" -> pngSample.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            response.entity shouldBe expectedEntity
          }
        }
      }
    }

    it should s"handle http web action with html/text response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult =
            Some(JsObject(webApiDirectives.statusCode -> OK.intValue.toJson, "body" -> "hello world".toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[String] shouldBe "hello world"
          }
        }
      }
    }

    it should s"allow web action with incorrect application/json header and text response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("content-type" -> "application/json".toJson),
              webApiDirectives.statusCode -> OK.intValue.toJson,
              "body" -> "hello world".toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            mediaType shouldBe MediaTypes.`application/json`
            headers.size shouldBe 1
            headers.exists(_.is(ActivationIdHeader)) should be(true)
            responseAs[String] shouldBe "hello world"
          }
        }
      }
    }

    it should s"reject http web action with invalid content-type header (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("content-type" -> "xyzbar".toJson),
              webApiDirectives.statusCode -> OK.intValue.toJson,
              "body" -> "hello world".toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(BadRequest)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpUnknownContentType))
          }
        }
      }
    }

    it should s"handle an activation that results in application error (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "application_error" -> JsObject(
                webApiDirectives.statusCode -> OK.intValue.toJson,
                "body" -> "no hello for you".toJson)))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[String] shouldBe "no hello for you"
          }
        }
      }
    }

    it should s"handle an activation that results in application error that does not match .json extension (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject("application_error" -> "bad response type".toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(BadRequest)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.invalidMedia(MediaTypes.`application/json`)))
          }
        }
      }
    }

    it should s"handle an activation that results in developer or system error (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.text")
        .foreach { path =>
          Seq("developer_error", "whisk_error").foreach { e =>
            allowedMethods.foreach { m =>
              invocationsAllowed += 1
              actionResult = Some(JsObject(e -> "bad response type".toJson))

              m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
                status should be(BadRequest)
                if (e == "application_error") {
                  confirmErrorWithTid(responseAs[JsObject], Some(Messages.invalidMedia(MediaTypes.`application/json`)))
                } else {
                  confirmErrorWithTid(responseAs[JsObject], Some(Messages.errorProcessingRequest))
                }
              }
            }
          }
        }
    }

    it should s"support formdata (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json").foreach { path =>
        val form = FormData(Map("field1" -> "value1", "field2" -> "value2"))
        invocationsAllowed += 1

        Post(s"$testRoutePath/$path", form.toEntity) ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          responseAs[JsObject].fields("content").asJsObject.fields("field1") shouldBe JsString("value1")
          responseAs[JsObject].fields("content").asJsObject.fields("field2") shouldBe JsString("value2")
        }
      }
    }

    it should s"reject requests when entity size exceeds allowed limit (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json").foreach { path =>
        val largeEntity = "a" * (allowedActivationEntitySize.toInt + 1)

        val content = s"""{"a":"$largeEntity"}"""
        Post(s"$testRoutePath/$path", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
          status should be(PayloadTooLarge)
          val expectedErrorMsg = Messages.entityTooBig(
            SizeError(fieldDescriptionForSizeError, (largeEntity.length + 8).B, allowedActivationEntitySize.B))
          confirmErrorWithTid(responseAs[JsObject], Some(expectedErrorMsg))
        }

        val form = FormData(Map("a" -> largeEntity))
        Post(s"$testRoutePath/$path", form) ~> Route.seal(routes(creds)) ~> check {
          status should be(PayloadTooLarge)
          val expectedErrorMsg = Messages.entityTooBig(
            SizeError(fieldDescriptionForSizeError, (largeEntity.length + 2).B, allowedActivationEntitySize.B))
          confirmErrorWithTid(responseAs[JsObject], Some(expectedErrorMsg))
        }
      }
    }

    it should s"reject unknown extensions (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(
        s"$systemId/proxy/export_c.xyz",
        s"$systemId/proxy/export_c.xyz/",
        s"$systemId/proxy/export_c.xyz/content",
        s"$systemId/proxy/export_c.xyzz",
        s"$systemId/proxy/export_c.xyzz/",
        s"$systemId/proxy/export_c.xyzz/content").foreach { path =>
        allowedMethods.foreach { m =>
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {

            if (webApiDirectives.enforceExtension) {
              status should be(NotAcceptable)
              confirmErrorWithTid(
                responseAs[JsObject],
                Some(Messages.contentTypeExtensionNotSupported(WhiskWebActionsApi.allowedExtensions)))
            } else {
              status should be(NotFound)
            }
          }
        }
      }
    }

    it should s"reject request that tries to override reserved properties (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      allowedMethodsWithEntity.foreach { m =>
        webApiDirectives.reservedProperties.foreach { p =>
          m(s"$testRoutePath/$systemId/proxy/export_c.json?$p=YYY") ~> Route.seal(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
          }

          m(s"$testRoutePath/$systemId/proxy/export_c.json", JsObject(p -> "YYY".toJson)) ~> Route.seal(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
          }
        }
      }
    }

    it should s"reject request that tries to override final parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      val contentX = JsObject("x" -> "overridden".toJson)
      val contentZ = JsObject("z" -> "overridden".toJson)

      allowedMethodsWithEntity.foreach { m =>
        invocationsAllowed += 1

        m(s"$testRoutePath/$systemId/proxy/export_c.json?x=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?y=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json", contentX) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?y=overridden", contentZ) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?empty=overridden") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[JsObject]
          response shouldBe JsObject(
            "pkg" -> s"$systemId/proxy".toJson,
            "action" -> "export_c".toJson,
            "content" -> metaPayload(
              m.method.name.toLowerCase,
              Map("empty" -> "overridden").toJson.asJsObject,
              creds,
              pkgName = "proxy"))
        }
      }
    }

    it should s"inline body when receiving entity that is not a JsObject (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      val str = "1,2,3"
      invocationsAllowed = 3

      Post(s"$testRoutePath/$systemId/proxy/export_c.json", HttpEntity(ContentTypes.`text/html(UTF-8)`, str)) ~> Route
        .seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            JsObject(webApiDirectives.body -> str.toJson),
            creds,
            pkgName = "proxy",
            headers = List(`Content-Type`(ContentTypes.`text/html(UTF-8)`))))
      }

      Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map("a" -> "b", "c" -> "d").toJson.asJsObject,
            creds,
            pkgName = "proxy"))
      }

      Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d", JsObject.empty) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map("a" -> "b", "c" -> "d").toJson.asJsObject,
            creds,
            pkgName = "proxy",
            headers = List(`Content-Type`(ContentTypes.`application/json`))))
      }
    }

    it should s"throttle subject owning namespace for web action (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // this should fail for exceeding quota
      Seq(s"$systemId/proxy/export_c.text/content/z").foreach { path =>
        allowedMethods.foreach { m =>
          failThrottleForSubject = Some(systemId)
          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(TooManyRequests)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.tooManyRequests(2, 1)))
          }
          failThrottleForSubject = None
        }
      }
    }

    it should s"respond with custom options (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        invocationsAllowed += 1 // custom options means action is invoked
        actionResult =
          Some(JsObject("headers" -> JsObject("Access-Control-Allow-Methods" -> "OPTIONS, GET, PATCH".toJson)))

        // the added headers should be ignored
        Options(s"$testRoutePath/$path") ~> addHeader(`Access-Control-Request-Headers`("x-custom-header")) ~> Route
          .seal(routes(creds)) ~> check {
          header("Access-Control-Allow-Origin") shouldBe empty
          header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, PATCH"
          header("Access-Control-Request-Headers") shouldBe empty
        }
      }
    }

    it should s"respond with custom options even when authentication is required but missing (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val entityName = MakeName.next("export")
      val action =
        stubAction(
          proxyNamespace,
          entityName,
          customOptions = true,
          requireAuthentication = true,
          requireAuthenticationAsBoolean = true)
      val path = action.fullyQualifiedName(false)
      put(entityStore, action)

      invocationsAllowed += 1 // custom options means action is invoked
      actionResult =
        Some(JsObject("headers" -> JsObject("Access-Control-Allow-Methods" -> "OPTIONS, GET, PATCH".toJson)))

      // the added headers should be ignored
      Options(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
        header("Access-Control-Allow-Origin") shouldBe empty
        header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, PATCH"
        header("Access-Control-Request-Headers") shouldBe empty
      }
    }

    it should s"support multiple values for headers (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        invocationsAllowed += 1
        actionResult =
          Some(JsObject("headers" -> JsObject("Set-Cookie" -> JsArray(JsString("a=b"), JsString("c=d; Path = /")))))

        Options(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
          headers should contain allOf (RawHeader("Set-Cookie", "a=b"),
          RawHeader("Set-Cookie", "c=d; Path = /"))
        }
      }
    }

    it should s"invoke action and respond with default options headers (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      put(entityStore, stubAction(proxyNamespace, EntityName("export_without_custom_options"), false))

      Seq(s"$systemId/proxy/export_without_custom_options.http", s"$systemId/proxy/export_without_custom_options.json")
        .foreach { path =>
          Seq(`Access-Control-Request-Headers`("x-custom-header"), RawHeader("x-custom-header", "value")).foreach {
            testHeader =>
              allowedMethods.foreach { m =>
                if (m != Options) invocationsAllowed += 1 // options verb does not invoke an action
                m(s"$testRoutePath/$path") ~> addHeader(testHeader) ~> Route.seal(routes(creds)) ~> check {
                  header("Access-Control-Allow-Origin").get.toString shouldBe "Access-Control-Allow-Origin: *"
                  header("Access-Control-Allow-Methods").get.toString shouldBe "Access-Control-Allow-Methods: OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH"
                  if (testHeader.name == `Access-Control-Request-Headers`.name) {
                    header("Access-Control-Allow-Headers").get.toString shouldBe "Access-Control-Allow-Headers: x-custom-header"
                  } else {
                    header("Access-Control-Allow-Headers").get.toString shouldBe "Access-Control-Allow-Headers: Authorization, Origin, X-Requested-With, Content-Type, Accept, User-Agent"
                  }
                }
              }
          }
        }
    }

    it should s"invoke action with head verb (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        invocationsAllowed += 1
        actionResult = Some(JsObject("headers" -> JsObject("location" -> "http://openwhisk.org".toJson)))

        Head(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
          header("location").get.toString shouldBe "location: http://openwhisk.org"
        }
      }
    }

    it should s"handle html web action with text/xml response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.html").foreach { path =>
        val html = """<html><body>test</body></html>"""
        val xml = """<?xml version="1.0" encoding="UTF-8"?><note><from>test</from></note>"""
        invocationsAllowed += 2
        actionResult = Some(JsObject("html" -> xml.toJson))

        Seq((html, MediaTypes.`text/html`), (xml, MediaTypes.`text/html`)).foreach {
          case (res, expectedMediaType) =>
            actionResult = Some(JsObject("html" -> res.toJson))

            Get(s"$testRoutePath/$path") ~> addHeader("Accept", expectedMediaType.value) ~> Route.seal(routes(creds)) ~> check {
              status should be(OK)
              contentType shouldBe ContentTypes.`text/html(UTF-8)`
              responseAs[String] shouldBe res
              mediaType shouldBe expectedMediaType
            }
        }
      }
    }

    it should s"not fail a raw http action when query or body parameters overlap with final action parameters (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      invocationsAllowed = 2

      val queryString = "x=overridden&key2=value2"
      Post(s"$testRoutePath/$systemId/proxy/raw_export_c.json?$queryString") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "raw_export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.body -> "".toJson, webApiDirectives.query -> queryString.toJson).toJson.asJsObject,
            creds,
            pkgName = "proxy"))
      }

      Post(
        s"$testRoutePath/$systemId/proxy/raw_export_c.json",
        JsObject("x" -> "overridden".toJson, "key2" -> "value2".toJson)) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "raw_export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.query -> "".toJson, webApiDirectives.body -> Base64.getEncoder.encodeToString {
              JsObject("x" -> JsString("overridden"), "key2" -> JsString("value2")).compactPrint.getBytes
            }.toJson).toJson.asJsObject,
            creds,
            pkgName = "proxy",
            headers = List(`Content-Type`(ContentTypes.`application/json`))))
      }
    }

    it should s"invoke raw action ensuring body and query arguments are set properly (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val queryString = "key1=value1&key2=value2"
      Seq(
        "1,2,3",
        JsObject("a" -> "A".toJson, "b" -> "B".toJson).prettyPrint,
        JsObject("a" -> "A".toJson, "b" -> "B".toJson).compactPrint).foreach { str =>
        Post(
          s"$testRoutePath/$systemId/proxy/raw_export_c.json?$queryString",
          HttpEntity(ContentTypes.`application/json`, str)) ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          invocationsAllowed += 1
          val response = responseAs[JsObject]
          response shouldBe JsObject(
            "pkg" -> s"$systemId/proxy".toJson,
            "action" -> "raw_export_c".toJson,
            "content" -> metaPayload(
              Post.method.name.toLowerCase,
              Map(webApiDirectives.body -> Base64.getEncoder.encodeToString {
                str.getBytes
              }.toJson, webApiDirectives.query -> queryString.toJson).toJson.asJsObject,
              creds,
              pkgName = "proxy",
              headers = List(`Content-Type`(ContentTypes.`application/json`))))
        }
      }
    }

    it should s"invoke raw action ensuring body and query arguments are empty strings when not specified in request (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Post(s"$testRoutePath/$systemId/proxy/raw_export_c.json") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        invocationsAllowed += 1
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "raw_export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.body -> "".toJson, webApiDirectives.query -> "".toJson).toJson.asJsObject,
            creds,
            pkgName = "proxy"))
      }
    }

    it should s"reject invocation of web action with invalid accept header (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        actionResult = Some(JsObject("body" -> "Plain text".toJson))
        invocationsAllowed += 1

        Get(s"$testRoutePath/$path") ~> addHeader("Accept", "application/json") ~> Route.seal(routes(creds)) ~> check {
          status should be(NotAcceptable)
          response shouldBe HttpResponse(
            NotAcceptable,
            entity = "Resource representation is only available with these types:\ntext/html; charset=UTF-8")
        }
      }
    }

    it should s"reject invocation of web action which has no entitlement (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        actionResult = Some(JsObject("body" -> "Plain text".toJson))
        failCheckEntitlement = true

        Get(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
          status should be(Forbidden)
        }
      }
    }

    it should s"not invoke an action more than once when determining entity type (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.html").foreach { path =>
        val html = """<html><body>test</body></html>"""
        val xml = """<?xml version="1.0" encoding="UTF-8"?><note><from>test</from></note>"""
        invocationsAllowed += 1
        actionResult = Some(JsObject("html" -> xml.toJson))

        Get(s"$testRoutePath/$path") ~> addHeader("Accept", MediaTypes.`text/xml`.value) ~> Route.seal(routes(creds)) ~> check {
          status should be(NotAcceptable)
        }
      }

      withClue(s"allowed invoke count did not match actual") {
        invocationsAllowed shouldBe invocationCount
      }
    }

    it should s"invoke web action ensuring JSON value body arguments are received as is (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq("this is a string".toJson, JsArray(1.toJson, "str str".toJson, false.toJson), true.toJson, 99.toJson)
        .foreach { str =>
          invocationsAllowed += 1
          Post(
            s"$testRoutePath/$systemId/proxy/export_c.json",
            HttpEntity(ContentTypes.`application/json`, str.compactPrint)) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
              "pkg" -> s"$systemId/proxy".toJson,
              "action" -> "export_c".toJson,
              "content" -> metaPayload(
                Post.method.name.toLowerCase,
                Map(webApiDirectives.body -> str).toJson.asJsObject,
                creds,
                pkgName = "proxy",
                headers = List(`Content-Type`(ContentTypes.`application/json`))))
          }
        }
    }

    it should s"invoke web action ensuring binary body is base64 encoded (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      val entity = HttpEntity(ContentType(MediaTypes.`image/png`), Base64.getDecoder().decode(pngSample))

      invocationsAllowed += 1
      Post(s"$testRoutePath/$systemId/proxy/export_c.json", entity) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.body -> pngSample.toJson).toJson.asJsObject,
            creds,
            pkgName = "proxy",
            headers = List(RawHeader(`Content-Type`.lowercaseName, MediaTypes.`image/png`.toString))))
      }
    }

    it should s"allowed string based status code (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      invocationsAllowed += 2

      actionResult = Some(JsObject(webApiDirectives.statusCode -> JsString("200")))
      Head(s"$testRoutePath/$systemId/proxy/export_c.http") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
      }

      actionResult = Some(JsObject(webApiDirectives.statusCode -> JsString("xyz")))
      Head(s"$testRoutePath/$systemId/proxy/export_c.http") ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
      }
    }

    it should s"support json (including +json subtypes) (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      val path = s"$systemId/proxy/export_c.json"
      val entity = JsObject("field1" -> "value1".toJson)

      Seq(
        ContentType(MediaType.applicationWithFixedCharset("cloudevents+json", HttpCharsets.`UTF-8`)),
        ContentTypes.`application/json`).foreach { ct =>
        invocationsAllowed += 1
        Post(s"$testRoutePath/$path", HttpEntity(ct, entity.compactPrint)) ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          responseAs[JsObject].fields("content").asJsObject.fields("field1") shouldBe entity.fields("field1")
        }
      }
    }
  }

  class TestingEntitlementProvider(config: WhiskConfig, loadBalancer: LoadBalancer)
      extends EntitlementProvider(config, loadBalancer, ControllerInstanceId("0")) {

    // The check method checks both throttle and entitlement.
    protected[core] override def check(user: Identity, right: Privilege, resource: Resource)(
      implicit transid: TransactionId): Future[Unit] = {
      val subject = user.subject

      // first, check entitlement
      if (failCheckEntitlement) {
        Future.failed(RejectRequest(Forbidden))
      } else {
        // then, check throttle
        logging.debug(this, s"test throttle is checking user '$subject' has not exceeded activation quota")
        failThrottleForSubject match {
          case Some(subject) if subject == user.subject =>
            Future.failed(RejectRequest(TooManyRequests, Messages.tooManyRequests(2, 1)))
          case _ => Future.successful({})
        }
      }
    }

    protected[core] override def grant(user: Identity, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???

    /** Revokes subject right to resource by removing them from the entitlement matrix. */
    protected[core] override def revoke(user: Identity, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???

    /** Checks if subject has explicit grant for a resource. */
    protected override def entitled(user: Identity, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???
  }

}
