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
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import whisk.core.controller.Authenticate
import whisk.core.controller.RejectRequest
import whisk.core.entitlement._
import whisk.core.entitlement.Privilege._
import whisk.core.entity._

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
    val someUser = Subject().toIdentity(AuthKey())
    val adminUser = Subject("admin").toIdentity(AuthKey())
    val guestUser = Subject("anonym").toIdentity(AuthKey())

    it should "authorize a user to only read from their collection" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
        val resources = collections map { Resource(someUser.namespace.toPath, _, None) }
        resources foreach { r =>
            Await.result(entitlementProvider.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(someUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(someUser, REJECT, r), requestTimeout) should be(false)
        }
    }

    it should "not authorize a user to list someone else's collection or access it by other other right" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
        val resources = collections map { Resource(someUser.namespace.toPath, _, None) }
        resources foreach { r =>
            // it is permissible to list packages in any namespace (provided they are either owned by
            // the subject requesting access or the packages are public); that is, the entitlement is more
            // fine grained and applies to public vs private private packages (hence permit READ on PACKAGES to
            // be true
            Await.result(entitlementProvider.check(guestUser, READ, r), requestTimeout) should be(r.collection == PACKAGES)
            Await.result(entitlementProvider.check(guestUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, ACTIVATE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, REJECT, r), requestTimeout) should be(false)
        }
    }

    it should "authorize a user to CRUD or activate (if supported) an entity in a collection" in {
        implicit val tid = transid()
        // packages are tested separately
        val collections = Seq(ACTIONS, RULES, TRIGGERS)
        val resources = collections map { Resource(someUser.namespace.toPath, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementProvider.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, PUT, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, DELETE, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout) should be(true)
        }
    }

    it should "not authorize a user to CRUD an entity in a collection if authkey has no CRUD rights" in {
        implicit val tid = transid()
        val subject = Subject()
        val someUser = Identity(subject, EntityName(subject()), AuthKey(), Set(Privilege.ACTIVATE))
        val collections = Seq(ACTIONS, RULES, TRIGGERS)
        val resources = collections map { Resource(someUser.namespace.toPath, _, Some("xyz")) }
        resources foreach { r =>
            a[RejectRequest] should be thrownBy {
                Await.result(entitlementProvider.check(someUser, READ, r), requestTimeout)
            }
            a[RejectRequest] should be thrownBy {
                Await.result(entitlementProvider.check(someUser, PUT, r), requestTimeout)
            }
            a[RejectRequest] should be thrownBy {
                Await.result(entitlementProvider.check(someUser, DELETE, r), requestTimeout)
            }
            Await.result(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout) should be(true)
        }
    }

    it should "not authorize a user to CRUD or activate an entity in a collection that does not support CRUD or activate" in {
        implicit val tid = transid()
        val collections = Seq(NAMESPACES, ACTIVATIONS)
        val resources = collections map { Resource(someUser.namespace.toPath, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementProvider.check(someUser, READ, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(someUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "not authorize a user to CRUD or activate an entity in someone else's collection" in {
        implicit val tid = transid()
        val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES)
        val resources = collections map { Resource(someUser.namespace.toPath, _, Some("xyz")) }
        resources foreach { r =>
            Await.result(entitlementProvider.check(guestUser, READ, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, PUT, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, DELETE, r), requestTimeout) should be(false)
            Await.result(entitlementProvider.check(guestUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "authorize a user to list, create/update/delete a package" in {
        implicit val tid = transid()
        val collections = Seq(PACKAGES)
        val resources = collections map { Resource(someUser.namespace.toPath, _, Some("xyz")) }
        resources foreach { r =>
            a[RejectRequest] should be thrownBy {
                // read should fail because the lookup for the package will fail
                Await.result(entitlementProvider.check(someUser, READ, r), requestTimeout)
            }
            // create/put/delete should be allowed
            Await.result(entitlementProvider.check(someUser, PUT, r), requestTimeout) should be(true)
            Await.result(entitlementProvider.check(someUser, DELETE, r), requestTimeout) should be(true)
            // activate is not allowed on a package
            Await.result(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout) should be(false)
        }
    }

    it should "grant access to entire collection to another user" in {
        implicit val tid = transid()
        val all = Resource(someUser.namespace.toPath, ACTIONS, None)
        val one = Resource(someUser.namespace.toPath, ACTIONS, Some("xyz"))
        Await.result(entitlementProvider.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementProvider.check(adminUser, READ, one), requestTimeout) should not be (true)
        Await.result(entitlementProvider.grant(adminUser.subject, READ, all), requestTimeout) // granted
        Await.result(entitlementProvider.check(adminUser, READ, all), requestTimeout) should be(true)
        Await.result(entitlementProvider.check(adminUser, READ, one), requestTimeout) should be(true)
        Await.result(entitlementProvider.revoke(adminUser.subject, READ, all), requestTimeout) // revoked
    }

    it should "grant access to specific resource to a user" in {
        implicit val tid = transid()
        val all = Resource(someUser.namespace.toPath, ACTIONS, None)
        val one = Resource(someUser.namespace.toPath, ACTIONS, Some("xyz"))
        Await.result(entitlementProvider.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementProvider.check(adminUser, READ, one), requestTimeout) should not be (true)
        Await.result(entitlementProvider.check(adminUser, DELETE, one), requestTimeout) should not be (true)
        Await.result(entitlementProvider.grant(adminUser.subject, READ, one), requestTimeout) // granted
        Await.result(entitlementProvider.check(adminUser, READ, all), requestTimeout) should not be (true)
        Await.result(entitlementProvider.check(adminUser, READ, one), requestTimeout) should be(true)
        Await.result(entitlementProvider.check(adminUser, DELETE, one), requestTimeout) should not be (true)
        Await.result(entitlementProvider.revoke(adminUser.subject, READ, one), requestTimeout) // revoked
    }
}
