/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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

import spray.http.FormData
import spray.http.HttpMethods
import spray.http.MediaTypes
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.controller.Context
import whisk.core.controller.RejectRequest
import whisk.core.controller.WhiskMetaApi
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.EntitlementProvider
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Privilege._
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.iam.NamespaceProvider
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Meta API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class MetaApiTests extends ControllerTestCommon with WhiskMetaApi with BeforeAndAfterEach {

    override val apipath = "api"
    override val apiversion = "v1"

    val systemId = Subject()
    val systemKey = AuthKey()
    val systemIdentity = Future.successful(Identity(systemId, EntityName(systemId.asString), systemKey, Privilege.ALL))
    override lazy val entitlementProvider = new TestingEntitlementProvider(whiskConfig, loadBalancer, iam)

    /** Meta API tests */
    behavior of "Meta API"

    var failActionLookup = false // toggle to cause action lookup to fail
    var failActivation = 0 // toggle to cause action to fail
    var failThrottleForSubject: Option[Subject] = None // toggle to cause throttle to fail for subject
    var actionResult: Option[JsObject] = None
    var requireAuthentication = false // toggle require-whisk-auth annotation on action

    override def afterEach() = {
        failActionLookup = false
        failActivation = 0
        failThrottleForSubject = None
        actionResult = None
        requireAuthentication = false
    }

    val allowedMethods = Seq(Get, Post, Put, Delete)

    // there is only one package that is predefined 'proxy'
    val packages = Seq(
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("proxy"),
            parameters = Parameters("x", JsString("X")) ++ Parameters("z", JsString("z"))))

    override protected def getPackage(pkgName: FullyQualifiedEntityName)(
        implicit transid: TransactionId) = {
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
    override protected def getAction(actionName: FullyQualifiedEntityName)(
        implicit transid: TransactionId) = {
        if (!failActionLookup) {
            def theAction = {
                val annotations = Parameters(WhiskAction.finalParamsAnnotationName, JsBoolean(true))

                WhiskAction(actionName.path, actionName.name, Exec.js("??"), defaultActionParameters, annotations = {
                    if (actionName.name.asString.startsWith("export_")) {
                        annotations ++
                            Parameters("web-export", JsBoolean(true)) ++ {
                                if (requireAuthentication) {
                                    Parameters("require-whisk-auth", JsBoolean(true))
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
    override protected def getIdentity(namespace: EntityName)(
        implicit transid: TransactionId): Future[Identity] = {
        if (namespace.asString == systemId.asString) {
            systemIdentity
        } else {
            logging.info(this, s"namespace has no identity")
            Future.failed(RejectRequest(BadRequest))
        }
    }

    override protected[controller] def invokeAction(user: Identity, action: WhiskAction, payload: Option[JsObject], blocking: Boolean, waitOverride: Option[FiniteDuration] = None)(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        if (failActivation == 0) {
            // construct a result stub that includes:
            // 1. the package name for the action (to confirm that this resolved to systemId)
            // 2. the action name (to confirm that this resolved to the expected meta action)
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
                        r.fields.get("application_error").map {
                            e => ActivationResponse.applicationError(e)
                        } orElse r.fields.get("developer_error").map {
                            e => ActivationResponse.containerError(e)
                        } orElse r.fields.get("whisk_error").map {
                            e => ActivationResponse.whiskError(e)
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

            Future.successful(activation.activationId, Some(activation))
        } else if (failActivation == 1) {
            Future.successful(ActivationId(), None)
        } else {
            Future.failed(new IllegalStateException("bad activation"))
        }
    }

    def metaPayload(method: String, params: Map[String, String], identity: Option[Identity], path: String = "", body: Option[JsObject] = None, pkgName: String = null) = {
        val packageActionParams = Option(pkgName).filter(_ != null).flatMap(n => packages.find(_.name == EntityName(n)))
            .map(_.parameters)
            .getOrElse(Parameters())

        (packageActionParams ++ defaultActionParameters).merge {
            Some {
                JsObject(
                    params.toJson.asJsObject.fields ++
                        body.map(_.fields).getOrElse(Map()) ++
                        Context(HttpMethods.getForKey(method.toUpperCase).get, List(), path, Map()).metadata(identity))
            }
        }.get
    }

    def confirmErrorWithTid(error: JsObject, message: Option[String] = None) = {
        error.fields.size shouldBe 2
        error.fields.get("error") shouldBe defined
        message.foreach { m => error.fields.get("error").get shouldBe JsString(m) }
        error.fields.get("code") shouldBe defined
        error.fields.get("code").get shouldBe an[JsNumber]
    }

    val testRoutePath = s"/$routePath/$webInvokePath"

    Seq(None, Some(WhiskAuth(Subject(), AuthKey()).toIdentity)).foreach { creds =>

        it should s"split action name and extension (auth? ${creds.isDefined})" in {
            val r = WhiskMetaApi.extensionSplitter
            Seq("t.j.http", "t.js.http", "tt.j.http", "tt.js.http").foreach { s =>
                val r(n, e) = s
                val i = s.lastIndexOf(".")
                n shouldBe s.substring(0, i)
                e shouldBe s.substring(i + 1)
            }

            Seq("t.js", "t.js.htt", "t.js.httpz").foreach { s =>
                a[MatchError] should be thrownBy {
                    val r(n, e) = s
                }
            }

        }

        it should s"not match invalid routes (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // none of these should match a route
            Seq("a", "a/b", "/a", s"$systemId/c", s"$systemId/export_c").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(NotFound)
                    }
                }
        }

        it should s"reject unsupported http verbs (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq((Head, MethodNotAllowed), (Patch, MethodNotAllowed)).
                foreach {
                    case (m, code) =>
                        m(s"$systemId/proxy/export_c.json") ~> sealRoute(routes(creds)) ~> check {
                            status should be(code)
                        }
                }
        }

        it should s"reject requests when identity, package or action lookup fail or missing annotation (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // the first of these fails in the identity lookup,
            // the second in the package lookup (does not exist),
            // the third fails the annotation check (no web-export annotation because name doesn't start with export_c)
            // the fourth fails the action lookup
            Seq("guest/proxy/c", s"$systemId/doesnotexist/c", s"$systemId/default/c", s"$systemId/proxy/export_fail").
                foreach { path =>
                    failActionLookup = path.endsWith("fail")

                    Get(s"$testRoutePath/${path}.json") ~> sealRoute(routes(creds)) ~> check {
                        status should be(NotFound)
                    }

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(NotAcceptable)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.contentTypeNotSupported))
                    }
                }
        }

        it should s"reject requests when authentication is required but none given (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_auth").
                foreach { path =>
                    requireAuthentication = true

                    Get(s"$testRoutePath/${path}.json") ~> sealRoute(routes(creds)) ~> check {
                        creds match {
                            case None => status should be(Unauthorized)
                            case Some(user) =>
                                status should be(OK)
                                val response = responseAs[JsObject]
                                response shouldBe JsObject(
                                    "pkg" -> s"$systemId/proxy".toJson,
                                    "action" -> "export_auth".toJson,
                                    "content" -> metaPayload("get", Map(), creds, pkgName = "proxy"))
                                response.fields("content").asJsObject.fields("__ow_meta_namespace") shouldBe user.namespace.toJson
                        }
                    }
                }
        }

        it should s"invoke action that times out and provide a code (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            failActivation = 1
            Get(s"$testRoutePath/$systemId/proxy/export_c.json") ~> sealRoute(routes(creds)) ~> check {
                status should be(Accepted)
                val response = responseAs[JsObject]
                confirmErrorWithTid(response, Some("Response not yet ready."))
            }
        }

        it should s"invoke action that errors and response with error and code (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            failActivation = 2
            Get(s"$testRoutePath/$systemId/proxy/export_c.json") ~> sealRoute(routes(creds)) ~> check {
                status should be(InternalServerError)
                val response = responseAs[JsObject]
                confirmErrorWithTid(response)
            }
        }

        it should s"invoke action and merge query parameters (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json?a=b&c=d")
                .foreach { path =>
                    allowedMethods.foreach { m =>
                        m(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                            status should be(OK)
                            val response = responseAs[JsObject]
                            response shouldBe JsObject(
                                "pkg" -> s"$systemId/proxy".toJson,
                                "action" -> "export_c".toJson,
                                "content" -> metaPayload(
                                    m.method.name.toLowerCase,
                                    Map("a" -> "b", "c" -> "d"),
                                    creds,
                                    pkgName = "proxy"))
                        }
                    }
                }
        }

        it should s"invoke action and merge body parameters (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // both of these should produce full result objects (trailing slash is ok)
            Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/")
                .foreach { path =>
                    allowedMethods.foreach { m =>
                        val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
                        val p = if (path.endsWith("/")) "/" else ""
                        m(s"$testRoutePath/$path", content) ~> sealRoute(routes(creds)) ~> check {
                            status should be(OK)
                            val response = responseAs[JsObject]
                            response shouldBe JsObject(
                                "pkg" -> s"$systemId/proxy".toJson,
                                "action" -> "export_c".toJson,
                                "content" -> metaPayload(
                                    m.method.name.toLowerCase,
                                    Map(),
                                    creds,
                                    body = Some(content),
                                    path = p,
                                    pkgName = "proxy"))
                        }
                    }
                }
        }

        it should s"invoke action and merge query and body parameters (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json?a=b&c=d")
                .foreach { path =>
                    allowedMethods.foreach { m =>
                        val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
                        m(s"$testRoutePath/$path", content) ~> sealRoute(routes(creds)) ~> check {
                            status should be(OK)
                            val response = responseAs[JsObject]
                            response shouldBe JsObject(
                                "pkg" -> s"$systemId/proxy".toJson,
                                "action" -> "export_c".toJson,
                                "content" -> metaPayload(
                                    m.method.name.toLowerCase,
                                    Map("a" -> "b", "c" -> "d"),
                                    creds,
                                    body = Some(content),
                                    pkgName = "proxy"))
                        }
                    }
                }
        }

        it should s"invoke action in default package (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/default/export_c.json").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[JsObject]
                        response shouldBe JsObject(
                            "pkg" -> s"$systemId".toJson,
                            "action" -> "export_c".toJson,
                            "content" -> metaPayload("get", Map(), creds))
                    }
                }
        }

        it should s"project a field from the result object (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json/content").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[JsObject]
                        response shouldBe metaPayload("get", Map(), creds, path = "/content", pkgName = "proxy")
                    }
                }

            Seq(s"$systemId/proxy/export_c.text/content/z", s"$systemId/proxy/export_c.text/content/z/", s"$systemId/proxy/export_c.text/content/z//").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[String]
                        response shouldBe "Z"
                    }
                }
        }

        it should s"reject when projecting a result which does not match expected type (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // these project a result which does not match expected type
            Seq(s"$systemId/proxy/export_c.json/a").
                foreach { path =>
                    actionResult = Some(JsObject("a" -> JsString("b")))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(BadRequest)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.invalidMedia(MediaTypes.`application/json`)))
                    }
                }
        }

        it should s"reject when projecting a field from the result object that does not exist (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.text/foobar", s"$systemId/proxy/export_c.text/content/z/x").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(NotFound)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.propertyNotFound))
                    }
                }
        }

        it should s"not project an http response (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // http extension does not project
            Seq(s"$systemId/proxy/export_c.http/content/response").
                foreach { path =>
                    actionResult = Some(JsObject("headers" -> JsObject("location" -> "http://openwhisk.org".toJson), "code" -> Found.intValue.toJson))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(Found)
                        header("location").get.toString shouldBe "location: http://openwhisk.org"
                    }
                }
        }

        it should s"use default projection for extension (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http").
                foreach { path =>
                    actionResult = Some(JsObject("headers" -> JsObject("location" -> "http://openwhisk.org".toJson), "code" -> Found.intValue.toJson))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(Found)
                        header("location").get.toString shouldBe "location: http://openwhisk.org"
                    }
                }

            Seq(s"$systemId/proxy/export_c.text").
                foreach { path =>
                    actionResult = Some(JsObject("text" -> JsString("default text")))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[String]
                        response shouldBe "default text"
                    }
                }

            Seq(s"$systemId/proxy/export_c.json").
                foreach { path =>
                    actionResult = Some(JsObject("foobar" -> JsString("foobar")))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[JsObject]
                        response shouldBe actionResult.get
                    }
                }

            Seq(s"$systemId/proxy/export_c.html").
                foreach { path =>
                    actionResult = Some(JsObject("html" -> JsString("<html>hi</htlml>")))
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        val response = responseAs[String]
                        response shouldBe "<html>hi</htlml>"
                    }
                }
        }

        it should s"handle http web action with base64 encoded response (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http").
                foreach { path =>
                    actionResult = Some(JsObject(
                        "headers" -> JsObject(
                            "content-type" -> "application/json".toJson),
                        "code" -> OK.intValue.toJson,
                        "body" -> Base64.getEncoder.encodeToString {
                            JsObject("field" -> "value".toJson).compactPrint.getBytes
                        }.toJson))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        header("content-type").get.toString shouldBe "content-type: application/json"
                        responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
                    }
                }
        }

        it should s"handle http web action with html/text response (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http").
                foreach { path =>
                    actionResult = Some(JsObject(
                        "code" -> OK.intValue.toJson,
                        "body" -> "hello world".toJson))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        responseAs[String] shouldBe "hello world"
                    }
                }
        }

        it should s"reject http web action with mimatch between header and response (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http").
                foreach { path =>
                    actionResult = Some(JsObject(
                        "headers" -> JsObject(
                            "content-type" -> "application/json".toJson),
                        "code" -> OK.intValue.toJson,
                        "body" -> "hello world".toJson))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(BadRequest)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
                    }
                }
        }

        it should s"reject http web action with unknown header (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http").
                foreach { path =>
                    actionResult = Some(JsObject(
                        "headers" -> JsObject(
                            "content-type" -> "xyz/bar".toJson),
                        "code" -> OK.intValue.toJson,
                        "body" -> "hello world".toJson))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(BadRequest)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpUnknownContentType))
                    }
                }
        }

        it should s"handle an activation that results in application error and response matches extension (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.http", s"$systemId/proxy/export_c.http/ignoreme").
                foreach { path =>
                    actionResult = Some(JsObject(
                        "application_error" -> JsObject(
                            "code" -> OK.intValue.toJson,
                            "body" -> "no hello for you".toJson)))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        responseAs[String] shouldBe "no hello for you"
                    }
                }
        }

        it should s"handle an activation that results in application error but where response does not match extension (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/ignoreme").
                foreach { path =>
                    actionResult = Some(JsObject("application_error" -> "bad response type".toJson))

                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(BadRequest)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.invalidMedia(MediaTypes.`application/json`)))
                    }
                }
        }

        it should s"handle an activation that results in developer or system error (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/ignoreme", s"$systemId/proxy/export_c.text").
                foreach { path =>
                    Seq("developer_error", "whisk_error").foreach { e =>
                        actionResult = Some(JsObject(e -> "bad response type".toJson))
                        Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
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

        it should s"support formdata (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.text/content/field1", s"$systemId/proxy/export_c.text/content/field2").
                foreach { path =>
                    val form = FormData(Seq("field1" -> "value1", "field2" -> "value2"))
                    Post(s"$testRoutePath/$path", form) ~> sealRoute(routes(creds)) ~> check {
                        status should be(OK)
                        responseAs[String] should (be("value1") or be("value2"))
                    }
                }
        }

        it should s"reject requests when entity size exceeds allowed limit (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.json").
                foreach { path =>
                    val largeEntity = "a" * (allowedActivationEntitySize.toInt + 1)

                    val content = s"""{"a":"$largeEntity"}"""
                    Post(s"$testRoutePath/$path", content.parseJson.asJsObject) ~> sealRoute(routes(creds)) ~> check {
                        status should be(RequestEntityTooLarge)
                        val expectedErrorMsg = Messages.entityTooBig(SizeError(
                            fieldDescriptionForSizeError,
                            (largeEntity.length + 13).B,
                            allowedActivationEntitySize.B))
                        confirmErrorWithTid(responseAs[JsObject], Some(expectedErrorMsg))
                    }

                    val form = FormData(Seq("a" -> largeEntity))
                    Post(s"$testRoutePath/$path", form) ~> sealRoute(routes(creds)) ~> check {
                        status should be(RequestEntityTooLarge)
                        val expectedErrorMsg = Messages.entityTooBig(SizeError(
                            fieldDescriptionForSizeError,
                            (largeEntity.length + 2).B,
                            allowedActivationEntitySize.B))
                        confirmErrorWithTid(responseAs[JsObject], Some(expectedErrorMsg))
                    }
                }
        }

        it should s"reject unknown extensions (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Seq(s"$systemId/proxy/export_c.xyz", s"$systemId/proxy/export_c.xyz/", s"$systemId/proxy/export_c.xyz/content",
                s"$systemId/proxy/export_c.xyzz", s"$systemId/proxy/export_c.xyzz/", s"$systemId/proxy/export_c.xyzz/content").
                foreach { path =>
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(NotAcceptable)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.contentTypeNotSupported))
                    }
                }
        }

        it should s"reject request that tries to override reserved properties (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            allowedMethods.foreach { m =>
                WhiskMetaApi.reservedProperties.foreach { p =>
                    m(s"$testRoutePath/$systemId/proxy/export_c.json?$p=YYY") ~> sealRoute(routes(creds)) ~> check {
                        status should be(BadRequest)
                        responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                    }

                    m(s"$testRoutePath/$systemId/proxy/export_c.json", JsObject(p -> "YYY".toJson)) ~> sealRoute(routes(creds)) ~> check {
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

            Get(s"$testRoutePath/$systemId/proxy/export_c.json?x=overriden") ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
            }

            Get(s"$testRoutePath/$systemId/proxy/export_c.json?y=overriden") ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
            }

            Get(s"$testRoutePath/$systemId/proxy/export_c.json", contentX) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
            }

            Get(s"$testRoutePath/$systemId/proxy/export_c.json?y=overriden", contentZ) ~> sealRoute(routes(creds)) ~> check {
                status should be(BadRequest)
                responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
            }

            Get(s"$testRoutePath/$systemId/proxy/export_c.json?empty=overriden") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                val response = responseAs[JsObject]
                response shouldBe JsObject(
                    "pkg" -> s"$systemId/proxy".toJson,
                    "action" -> "export_c".toJson,
                    "content" -> metaPayload(
                        "get",
                        Map("empty" -> "overriden"),
                        creds,
                        pkgName = "proxy"))
            }
        }

        it should s"rejection invoke action when receiving entity that is not a JsObject (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d", "1,2,3") ~> sealRoute(routes(creds)) ~> check {
                status should be(UnsupportedMediaType)
                responseAs[String] should include("application/json")
            }

            Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
            }

            Post(s"$testRoutePath/$systemId/proxy/export_c.json?a=b&c=d", JsObject()) ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
            }

        }

        it should s"throttle subject owning namespace for web action (auth? ${creds.isDefined})" in {
            implicit val tid = transid()

            // this should fail for exceeding quota
            Seq(s"$systemId/proxy/export_c.text/content/z").
                foreach { path =>
                    failThrottleForSubject = Some(systemId)
                    Get(s"$testRoutePath/$path") ~> sealRoute(routes(creds)) ~> check {
                        status should be(TooManyRequests)
                        confirmErrorWithTid(responseAs[JsObject], Some(Messages.tooManyRequests))
                    }
                    failThrottleForSubject = None
                }
        }
    }

    class TestingEntitlementProvider(
        config: WhiskConfig,
        loadBalancer: LoadBalancer,
        iam: NamespaceProvider)
        extends EntitlementProvider(config, loadBalancer, iam) {

        protected[core] override def checkThrottles(user: Identity)(
            implicit transid: TransactionId): Future[Unit] = {
            val subject = user.subject
            logging.debug(this, s"test throttle is checking user '$subject' has not exceeded activation quota")

            failThrottleForSubject match {
                case Some(subject) if subject == user.subject =>
                    Future.failed(RejectRequest(TooManyRequests, Messages.tooManyRequests))
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
