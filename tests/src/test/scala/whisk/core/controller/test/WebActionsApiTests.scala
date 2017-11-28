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

package whisk.core.controller.test

import java.time.Instant
import java.util.Base64

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.FlatSpec
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
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.controller.Context
import whisk.core.controller.RejectRequest
import whisk.core.controller.WhiskWebActionsApi
import whisk.core.controller.WebApiDirectives
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.EntitlementProvider
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.ErrorResponse
import whisk.http.Messages

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
  val systemId = Subject()
  val systemKey = AuthKey()
  val systemIdentity = Future.successful(Identity(systemId, EntityName(systemId.asString), systemKey, Privilege.ALL))
  override lazy val entitlementProvider = new TestingEntitlementProvider(whiskConfig, loadBalancer)
  protected val testRoutePath = webInvokePathSegments.mkString("/", "/", "")

  behavior of "Web actions API"

  var failActionLookup = false // toggle to cause action lookup to fail
  var failActivation = 0 // toggle to cause action to fail
  var failThrottleForSubject: Option[Subject] = None // toggle to cause throttle to fail for subject
  var actionResult: Option[JsObject] = None
  var requireAuthentication = false // toggle require-whisk-auth annotation on action
  var customOptions = true // toogle web-custom-options annotation on action
  var invocationCount = 0
  var invocationsAllowed = 0

  override def beforeEach() = {
    invocationCount = 0
    invocationsAllowed = 0
  }

  override def afterEach() = {
    failActionLookup = false
    failActivation = 0
    failThrottleForSubject = None
    actionResult = None
    requireAuthentication = false
    customOptions = true
    assert(invocationsAllowed == invocationCount, "allowed invoke count did not match actual")
  }

  val allowedMethodsWithEntity = {
    val nonModifierMethods = Seq(Get, Options)
    val modifierMethods = Seq(Post, Put, Delete, Patch)
    modifierMethods ++ nonModifierMethods
  }

  val allowedMethods = {
    allowedMethodsWithEntity ++ Seq(Head)
  }

  // there is only one package that is predefined 'proxy'
  val packages = Seq(
    WhiskPackage(
      EntityPath(systemId.asString),
      EntityName("proxy"),
      parameters = Parameters("x", JsString("X")) ++ Parameters("z", JsString("z"))))

  override protected def getPackage(pkgName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    Future {
      packages.find(_.fullyQualifiedName(false) == pkgName).get
    } recoverWith {
      case _: NoSuchElementException => Future.failed(NoDocumentException("does not exist"))
    }
  }

  val defaultActionParameters = {
    Parameters("y", JsString("Y")) ++ Parameters("z", JsString("Z")) ++ Parameters("empty", JsNull)
  }

  // action names that start with 'export_' will automatically have an web-export annotation added by the test harness
  override protected def getAction(actionName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    if (!failActionLookup) {
      def theAction = {
        val annotations = Parameters(WhiskActionMetaData.finalParamsAnnotationName, JsBoolean(true))

        WhiskActionMetaData(
          actionName.path,
          actionName.name,
          jsDefaultMetaData("??"),
          defaultActionParameters,
          annotations = {
            if (actionName.name.asString.startsWith("export_")) {
              annotations ++
                Parameters("web-export", JsBoolean(true)) ++ {
                if (requireAuthentication) {
                  Parameters("require-whisk-auth", JsBoolean(true))
                } else Parameters()
              } ++ {
                if (customOptions) {
                  Parameters("web-custom-options", JsBoolean(true))
                } else Parameters()
              }
            } else if (actionName.name.asString.startsWith("raw_export_")) {
              annotations ++
                Parameters("web-export", JsBoolean(true)) ++
                Parameters("raw-http", JsBoolean(true)) ++ {
                if (requireAuthentication) {
                  Parameters("require-whisk-auth", JsBoolean(true))
                } else Parameters()
              } ++ {
                if (customOptions) {
                  Parameters("web-custom-options", JsBoolean(true))
                } else Parameters()
              }
            } else annotations
          })
      }

      if (actionName.path.defaultPackage) {
        Future.successful(theAction)
      } else {
        getPackage(actionName.path.toFullyQualifiedEntityName) map (_ => theAction)
      }
    } else {
      Future.failed(NoDocumentException("doesn't exist"))
    }
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
        ActivationId(),
        start = Instant.now,
        end = Instant.now,
        response = {
          actionResult.flatMap { r =>
            r.fields.get("application_error").map { e =>
              ActivationResponse.applicationError(e)
            } orElse r.fields.get("developer_error").map { e =>
              ActivationResponse.containerError(e)
            } orElse r.fields.get("whisk_error").map { e =>
              ActivationResponse.whiskError(e)
            } orElse None // for clarity
          } getOrElse ActivationResponse.success(Some(result))
        })

      // check that action parameters were merged with package
      // all actions have default parameters (see actionLookup stub)
      if (!action.namespace.defaultPackage) {
        getPackage(action.namespace.toFullyQualifiedEntityName) foreach { pkg =>
          action.parameters shouldBe (pkg.parameters ++ defaultActionParameters)
        }
      } else {
        action.parameters shouldBe defaultActionParameters
      }
      action.parameters.get("z") shouldBe defaultActionParameters.get("z")

      Future.successful(Right(activation))
    } else if (failActivation == 1) {
      Future.successful(Left(ActivationId()))
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
                  headers: List[HttpHeader] = List()) = {
    val packageActionParams = Option(pkgName)
      .filter(_ != null)
      .flatMap(n => packages.find(_.name == EntityName(n)))
      .map(_.parameters)
      .getOrElse(Parameters())

    (packageActionParams ++ defaultActionParameters).merge {
      Some {
        JsObject(
          params.fields ++
            body.map(_.fields).getOrElse(Map()) ++
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
    error.fields.get("code").get shouldBe an[JsNumber]
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

    it should s"reject requests when identity, package or action lookup fail or missing annotation (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // the first of these fails in the identity lookup,
      // the second in the package lookup (does not exist),
      // the third fails the annotation check (no web-export annotation because name doesn't start with export_c)
      // the fourth fails the action lookup
      Seq("guest/proxy/c", s"$systemId/doesnotexist/c", s"$systemId/default/c", s"$systemId/proxy/export_fail")
        .foreach { path =>
          allowedMethods.foreach { m =>
            failActionLookup = path.endsWith("fail")

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

    it should s"reject requests when authentication is required but none given (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_auth").foreach { path =>
        allowedMethods.foreach { m =>
          if (creds.isDefined)
            invocationsAllowed += 1
          requireAuthentication = true

          m(s"$testRoutePath/${path}.json") ~> Route.seal(routes(creds)) ~> check {
            creds match {
              case None => status should be(Unauthorized)
              case Some(user) =>
                status should be(OK)
                val response = responseAs[JsObject]
                response shouldBe JsObject(
                  "pkg" -> s"$systemId/proxy".toJson,
                  "action" -> "export_auth".toJson,
                  "content" -> metaPayload(m.method.name.toLowerCase, JsObject(), creds, pkgName = "proxy"))
                response.fields("content").asJsObject.fields(webApiDirectives.namespace) shouldBe user.namespace.toJson
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
                JsObject(),
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

      Seq("", JsArray().compactPrint, JsObject().compactPrint, JsNull.compactPrint).foreach { arg =>
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
                  if (arg.nonEmpty && arg != "{}") JsObject(webApiDirectives.body -> arg.parseJson) else JsObject(),
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
              "content" -> metaPayload(m.method.name.toLowerCase, JsObject(), creds))
          }
        }
      }
    }

    it should s"project a field from the result object (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json/content").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe metaPayload(
              m.method.name.toLowerCase,
              JsObject(),
              creds,
              path = "/content",
              pkgName = "proxy")
          }
        }
      }

      Seq(
        s"$systemId/proxy/export_c.text/content/z",
        s"$systemId/proxy/export_c.text/content/z/",
        s"$systemId/proxy/export_c.text/content/z//").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[String]
            response shouldBe "Z"
          }
        }
      }
    }

    it should s"reject when projecting a result which does not match expected type (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // these project a result which does not match expected type
      Seq(s"$systemId/proxy/export_c.json/a").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject("a" -> JsString("b")))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(BadRequest)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.invalidMedia(MediaTypes.`application/json`)))
          }
        }
      }
    }

    it should s"reject when projecting a field from the result object that does not exist (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.text/foobar", s"$systemId/proxy/export_c.text/content/z/x").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(NotFound)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.propertyNotFound))
            // ensure that error message is pretty printed as { error, code }
            responseAs[String].lines should have size 4
          }
        }
      }
    }

    it should s"not project an http response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      // http extension does not project
      Seq(s"$systemId/proxy/export_c.http/content/response").foreach { path =>
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

    it should s"use default projection for extension (auth? ${creds.isDefined})" in {
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
          headers.length shouldBe 0
        }
      }

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        Set(JsObject(), JsObject("body" -> "".toJson), JsObject("body" -> JsNull)).foreach { bodyResult =>
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

      Seq(JsObject("a" -> "A".toJson), JsArray("a".toJson), JsString("a"), JsBoolean(true), JsNumber(1), JsNull)
        .foreach { jsval =>
          val path = s"$systemId/proxy/export_c.text/res"
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(JsObject("res" -> jsval))

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

    it should s"handle http web action with base64 encoded JSON response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        Seq(OK, Created).foreach { statusCode =>
          allowedMethods.foreach { m =>
            invocationsAllowed += 1
            actionResult = Some(
              JsObject(
                "headers" -> JsObject("content-type" -> "application/json".toJson),
                webApiDirectives.statusCode -> statusCode.intValue.toJson,
                "body" -> Base64.getEncoder.encodeToString {
                  JsObject("field" -> "value".toJson).compactPrint.getBytes
                }.toJson))

            m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
              status should be(statusCode)
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
              "body" -> Base64.getEncoder.encodeToString {
                JsObject("field" -> "value".toJson).compactPrint.getBytes
              }.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
          }
        }

        // omit status code and headers
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(JsObject("body" -> Base64.getEncoder.encodeToString {
            JsObject("field" -> "value".toJson).compactPrint.getBytes
          }.toJson))

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
              "body" -> Base64.getEncoder.encodeToString {
                JsObject("field" -> "value".toJson).compactPrint.getBytes
              }.toJson))

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
              headers shouldBe empty
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
              headers shouldBe List(RawHeader("Set-Cookie", "a=b"))
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
              headers shouldBe empty
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
              headers shouldBe List(RawHeader("Set-Cookie", "a=b"))
              response.entity shouldBe HttpEntity.Empty
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

    it should s"handle http web action with base64 encoded unknown '+json' response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject("content-type" -> "application/hal+json".toJson),
              webApiDirectives.statusCode -> OK.intValue.toJson,
              "body" -> Base64.getEncoder.encodeToString {
                JsObject("field" -> "value".toJson).compactPrint.getBytes
              }.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            mediaType.value shouldBe "application/hal+json"
            responseAs[String].parseJson shouldBe JsObject("field" -> "value".toJson)
          }
        }
      }
    }

    it should s"handle http web action without base64 encoded JSON response (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(
        (JsObject("content-type" -> "application/json".toJson), OK),
        (JsObject(), OK),
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

    it should s"handle http web action without base64 encoded known '+json' response (auth? ${creds.isDefined})" in {
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

    it should s"handle http web action without base64 encoded unknown '+json' response (auth? ${creds.isDefined})" in {
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
      val png = "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAGCAYAAAD68A/GAAAA/klEQVQYGWNgAAEHBxaG//+ZQMyyn581Pfas+cRQnf1LfF" +
        "Ljf+62smUgcUbt0FA2Zh7drf/ffMy9vLn3RurrW9e5hCU11i2azfD4zu1/DHz8TAy/foUxsXBrFzHzC7r8+M9S1vn1qxQT07dDjL" +
        "9fdemrqKxlYGT6z8AIMo6hgeUfA0PUvy9fGFh5GWK3z7vNxSWt++jX99+8SoyiGQwsW38w8PJEM7x5v5SJ8f+/xv8MDAzffv9hev" +
        "fkWjiXBGMpMx+j2awovjcMjFztDO8+7GF49LkbZDCDeXLTWnZO7qDfn1/+5jbw/8pjYWS4wZLztXnuEuYTk2M+MzIw/AcA36Vewa" +
        "D6fzsAAAAASUVORK5CYII="
      val expectedEntity = HttpEntity(ContentType(MediaTypes.`image/png`), Base64.getDecoder().decode(png))

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        allowedMethods.foreach { m =>
          invocationsAllowed += 1
          actionResult = Some(
            JsObject(
              "headers" -> JsObject(`Content-Type`.lowercaseName -> MediaTypes.`image/png`.toString.toJson),
              "body" -> png.toJson))

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

    it should s"reject http web action with mismatch between header and response (auth? ${creds.isDefined})" in {
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
            status should be(BadRequest)
            confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
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

    it should s"handle an activation that results in application error and response matches extension (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http", s"$systemId/proxy/export_c.http/ignoreme").foreach { path =>
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

    it should s"handle an activation that results in application error but where response does not match extension (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/ignoreme").foreach { path =>
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

      Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/ignoreme", s"$systemId/proxy/export_c.text")
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

      Seq(s"$systemId/proxy/export_c.text/content/field1", s"$systemId/proxy/export_c.text/content/field2").foreach {
        path =>
          val form = FormData(Map("field1" -> "value1", "field2" -> "value2"))
          invocationsAllowed += 2

          Post(s"$testRoutePath/$path", form.toEntity) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[String] should (be("value1") or be("value2"))
          }

          Post(s"$testRoutePath/$path", form.toEntity(HttpCharsets.`US-ASCII`)) ~> Route.seal(routes(creds)) ~> check {
            status should be(OK)
            responseAs[String] should (be("value1") or be("value2"))
          }
      }
    }

    it should s"reject requests when entity size exceeds allowed limit (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.json").foreach { path =>
        val largeEntity = "a" * (allowedActivationEntitySize.toInt + 1)

        val content = s"""{"a":"$largeEntity"}"""
        Post(s"$testRoutePath/$path", content.parseJson.asJsObject) ~> Route.seal(routes(creds)) ~> check {
          status should be(RequestEntityTooLarge)
          val expectedErrorMsg = Messages.entityTooBig(
            SizeError(fieldDescriptionForSizeError, (largeEntity.length + 8).B, allowedActivationEntitySize.B))
          confirmErrorWithTid(responseAs[JsObject], Some(expectedErrorMsg))
        }

        val form = FormData(Map("a" -> largeEntity))
        Post(s"$testRoutePath/$path", form) ~> Route.seal(routes(creds)) ~> check {
          status should be(RequestEntityTooLarge)
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
          actionResult = Some(JsObject(webApiDirectives.statusCode -> Created.intValue.toJson))

          m(s"$testRoutePath/$path") ~> Route.seal(routes(creds)) ~> check {
            if (webApiDirectives.enforceExtension) {
              status should be(NotAcceptable)
              confirmErrorWithTid(
                responseAs[JsObject],
                Some(Messages.contentTypeExtensionNotSupported(WhiskWebActionsApi.allowedExtensions)))
            } else {
              invocationsAllowed += 1
              status should be(Created)
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
      val contentX = JsObject("x" -> "overriden".toJson)
      val contentZ = JsObject("z" -> "overriden".toJson)

      allowedMethodsWithEntity.foreach { m =>
        invocationsAllowed += 1

        m(s"$testRoutePath/$systemId/proxy/export_c.json?x=overriden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?y=overriden") ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json", contentX) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?y=overriden", contentZ) ~> Route.seal(routes(creds)) ~> check {
          status should be(BadRequest)
          responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        m(s"$testRoutePath/$systemId/proxy/export_c.json?empty=overriden") ~> Route.seal(routes(creds)) ~> check {
          status should be(OK)
          val response = responseAs[JsObject]
          response shouldBe JsObject(
            "pkg" -> s"$systemId/proxy".toJson,
            "action" -> "export_c".toJson,
            "content" -> metaPayload(
              m.method.name.toLowerCase,
              Map("empty" -> "overriden").toJson.asJsObject,
              creds,
              pkgName = "proxy"))
        }
      }
    }

    it should s"inline body when receiving entity that is not a JsObject (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      val str = "1,2,3"
      invocationsAllowed = 3

      /*
       * Now supporting all content types with inlined "body".
       *
             Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d", "1,2,3") ~> Route.seal(routes(creds)) ~> check {
                 status should be(BadRequest)
                 confirmErrorWithTid(responseAs[JsObject], Some(Messages.contentTypeNotSupported))
             }
       *
       */

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

      Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d", JsObject()) ~> Route.seal(routes(creds)) ~> check {
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

    it should s"invoke action with options verb with custom options (auth? ${creds.isDefined})" in {
      implicit val tid = transid()

      Seq(s"$systemId/proxy/export_c.http").foreach { path =>
        invocationsAllowed += 1
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

    it should s"invoke action with options verb without custom options (auth? ${creds.isDefined})" in {
      implicit val tid = transid()
      customOptions = false

      Seq(s"$systemId/proxy/export_c.http", s"$systemId/proxy/export_c.json").foreach { path =>
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
                  header("Access-Control-Allow-Headers").get.toString shouldBe "Access-Control-Allow-Headers: Authorization, Content-Type"
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

      val queryString = "x=overriden&key2=value2"
      Post(s"$testRoutePath/$systemId/proxy/raw_export_c.json?$queryString") ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "raw_export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.body -> JsObject(), webApiDirectives.query -> queryString.toJson).toJson.asJsObject,
            creds,
            pkgName = "proxy"))
      }

      Post(
        s"$testRoutePath/$systemId/proxy/raw_export_c.json",
        JsObject("x" -> "overriden".toJson, "key2" -> "value2".toJson)) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        val response = responseAs[JsObject]
        response shouldBe JsObject(
          "pkg" -> s"$systemId/proxy".toJson,
          "action" -> "raw_export_c".toJson,
          "content" -> metaPayload(
            Post.method.name.toLowerCase,
            Map(webApiDirectives.query -> "".toJson, webApiDirectives.body -> Base64.getEncoder.encodeToString {
              JsObject("x" -> JsString("overriden"), "key2" -> JsString("value2")).compactPrint.getBytes
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

    it should s"invoke web action ensuring JSON value body arguments are not Base64 encoded (auth? ${creds.isDefined})" in {
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
  }

  class TestingEntitlementProvider(config: WhiskConfig, loadBalancer: LoadBalancer)
      extends EntitlementProvider(config, loadBalancer) {

    protected[core] override def checkThrottles(user: Identity)(implicit transid: TransactionId): Future[Unit] = {
      val subject = user.subject
      logging.debug(this, s"test throttle is checking user '$subject' has not exceeded activation quota")

      failThrottleForSubject match {
        case Some(subject) if subject == user.subject =>
          Future.failed(RejectRequest(TooManyRequests, Messages.tooManyRequests(2, 1)))
        case _ => Future.successful({})
      }
    }

    protected[core] override def grant(subject: Subject, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???

    /** Revokes subject right to resource by removing them from the entitlement matrix. */
    protected[core] override def revoke(subject: Subject, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???

    /** Checks if subject has explicit grant for a resource. */
    protected override def entitled(subject: Subject, right: Privilege, resource: Resource)(
      implicit transid: TransactionId) = ???
  }

}
