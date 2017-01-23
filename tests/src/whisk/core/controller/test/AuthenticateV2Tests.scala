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

import spray.routing.authentication.UserPass
import whisk.core.controller.Authenticate
import whisk.core.entity.AuthKey
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAuthV2
import whisk.core.entity.WhiskNamespace
import whisk.core.entitlement.Privilege
import whisk.core.entity.Identity

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
class AuthenticateV2Tests extends ControllerTestCommon with Authenticate {

    // Creates a new unique name each time its called
    def aname = MakeName.next("authenticatev2_tests")

    behavior of "Authenticate V2"

    it should "authorize a known user using the new database schema in different namespaces" in {
        implicit val tid = transid()
        val subject = Subject()

        val namespaces = Set(
            WhiskNamespace(aname, AuthKey()),
            WhiskNamespace(aname, AuthKey()))

        val entry = WhiskAuthV2(subject, namespaces)

        put(authStoreV2, entry)

        // Try to login with each specific namespace
        namespaces.foreach { ns =>
            println(s"Trying to login to $ns")
            val pass = UserPass(ns.authkey.uuid.asString, ns.authkey.key.asString)
            val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
            user.get shouldBe Identity(subject, ns.name, ns.authkey, Privilege.ALL)
        }
    }
}
