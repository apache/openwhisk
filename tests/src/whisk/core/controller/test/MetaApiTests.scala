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

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Instant
import java.util.Base64

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.InfoLevel
import spray.http.FormData
import spray.http.HttpMethods
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.controller.Context
import whisk.core.controller.RejectRequest
import whisk.core.controller.WhiskMetaApi
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.Privilege
import whisk.core.entity._
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
    override lazy val systemKey = AuthKey()
    override lazy val systemIdentity = Future.successful(Identity(systemId, EntityName(systemId.asString), systemKey, Privilege.ALL))

    /** Meta API tests */
    behavior of "Meta API"

    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject.asString)
    setVerbosity(InfoLevel)

    val packages = Seq(
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("notmeta"),
            annotations = Parameters("meta", JsBoolean(false))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("badmeta"),
            annotations = Parameters("meta", JsBoolean(true))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("heavymeta"),
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi")) ++
                Parameters("post", JsString("createRoute")) ++
                Parameters("delete", JsString("deleteApi"))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("partialmeta"),
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi"))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("packagemeta"),
            parameters = Parameters("x", JsString("X")) ++ Parameters("z", JsString("z")),
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi"))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("publicmeta"),
            publish = true,
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi"))),
        WhiskPackage(
            EntityPath(systemId.asString),
            EntityName("bindingmeta"),
            Some(Binding(EntityName(systemId.asString), EntityName("heavymeta"))),
            annotations = Parameters("meta", JsBoolean(true))),
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
        Parameters("y", JsString("Y")) ++ Parameters("z", JsString("Z"))
    }

    override protected def getAction(actionName: FullyQualifiedEntityName)(
        implicit transid: TransactionId) = {
        if (!failActionLookup) {
            def theAction = {
                val annotations = Parameters(WhiskAction.finalParamsAnnotationName, JsBoolean(true))

                WhiskAction(actionName.path, actionName.name, Exec.js("??"), defaultActionParameters, annotations = {
                    if (actionName.name.asString.startsWith("export_")) {
                        annotations ++ Parameters("web-export", JsBoolean(true))
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

    override protected def getIdentity(namespace: EntityName)(
        implicit transid: TransactionId): Future[Identity] = {
        if (namespace.asString == systemId.asString) {
            systemIdentity
        } else {
            info(this, s"namespace has no identity")
            Future.failed(RejectRequest(BadRequest))
        }
    }

    var actionResult: Option[JsObject] = None
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
                response = ActivationResponse.success(Some(result)))

            // check that action parameters were merged with package
            // all actions have default parameters (see actionLookup stub)
            val packageName = Await.result(resolvePackageName(action.namespace.last), dbOpTimeout)
            getPackage(packageName) foreach { pkg =>
                action.parameters shouldBe (pkg.parameters ++ defaultActionParameters)
                action.parameters.get("z") shouldBe defaultActionParameters.get("z")
            }

            Future.successful(activation.activationId, Some(activation))
        } else if (failActivation == 1) {
            Future.successful(ActivationId(), None)
        } else {
            Future.failed(new IllegalStateException("bad activation"))
        }
    }

    def metaPayload(method: String, params: Map[String, String], identity: Identity, path: String = "", body: Option[JsObject] = None, pkgName: String = null) = {
        (Option(pkgName).filter(_ != null).flatMap(n => packages.find(_.name == EntityName(n)))
            .map(_.parameters)
            .getOrElse(Parameters()) ++ defaultActionParameters).merge {
                Some {
                    JsObject(
                        params.toJson.asJsObject.fields ++
                            body.map(_.fields).getOrElse(Map()) ++
                            Context(HttpMethods.getForKey(method.toUpperCase).get, List(), path, Map()).metadata(Option(identity)))
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

    var failActionLookup = false // toggle to cause action lookup to fail
    var failActivation = 0 // toggle to cause action to fail

    override def afterEach() = {
        failActionLookup = false
        failActivation = 0
        actionResult = None
    }

    it should "resolve a meta package into the systemId namespace" in {
        val packageName = Await.result(resolvePackageName(EntityName("foo")), dbOpTimeout)
        packageName.fullPath.asString shouldBe s"$systemId/foo"
    }

    it should "reject unsupported http verbs" in {
        implicit val tid = transid()

        val methods = Seq((Head, MethodNotAllowed), (Patch, MethodNotAllowed))
        methods.foreach {
            case (m, code) =>
                m(s"/$routePath/partialmeta") ~> sealRoute(routes(creds)) ~> check {
                    status should be(code)
                }
        }
    }

    it should "reject access to unknown package or missing package action" in {
        implicit val tid = transid()
        val methods = Seq(Get, Post, Delete)

        methods.foreach { m =>
            m(s"/$routePath") ~> sealRoute(routes(creds)) ~> check {
                status shouldBe NotFound
            }
        }

        val paths = Seq(
            (s"/$routePath/doesntexist", NotFound),
            (s"/$routePath/notmeta", NotFound),
            (s"/$routePath/badmeta", MethodNotAllowed), // exists but has no mapping
            (s"/$routePath/bindingmeta", NotFound))

        paths.foreach {
            case ((p, s)) =>
                methods.foreach { m =>
                    m(p) ~> sealRoute(routes(creds)) ~> check {
                        withClue(p) {
                            status shouldBe s
                        }
                    }
                }
        }

        failActionLookup = true
        Get(s"/$routePath/publicmeta") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
        }
    }

    it should "invoke action for allowed verbs on meta handler" in {
        implicit val tid = transid()

        val methods = Seq((Get, "getApi"), (Post, "createRoute"), (Delete, "deleteApi"))
        methods.foreach {
            case (m, name) =>
                m(s"/$routePath/heavymeta?a=b&c=d&namespace=xyz") ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId/heavymeta".toJson,
                        "action" -> name.toJson,
                        "content" -> metaPayload(m.method.value, Map("a" -> "b", "c" -> "d", "namespace" -> "xyz"), creds))
                }
        }
    }

    it should "invoke action for allowed verbs on meta handler with partial mapping" in {
        implicit val tid = transid()

        val methods = Seq((Get, OK), (Post, MethodNotAllowed), (Delete, MethodNotAllowed))
        methods.foreach {
            case (m, code) =>
                m(s"/$routePath/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
                    status should be(code)
                    if (status == OK) {
                        val response = responseAs[JsObject]
                        response shouldBe JsObject(
                            "pkg" -> s"$systemId/partialmeta".toJson,
                            "action" -> "getApi".toJson,
                            "content" -> metaPayload(m.method.value, Map("a" -> "b", "c" -> "d"), creds))
                    }
                }
        }
    }

    it should "invoke action for allowed verbs on meta handler and pass unmatched path to action" in {
        implicit val tid = transid()

        val paths = Seq("", "/", "/foo", "/foo/bar", "/foo/bar/", "/foo%20bar")
        paths.foreach { p =>
            withClue(s"failed on path: '$p'") {
                Get(s"/$routePath/partialmeta$p?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId/partialmeta".toJson,
                        "action" -> "getApi".toJson,
                        "content" -> metaPayload("get", Map("a" -> "b", "c" -> "d"), creds, p))
                }
            }
        }
    }

    it should "invoke action that times out and provide a code" in {
        implicit val tid = transid()

        failActivation = 1
        Get(s"/$routePath/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            confirmErrorWithTid(response, Some("Response not yet ready."))
        }
    }

    it should "invoke action that errors and response with error and code" in {
        implicit val tid = transid()

        failActivation = 2
        Get(s"/$routePath/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            val response = responseAs[JsObject]
            confirmErrorWithTid(response)
        }
    }

    it should "merge package parameters with action, query params and content payload" in {
        implicit val tid = transid()
        val body = JsObject("foo" -> "bar".toJson)
        Get(s"/$routePath/packagemeta/extra/path?a=b&c=d", body) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
                "pkg" -> s"$systemId/packagemeta".toJson,
                "action" -> "getApi".toJson,
                "content" -> metaPayload(
                    "get",
                    Map("a" -> "b", "c" -> "d"),
                    creds,
                    path = "/extra/path",
                    body = Some(body),
                    pkgName = "packagemeta"))
        }
    }

    it should "reject request that defined reserved properties" in {
        implicit val tid = transid()

        val methods = Seq(Get, Post, Delete)

        methods.foreach { m =>
            WhiskMetaApi.reservedProperties.foreach { p =>
                m(s"/$routePath/packagemeta/?$p=YYY") ~> sealRoute(routes(creds)) ~> check {
                    status should be(BadRequest)
                    responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                }

                m(s"/$routePath/packagemeta", JsObject(p -> "YYY".toJson)) ~> sealRoute(routes(creds)) ~> check {
                    status should be(BadRequest)
                    responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                }
            }
        }
    }

    it should "invoke action and pass content body as string to action" in {
        implicit val tid = transid()

        val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
        Post(s"/$routePath/heavymeta?a=b&c=d", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
                "pkg" -> s"$systemId/heavymeta".toJson,
                "action" -> "createRoute".toJson,
                "content" -> metaPayload("post", Map("a" -> "b", "c" -> "d"), creds, body = Some(content)))
        }
    }

    it should "invoke action and ignore invoke parameters that are immutable" in {
        implicit val tid = transid()
        val contentX = JsObject("x" -> "overriden".toJson)
        val contentZ = JsObject("z" -> "overriden".toJson)

        Get(s"/$routePath/packagemeta?x=overriden") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/$routePath/packagemeta?y=overriden") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/$routePath/packagemeta", contentX) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/$routePath/packagemeta?y=overriden", contentZ) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }
    }

    it should "rejection invoke action when receiving entity that is not a JsObject" in {
        implicit val tid = transid()

        Post(s"/$routePath/heavymeta?a=b&c=d", "1,2,3") ~> sealRoute(routes(creds)) ~> check {
            status should be(UnsupportedMediaType)
            responseAs[String] should include("application/json")
        }

        Post(s"/$routePath/heavymeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        Post(s"/$routePath/heavymeta?a=b&c=d", JsObject()) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

    }

    it should "warn if meta package is public" in {
        implicit val tid = transid()
        val stream = new ByteArrayOutputStream
        val printstream = new PrintStream(stream)
        val savedstream = this.outputStream
        this.outputStream = printstream

        try {
            Get(s"/$routePath/publicmeta") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                stream.toString should include regex (s"""[WARN] *.*publicmeta@0.0.1' is public""")
                stream.reset()
            }
        } finally {
            stream.close()
            printstream.close()
        }
    }

    it should "allow anonymous acccess to fully qualified name" in {
        implicit val tid = transid()
        val exports = s"/$routePath/$anonymousInvokePath"

        // none of these should match a route
        Seq("a", "a/b", "/a", s"$systemId/c", s"$systemId/export_c").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(NotFound)
                }
            }

        // the first of these fails in the identity lookup,
        // the second in the package lookup (does not exist),
        // the third and fourth fail the annotation check
        Seq("guest/proxy/c", s"$systemId/doesnotexist/c", s"$systemId/publicmeta/c", s"$systemId/default/c").
            foreach { path =>
                Get(s"$exports/${path}.json") ~> sealRoute(routes()) ~> check {
                    status should be(NotFound)
                }

                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(NotAcceptable)
                    confirmErrorWithTid(responseAs[JsObject], Some(Messages.contentTypeRequired))
                }
            }

        // both of these should produce full result objects (trailing slash is ok)
        // action name starting with export_ will have required annotation
        Seq(s"$systemId/proxy/export_c.json", s"$systemId/proxy/export_c.json/").
            foreach { path =>
                val p = if (path.endsWith("/")) "/" else ""
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId/proxy".toJson,
                        "action" -> "export_c".toJson,
                        "content" -> metaPayload("get", Map(), null, p, pkgName = "proxy"))
                }
            }

        // these should match action in default package
        Seq(s"$systemId/default/export_c.json").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId".toJson,
                        "action" -> "export_c".toJson,
                        "content" -> metaPayload("get", Map(), null))
                }
            }

        // these should project a field from the result object
        Seq(s"$systemId/proxy/export_c.json/content").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe metaPayload("get", Map(), null, "/content", pkgName = "proxy")
                }
            }

        Seq(s"$systemId/proxy/export_c.json/a").
            foreach { path =>
                actionResult = Some(JsObject("a" -> JsString("b")))
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(BadRequest)
                }
            }

        // reset the action result
        actionResult = None

        // these test an http response
        Seq(s"$systemId/proxy/export_c.http/content/response").
            foreach { path =>
                val httpResponse = JsObject("response" -> JsObject("headers" -> JsObject("location" -> "http://openwhisk.org".toJson), "code" -> Found.intValue.toJson))
                Get(s"$exports/$path", httpResponse) ~> sealRoute(routes()) ~> check {
                    status should be(Found)
                    header("location").get.toString shouldBe "location: http://openwhisk.org"
                }
            }

        // these test default projection for extension
        Seq(s"$systemId/proxy/export_c.http").
            foreach { path =>
                actionResult = Some(JsObject("headers" -> JsObject("location" -> "http://openwhisk.org".toJson), "code" -> Found.intValue.toJson))
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(Found)
                    header("location").get.toString shouldBe "location: http://openwhisk.org"
                }
            }

        Seq(s"$systemId/proxy/export_c.text").
            foreach { path =>
                actionResult = Some(JsObject("text" -> JsString("default text")))
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[String]
                    response shouldBe "default text"
                }
            }

        Seq(s"$systemId/proxy/export_c.json").
            foreach { path =>
                actionResult = Some(JsObject("foobar" -> JsString("foobar")))
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe actionResult.get
                }
            }

        Seq(s"$systemId/proxy/export_c.html").
            foreach { path =>
                actionResult = Some(JsObject("html" -> JsString("<html>hi</htlml>")))
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[String]
                    response shouldBe "<html>hi</htlml>"
                }
            }

        // http web action with base64 encoded response
        Seq(s"$systemId/proxy/export_c.http").
            foreach { path =>
                actionResult = Some(JsObject(
                    "headers" -> JsObject(
                        "content-type" -> "application/json".toJson),
                    "code" -> OK.intValue.toJson,
                    "body" -> Base64.getEncoder.encodeToString {
                        JsObject("field" -> "value".toJson).compactPrint.getBytes
                    }.toJson))

                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    header("content-type").get.toString shouldBe "content-type: application/json"
                    responseAs[JsObject] shouldBe JsObject("field" -> "value".toJson)
                }
            }

        // http web action with text response
        Seq(s"$systemId/proxy/export_c.http").
            foreach { path =>
                actionResult = Some(JsObject(
                    "code" -> OK.intValue.toJson,
                    "body" -> "hello world".toJson))

                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    println(responseAs[String])
                    status should be(OK)
                    responseAs[String] shouldBe "hello world"
                }
            }

        // http web action with mimatch between header and response
        Seq(s"$systemId/proxy/export_c.http").
            foreach { path =>
                actionResult = Some(JsObject(
                    "headers" -> JsObject(
                        "content-type" -> "application/json".toJson),
                    "code" -> OK.intValue.toJson,
                    "body" -> "hello world".toJson))

                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(BadRequest)
                    confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpContentTypeError))
                }
            }

        // http web action with unknown header
        Seq(s"$systemId/proxy/export_c.http").
            foreach { path =>
                actionResult = Some(JsObject(
                    "headers" -> JsObject(
                        "content-type" -> "xyz/bar".toJson),
                    "code" -> OK.intValue.toJson,
                    "body" -> "hello world".toJson))

                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(BadRequest)
                    confirmErrorWithTid(responseAs[JsObject], Some(Messages.httpUnknownContentType))
                }
            }

        // reset the action result
        actionResult = None

        Seq(s"$systemId/proxy/export_c.text/content/field1", s"$systemId/proxy/export_c.text/content/field2").
            foreach { path =>
                val form = FormData(Seq("field1" -> "value1", "field2" -> "value2"))
                Post(s"$exports/$path", form) ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    responseAs[String] should (be("value1") or be("value2"))
                }
            }

        Seq(s"$systemId/proxy/export_c.text/content/z", s"$systemId/proxy/export_c.text/content/z/", s"$systemId/proxy/export_c.text/content/z//").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(OK)
                    val response = responseAs[String]
                    response shouldBe "Z"
                }
            }

        // these should fail because parameter override is not allowed
        // ?x=overriden
        Seq(s"$systemId/proxy/export_c.text/content/z?x=overriden").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(BadRequest)
                    responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                }
            }

        // these fail to project a field from the result object (doesn't exist)
        Seq(s"$systemId/proxy/export_c.text/foobar", s"$systemId/proxy/export_c.text/content/z/x").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(NotFound)
                    confirmErrorWithTid(responseAs[JsObject], Some(Messages.propertyNotFound))
                }
            }

        // these fail with unsupported extension
        Seq(s"$systemId/proxy/export_c.xyz", s"$systemId/proxy/export_c.xyz/", s"$systemId/proxy/export_c.xyz/content").
            foreach { path =>
                Get(s"$exports/$path") ~> sealRoute(routes()) ~> check {
                    status should be(NotAcceptable)
                    confirmErrorWithTid(responseAs[JsObject], Some(Messages.contentTypeNotSupported))
                }
            }
    }
}
