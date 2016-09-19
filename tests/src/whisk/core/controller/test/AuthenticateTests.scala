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

import scala.concurrent.Await

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.http.BasicHttpCredentials
import spray.http.StatusCodes._
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.authentication.UserPass
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import whisk.common.TransactionCounter
import whisk.core.controller.Authenticate
import whisk.core.controller.AuthenticatedRoute
import whisk.core.entity.AuthKey
import whisk.core.entity.DocId
import whisk.core.entity.Secret
import whisk.core.entity.Subject
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAuth
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
        val pass = UserPass(creds.uuid(), creds.key())
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user.get should be(creds.toIdentity)
    }

    it should "authorize a known user from cache" in {
        val creds = createTempCredentials(transid())._1
        val pass = UserPass(creds.uuid(), creds.key())

        // first query will be served from datastore
        val stream = new ByteArrayOutputStream
        val printstream = new PrintStream(stream)
        val savedstream = authStore.outputStream
        authStore.outputStream = printstream
        try {
            val user = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
            user.get should be(creds.toIdentity)
            stream.toString should include regex (s"serving from datastore: ${creds.uuid()}")
            stream.reset()

            // repeat query, should be served from cache
            val cachedUser = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
            cachedUser.get should be(creds.toIdentity)
            stream.toString should include regex (s"serving from cache: ${creds.uuid()}")
            stream.reset()

            // revoke key and invalidate cache
            val newCreds = {
                implicit val tid = transid()
                val newCreds = creds.revoke
                val prevRecord = get(authStore, DocId(creds.subject()), WhiskAuth, false)
                Await.result(WhiskAuth.put(authStore, newCreds.revision[WhiskAuth](prevRecord.docinfo.rev)), dbOpTimeout)
                newCreds
            }
            stream.toString should include regex (s"invalidating*.*${creds.uuid()}")
            stream.toString should include regex (s"caching*.*${creds.uuid()}")
            stream.reset()

            // repeat query, should be served from cache correctly
            val newPass = UserPass(newCreds.uuid(), newCreds.key())
            val refetchedUser = Await.result(validateCredentials(Some(newPass))(transid()), dbOpTimeout)
            stream.toString should include regex (s"serving from cache: ${creds.uuid()}")
            refetchedUser.isDefined should be(true)
            refetchedUser.get should be(newCreds.toIdentity)
        } finally {
            authStore.outputStream = savedstream
            stream.close()
            printstream.close()
        }
    }

    it should "not authorize a known user with an invalid key" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1
        val pass = UserPass(creds.uuid(), Secret().toString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize an unknown user" in {
        implicit val tid = transid()
        val creds = WhiskAuth(Subject(), AuthKey())
        val pass = UserPass(creds.authkey.uuid(), creds.authkey.key())
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
        val pass = UserPass("x", Secret().toString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed secret is provided" in {
        implicit val tid = transid()
        val pass = UserPass(UUID().toString, "x")
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
        val validCredentials = BasicHttpCredentials(creds.uuid(), creds.key())
        Get("/secured") ~> addCredentials(validCredentials) ~> route ~> check {
            status should be(OK)
        }
    }

    it should "not authorize an unknown user" in {
        val invalidCredentials = BasicHttpCredentials(UUID().toString, Secret().toString)
        Get("/secured") ~> addCredentials(invalidCredentials) ~> route ~> check {
            status should be(Unauthorized)
        }
    }

    it should "report service unavailable when db lookup fails" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1

        // force another key for the same uuid to cause an internal violation
        val secondCreds = WhiskAuth(Subject(), AuthKey(creds.uuid, Secret()))
        put(authStore, secondCreds)
        waitOnView(authStore, creds.uuid, 2)

        val invalidCredentials = BasicHttpCredentials(creds.uuid(), creds.key())
        Get("/secured") ~> addCredentials(invalidCredentials) ~> route ~> check {
            status should be(InternalServerError)
        }
    }

}
