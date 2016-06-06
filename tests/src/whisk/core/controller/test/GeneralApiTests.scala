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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.http.AllOrigins
import spray.http.HttpHeaders.`Access-Control-Allow-Origin`
import spray.http.HttpHeaders.`Access-Control-Allow-Headers`
import spray.http.StatusCodes.NotFound
import whisk.common.Verbosity
import whisk.core.controller.RestAPIVersion_v1

/**
 * Tests the API in general
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * These tests differ from the more specific tests in that they make calls to the
 * outermost routes of the controller.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class GeneralApiTests extends ControllerTestCommon {

    val api = new RestAPIVersion_v1(config, Verbosity.Loud, actorSystem, actorSystem.dispatcher)

    val originHeader = `Access-Control-Allow-Origin`(AllOrigins)
    val headersHeader = `Access-Control-Allow-Headers`("Authorization", "Content-Type")

    behavior of "General API"

    it should "respond to options properly" in {
        implicit val tid = transid()
        Options("/api/v1") ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
    }

    it should "respond to options on every route under /api/v1" in {
        implicit val tid = transid()
        Options("/api/v1/namespaces/_/actions") ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
    }

    it should "respond to options even on bogus routes under /api/v1" in {
        implicit val tid = transid()
        Options("/api/v1/bogus") ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
    }

    it should "not respond to options on routes before /api/v1" in {
        implicit val tid = transid()
        Options("/api") ~> sealRoute(api.routes(tid)) ~> check {
            status shouldBe NotFound
        }
    }

    it should "respond with cors headers on general api calls" in {
        implicit val tid = transid()
        val creds = createTempCredentials._2
        Get("/api/v1/namespaces/_/actions") ~> addCredentials(creds) ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
        Get("/api/v1/namespaces/_/rules") ~> addCredentials(creds) ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
        Get("/api/v1/namespaces/_/triggers") ~> addCredentials(creds) ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
        Get("/api/v1/namespaces/_/packages") ~> addCredentials(creds) ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
        Get("/api/v1/namespaces/") ~> addCredentials(creds) ~> sealRoute(api.routes(tid)) ~> check {
            headers should contain allOf (originHeader, headersHeader)
        }
    }

}
