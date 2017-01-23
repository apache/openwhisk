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

import scala.concurrent.Future

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.InfoLevel
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.controller.WhiskMetaApi
import whisk.core.database.NoDocumentException
import whisk.core.entity._
import whisk.http.ErrorResponse
import whisk.http.Messages
import scala.concurrent.Await
import whisk.core.entitlement.Privilege
import scala.concurrent.duration.FiniteDuration

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
            annotations = Parameters("meta", JsBoolean(true))))

    override protected[controller] def invokeAction(user: Identity, action: WhiskAction, payload: Option[JsObject], blocking: Boolean, waitOverride: Option[FiniteDuration] = None)(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        if (failActivation == 0) {
            // construct a result stub that includes:
            // 1. the package name for the action (to confirm that this resolved to systemId)
            // 2. the action name (to confirm that this resolved to the expected meta action)
            // 3. the payload received by the action which consists of the action.params + payload
            val result = JsObject(
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
            pkgLookup(packageName) foreach { pkg =>
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

    protected def pkgLookup(pkgName: String) = packages.find(_.name == EntityName(pkgName))

    override protected def pkgLookup(pkgName: FullyQualifiedEntityName)(
        implicit transid: TransactionId) = {
        Future {
            packages.find(_.name == pkgName.name).get
        } recoverWith {
            case t => Future.failed(NoDocumentException("doesn't exist"))
        }
    }

    val defaultActionParameters = {
        Parameters("y", JsString("Y")) ++ Parameters("z", JsString("Z"))
    }

    override protected def actionLookup(pkgName: FullyQualifiedEntityName, actionName: EntityName)(
        implicit transid: TransactionId) = {
        if (!failActionLookup) {
            Future.successful {
                val annotations = Parameters(WhiskAction.finalParamsAnnotationName, JsBoolean(true))
                WhiskAction(pkgName.fullPath, actionName, Exec.js("??"), defaultActionParameters, annotations = annotations)
            }
        } else {
            Future.failed(NoDocumentException("doesn't exist"))
        }
    }

    def metaPayload(method: String, params: Map[String, String], namespace: String, path: String = "", body: Option[JsObject] = None, pkg: Option[WhiskPackage] = None) = {
        (pkg.map(_.parameters).getOrElse(Parameters()) ++ defaultActionParameters).merge {
            Some {
                JsObject(
                    params.toJson.asJsObject.fields ++
                        body.map(_.fields).getOrElse(Map()) ++
                        Map("__ow_meta_verb" -> method.toLowerCase.toJson,
                            "__ow_meta_path" -> path.toJson,
                            "__ow_meta_namespace" -> namespace.toJson))
            }
        }.get
    }

    var failActionLookup = false // toggle to cause action lookup to fail
    var failActivation = 0 // toggle to cause action to fail

    override def afterEach() = {
        failActionLookup = false
        failActivation = 0
    }

    it should "resolve a meta package into the systemId namespace" in {
        val packageName = Await.result(resolvePackageName(EntityName("foo")), dbOpTimeout)
        packageName.fullPath.asString shouldBe s"$systemId/foo"
    }

    it should "reject unsupported http verbs" in {
        implicit val tid = transid()

        val methods = Seq((Put, MethodNotAllowed))
        methods.foreach {
            case (m, code) =>
                m("/experimental/partialmeta") ~> sealRoute(routes(creds)) ~> check {
                    status should be(code)
                }
        }
    }

    it should "reject access to unknown package or missing package action" in {
        implicit val tid = transid()
        val methods = Seq(Get, Post, Delete)

        methods.foreach { m =>
            m("/experimental") ~> sealRoute(routes(creds)) ~> check {
                status shouldBe NotFound
            }
        }

        val paths = Seq(
            "/experimental/doesntexist",
            "/experimental/notmeta",
            "/experimental/badmeta",
            "/experimental/bindingmeta")

        paths.foreach { p =>
            methods.foreach { m =>
                m(p) ~> sealRoute(routes(creds)) ~> check {
                    withClue(p) {
                        status shouldBe MethodNotAllowed
                    }
                }
            }
        }

        failActionLookup = true
        Get("/experimental/publicmeta") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
        }
    }

    it should "invoke action for allowed verbs on meta handler" in {
        implicit val tid = transid()

        val methods = Seq((Get, "getApi"), (Post, "createRoute"), (Delete, "deleteApi"))
        methods.foreach {
            case (m, name) =>
                m("/experimental/heavymeta?a=b&c=d&namespace=xyz") ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId/heavymeta".toJson,
                        "action" -> name.toJson,
                        "content" -> metaPayload(m.method.value, Map("a" -> "b", "c" -> "d", "namespace" -> "xyz"), creds.namespace.name))
                }
        }
    }

    it should "invoke action for allowed verbs on meta handler with partial mapping" in {
        implicit val tid = transid()

        val methods = Seq((Get, OK), (Post, MethodNotAllowed), (Delete, MethodNotAllowed))
        methods.foreach {
            case (m, code) =>
                m("/experimental/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
                    status should be(code)
                    if (status == OK) {
                        val response = responseAs[JsObject]
                        response shouldBe JsObject(
                            "pkg" -> s"$systemId/partialmeta".toJson,
                            "action" -> "getApi".toJson,
                            "content" -> metaPayload(m.method.value, Map("a" -> "b", "c" -> "d"), creds.namespace.name))
                    }
                }
        }
    }

    it should "invoke action for allowed verbs on meta handler and pass unmatched path to action" in {
        implicit val tid = transid()

        val paths = Seq("", "/", "/foo", "/foo/bar", "/foo/bar/", "/foo%20bar")
        paths.foreach { p =>
            withClue(s"failed on path: '$p'") {
                Get(s"/experimental/partialmeta$p?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> s"$systemId/partialmeta".toJson,
                        "action" -> "getApi".toJson,
                        "content" -> metaPayload("get", Map("a" -> "b", "c" -> "d"), creds.namespace.name, p))
                }
            }
        }
    }

    it should "invoke action that times out and provide a code" in {
        implicit val tid = transid()

        failActivation = 1
        Get(s"/experimental/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(Accepted)
            val response = responseAs[JsObject]
            response.fields.size shouldBe 1
            response.fields.get("code") shouldBe defined
            response.fields.get("code").get shouldBe an[JsNumber]
        }
    }

    it should "invoke action that errors and response with error and code" in {
        implicit val tid = transid()

        failActivation = 2
        Get(s"/experimental/partialmeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            val response = responseAs[JsObject]
            response.fields.size shouldBe 2
            response.fields.get("error") shouldBe defined
            response.fields.get("code") shouldBe defined
            response.fields.get("code").get shouldBe an[JsNumber]
        }
    }

    it should "merge package parameters with action, query params and content payload" in {
        implicit val tid = transid()

        val body = JsObject("foo" -> "bar".toJson)
        Get("/experimental/packagemeta/extra/path?a=b&c=d", body) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
                "pkg" -> s"$systemId/packagemeta".toJson,
                "action" -> "getApi".toJson,
                "content" -> metaPayload(
                    "get",
                    Map("a" -> "b", "c" -> "d"),
                    creds.namespace.name,
                    path = "/extra/path",
                    body = Some(body),
                    pkg = pkgLookup("packagemeta")))
        }
    }

    it should "reject request that defined reserved properties" in {
        implicit val tid = transid()

        val methods = Seq(Get, Post, Delete)

        methods.foreach { m =>
            reservedProperties.foreach { p =>
                m(s"/experimental/packagemeta/?$p=YYY") ~> sealRoute(routes(creds)) ~> check {
                    status should be(BadRequest)
                    responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                }

                m("/experimental/packagemeta", JsObject(p -> "YYY".toJson)) ~> sealRoute(routes(creds)) ~> check {
                    status should be(BadRequest)
                    responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
                }
            }
        }
    }

    it should "invoke action and pass content body as string to action" in {
        implicit val tid = transid()

        val content = JsObject("extra" -> "read all about it".toJson, "yummy" -> true.toJson)
        Post(s"/experimental/heavymeta?a=b&c=d", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[JsObject]
            response shouldBe JsObject(
                "pkg" -> s"$systemId/heavymeta".toJson,
                "action" -> "createRoute".toJson,
                "content" -> metaPayload("post", Map("a" -> "b", "c" -> "d"), creds.namespace.name, body = Some(content)))
        }
    }

    it should "invoke action and ignore invoke parameters that are immutable" in {
        implicit val tid = transid()
        val contentX = JsObject("x" -> "overriden".toJson)
        val contentZ = JsObject("z" -> "overriden".toJson)

        Get(s"/experimental/packagemeta?x=overriden") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/experimental/packagemeta?y=overriden") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/experimental/packagemeta", contentX) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }

        Get(s"/experimental/packagemeta?y=overriden", contentZ) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.parametersNotAllowed
        }
    }

    it should "rejection invoke action when receiving entity that is not a JsObject" in {
        implicit val tid = transid()

        Post(s"/experimental/heavymeta?a=b&c=d", "1,2,3") ~> sealRoute(routes(creds)) ~> check {
            status should be(UnsupportedMediaType)
            responseAs[String] should include("application/json")
        }

        Post(s"/experimental/heavymeta?a=b&c=d") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }

        Post(s"/experimental/heavymeta?a=b&c=d", JsObject()) ~> sealRoute(routes(creds)) ~> check {
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
            Get("/experimental/publicmeta") ~> sealRoute(routes(creds)) ~> check {
                status should be(OK)
                stream.toString should include regex (s"""[WARN] *.*publicmeta@0.0.1' is public""")
                stream.reset()
            }
        } finally {
            stream.close()
            printstream.close()
        }
    }

}
