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

import scala.concurrent.Await

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.http.BasicHttpCredentials
import spray.http.StatusCodes._
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import whisk.common.TransactionCounter
import whisk.core.controller.Authenticate
import whisk.core.controller.AuthenticatedRoute
import whisk.core.entity._
import whisk.http.BasicHttpService

/**
 * Tests authentication handler which guards API.
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
class AuthenticateTests extends ControllerTestCommon with Authenticate {
    behavior of "Authenticate"

    it should "authorize a known user" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1
        val pass = UserPass(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user.get should be(creds)
    }

    it should "authorize a known user from cache" in {
        val creds = createTempCredentials(transid())._1
        val pass = UserPass(creds.authkey.uuid.asString, creds.authkey.key.asString)

        // first query will be served from datastore
        val user = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
        user.get should be(creds)
        stream.toString should include regex (s"serving from datastore: ${creds.authkey.uuid.asString}")
        stream.reset()

        // repeat query, should be served from cache
        val cachedUser = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
        cachedUser.get should be(creds)
        stream.toString should include regex (s"serving from cache: ${creds.authkey.uuid.asString}")
        stream.reset()
    }

    it should "not authorize a known user with an invalid key" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1
        val pass = UserPass(creds.authkey.uuid.asString, Secret().asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize an unknown user" in {
        implicit val tid = transid()
        val creds = WhiskAuth(Subject(), AuthKey())
        val pass = UserPass(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when no user creds are provided" in {
        implicit val tid = transid()
        val user = Await.result(validateCredentials(None), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed user is provided" in {
        implicit val tid = transid()
        val pass = UserPass("x", Secret().asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed secret is provided" in {
        implicit val tid = transid()
        val pass = UserPass(UUID().asString, "x")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed creds are provided" in {
        implicit val tid = transid()
        val pass = UserPass("x", "y")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }
}

class AuthenticatedRouteTests
    extends ControllerTestCommon
    with Authenticate
    with AuthenticatedRoute
    with TransactionCounter {

    behavior of "Authenticated Route"

    val route = sealRoute {
        implicit val tid = transid()
        handleRejections(BasicHttpService.customRejectionHandler) {
            path("secured") {
                authenticate(basicauth) {
                    user => complete("ok")
                }
            }

        }
    }

    it should "authorize a known user" in {
        val creds = createTempCredentials(transid())._1
        val validCredentials = BasicHttpCredentials(creds.authkey.uuid.asString, creds.authkey.key.asString)
        Get("/secured") ~> addCredentials(validCredentials) ~> route ~> check {
            status should be(OK)
        }
    }

    it should "not authorize an unknown user" in {
        val invalidCredentials = BasicHttpCredentials(UUID().asString, Secret().asString)
        Get("/secured") ~> addCredentials(invalidCredentials) ~> route ~> check {
            status should be(Unauthorized)
        }
    }

    ignore should "report service unavailable when db lookup fails" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1

        // force another key for the same uuid to cause an internal violation
        val secondCreds = WhiskAuth(Subject(), AuthKey(creds.authkey.uuid, Secret()))
        put(authStore, secondCreds)
        waitOnView(authStore, creds.authkey, 2)

        val invalidCredentials = BasicHttpCredentials(creds.authkey.uuid.asString, creds.authkey.key.asString)
        Get("/secured") ~> addCredentials(invalidCredentials) ~> route ~> check {
            status should be(InternalServerError)
        }
    }

}
