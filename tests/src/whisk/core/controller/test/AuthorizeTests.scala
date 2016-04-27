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

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import scala.concurrent.Future
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.MethodNotAllowed
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.RequestTimeout
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.controller.Authenticate
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import spray.routing.authentication.UserPass
import scala.concurrent.Await
import whisk.core.entity.Secret
import whisk.common.Verbosity
import whisk.core.entity.UUID
import whisk.core.entity.Namespace
import whisk.core.entitlement.Collection._
import whisk.core.entitlement.Resource
import whisk.core.entitlement.Privilege._
import scala.language.postfixOps

/**
 * Tests authorization handler which guards resources.
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
class AuthorizeTests extends ControllerTestCommon with Authenticate {

    behavior of "Authorize"

    val requestTimeout = 1 second
    val someUser = Subject()
    val adminUser = Subject("admin")
    val guestUser = Subject("anonym")

    it should "authorize a user to only read from their collection" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
        val resources = collections map { Resource(someUser.namespace, _, None) }
        resources foreach { r =>
            Await.result(entitlementService.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(someUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(someUser, REJECT, r), requestTimeout) should be(false)
        }
    }

    it should "not authorize a user to list someone else's collection or access it by other other right" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
        val resources = collections map { Resource(someUser.namespace, _, None) }
        resources foreach { r =>
            // it is permissible to list packages in any namespace (provided they are either owned by
            // the subject requesting access or the packages are public); that is, the entitlement is more
            // fine grained and applies to public vs private private packages (hence permit READ on PACKAGES to
            // be true
            Await.result(entitlementService.check(guestUser, READ, r), requestTimeout) should be(r.collection == PACKAGES)
            Await.result(entitlementService.check(guestUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, ACTIVATE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, REJECT, r), requestTimeout) should be(false)
        }
    }

    it should "authorize a user to CRUD or activate (if supported) an entity in a collection" in {
        implicit val tid = transid()
        // packages are tested separately
        val collections = Seq(ACTIONS, RULES, TRIGGERS)
        val resources = collections map { Resource(someUser.namespace, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementService.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, PUT, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, DELETE, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, ACTIVATE, r), requestTimeout) should be(true)
        }
    }

    it should "not authorize a user to CRUD or activate an entity in a collection that does not support CRUD or activate" in {
        implicit val tid = transid()
        val collections = Seq(NAMESPACES, ACTIVATIONS)
        val resources = collections map { Resource(someUser.namespace, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementService.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(someUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "not authorize a user to CRUD or activate an entity in someone else's collection" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES)
        val resources = collections map { Resource(someUser.namespace, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementService.check(guestUser, READ, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementService.check(guestUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "authorize a user to list, create/update/delete a package" in {
        implicit val tid = transid()
        val collections = Seq(PACKAGES)
        val resources = collections map { Resource(someUser.namespace, _, Some("xyz")) }
        resources foreach { r =>
            // read should fail because the lookup for the package will fail
            Await.result(entitlementService.check(someUser, READ, r), requestTimeout) should be(false)
            // create/put/delete should be allowed
            Await.result(entitlementService.check(someUser, PUT, r), requestTimeout) should be(true)
            Await.result(entitlementService.check(someUser, DELETE, r), requestTimeout) should be(true)
            // activate is not allowed on a package
            Await.result(entitlementService.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "grant access to entire collection to another user" in {
        implicit val tid = transid()
        val all = Resource(someUser.namespace, ACTIONS, None)
        val one = Resource(someUser.namespace, ACTIONS, Some("xyz"))
        Await.result(entitlementService.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementService.check(adminUser, READ, one), requestTimeout) should not be (true)
        Await.result(entitlementService.grant(adminUser, READ, all), requestTimeout) // granted
        Await.result(entitlementService.check(adminUser, READ, all), requestTimeout) should be(true)
        Await.result(entitlementService.check(adminUser, READ, one), requestTimeout) should be(true)
        Await.result(entitlementService.revoke(adminUser, READ, all), requestTimeout) // revoked
    }

    it should "grant access to specific resource to a user" in {
        implicit val tid = transid()
        val all = Resource(someUser.namespace, ACTIONS, None)
        val one = Resource(someUser.namespace, ACTIONS, Some("xyz"))
        Await.result(entitlementService.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementService.check(adminUser, READ, one), requestTimeout) should not be (true)
        Await.result(entitlementService.check(adminUser, DELETE, one), requestTimeout) should not be (true)
        Await.result(entitlementService.grant(adminUser, READ, one), requestTimeout) // granted
        Await.result(entitlementService.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementService.check(adminUser, READ, one), requestTimeout) should be(true)
        Await.result(entitlementService.check(adminUser, DELETE, one), requestTimeout) should not be (true)
        Await.result(entitlementService.revoke(adminUser, READ, one), requestTimeout) // revoked
    }
}
