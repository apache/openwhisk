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
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.event.Logging.InfoLevel
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json._
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.controller.WhiskMetaApi
import whisk.core.entity._
import whisk.core.database.NoDocumentException
import org.scalatest.BeforeAndAfterEach

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

    val subject = Subject()
    override val systemId = subject()
    override lazy val systemKey = Future.successful(WhiskAuth(subject, AuthKey()).toIdentity)

    /** Meta API tests */
    behavior of "Meta API"

    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    setVerbosity(InfoLevel)

    val packages = Seq(
        WhiskPackage(
            EntityPath(systemId),
            EntityName("notmeta"),
            annotations = Parameters("meta", JsBoolean(false))),
        WhiskPackage(
            EntityPath(systemId),
            EntityName("badmeta"),
            annotations = Parameters("meta", JsBoolean(true))),
        WhiskPackage(
            EntityPath(systemId),
            EntityName("heavymeta"),
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi")) ++
                Parameters("post", JsString("createRoute")) ++
                Parameters("delete", JsString("deleteApi"))),
        WhiskPackage(
            EntityPath(systemId),
            EntityName("partialmeta"),
            parameters = Parameters("x", JsString("X")) ++ Parameters("z", JsString("z")),
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi"))),
        WhiskPackage(
            EntityPath(systemId),
            EntityName("publicmeta"),
            publish = true,
            annotations = Parameters("meta", JsBoolean(true)) ++
                Parameters("get", JsString("getApi"))))

    override protected[controller] def invokeAction(user: Identity, action: WhiskAction, payload: Option[JsObject], blocking: Boolean, waitOverride: Boolean = false)(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        val result = JsObject("pkg" -> action.namespace.toJson, "action" -> action.name.toJson, "content" -> payload.get)
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
        pkgLookup(action.namespace.last) map { pkg =>
            action.parameters shouldBe (pkg.parameters ++ defaultActionParameters)
            action.parameters("z") shouldBe defaultActionParameters("z")
        }

        Future.successful(activation.activationId, Some(activation))
    }

    override protected def pkgLookup(pkgName: EntityName)(
        implicit transid: TransactionId) = {
        Future {
            packages.filter(_.name == pkgName).head
        } recoverWith {
            case t => Future.failed(NoDocumentException("doesn't exist"))
        }
    }

    val defaultActionParameters = Parameters("y", JsString("Y")) ++ Parameters("z", JsString("Z"))

    override protected def actionLookup(pkgName: EntityName, actionName: EntityName)(
        implicit transid: TransactionId) = {
        if (!failActionLookup) {
            Future.successful {
                WhiskAction(pkgName.toPath, actionName, Exec.js("??"), defaultActionParameters)
            }
        } else {
            Future.failed(NoDocumentException("doesn't exist"))
        }
    }

    var failActionLookup = false // toggle to cause action lookup to fail

    override def afterEach() = {
        failActionLookup = false
    }

    it should "reject access to unknown package or missing package action" in {
        implicit val tid = transid()
        val methods = Seq(Get, Post, Delete)

        methods.map { m =>
            m("/experimental") ~> sealRoute(routes(creds)) ~> check {
                status shouldBe NotFound
            }
        }

        val paths = Seq("/experimental/doesntexist", "/experimental/notmeta", "/experimental/badmeta")
        paths.map { p =>
            methods.map { m =>
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
        methods.map {
            case (m, name) =>
                m("/experimental/heavymeta?a=b&c=d&namespace=xyz") ~> sealRoute(routes(creds)) ~> check {
                    status should be(OK)
                    val response = responseAs[JsObject]
                    response shouldBe JsObject(
                        "pkg" -> "heavymeta".toJson,
                        "action" -> name.toJson,
                        "content" -> JsObject(
                            "namespace" -> creds.namespace.toJson, //namespace overriden by API
                            "a" -> "b".toJson,
                            "c" -> "d".toJson))
                }
        }
    }

    it should "invoke action for allowed verbs on meta handler with partial mapping" in {
        implicit val tid = transid()

        val methods = Seq((Get, OK), (Post, MethodNotAllowed), (Delete, MethodNotAllowed))
        methods.map {
            case (m, status) =>
                m("/experimental/partialmeta?a=b&c=d&namespace=xyz") ~> sealRoute(routes(creds)) ~> check {
                    status should be(status)
                    if (status == OK) {
                        val response = responseAs[JsObject]
                        response shouldBe JsObject(
                            "pkg" -> "partialmeta".toJson,
                            "action" -> "getApi".toJson,
                            "content" -> JsObject(
                                "namespace" -> creds.namespace.toJson, //namespace overriden by API
                                "a" -> "b".toJson,
                                "c" -> "d".toJson))
                    }
                }
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
