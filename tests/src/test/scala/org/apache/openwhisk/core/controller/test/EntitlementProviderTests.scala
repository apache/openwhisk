/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.controller.test

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes._
import org.apache.openwhisk.core.controller.RejectRequest
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entitlement.Privilege._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.ExecManifest.{ImageName, RuntimeManifest}
import org.apache.openwhisk.http.Messages

/**
 * Tests authorization handler which guards resources.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class EntitlementProviderTests extends ControllerTestCommon with ScalaFutures {

  behavior of "Entitlement Provider"

  val requestTimeout = 10.seconds
  val someUser = WhiskAuthHelpers.newIdentity()
  val anotherUser = WhiskAuthHelpers.newIdentity()
  val adminUser = WhiskAuthHelpers.newIdentity(Subject("admin"))
  val guestUser = WhiskAuthHelpers.newIdentity(Subject("anonym"))

  val allowedKinds = Set("nodejs:10", "python")
  val disallowedKinds = Set("golang", "blackbox")

  def getExec(kind: String): Exec = {
    CodeExecAsString(RuntimeManifest(kind, ImageName(kind ++ "action")), "function main(){}", None)
  }

  it should "authorize a user to only read from their collection" in {
    implicit val tid = transid()
    val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, None) }

    resources foreach { r =>
      Await.ready(entitlementProvider.check(someUser, READ, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, PUT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, REJECT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
    }
  }

  it should "authorize a user to only read from their set of collections" in {
    implicit val tid = transid()
    val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
    val resources = collections map { c: Collection =>
      c match {
        case RULES      => Resource(anotherUser.namespace.name.toPath, c, None)
        case NAMESPACES => Resource(anotherUser.namespace.name.toPath, c, None)
        case _          => Resource(someUser.namespace.name.toPath, c, None)
      }
    }

    // Sets aren't ordered, but we need to compared an ordered list of namespaces in the output; so
    // create a sorted list of namespaces per the iterated through the set.  The output list of namespaces
    // will also be in sorted order.
    val resourcesSet = resources.toSet
    val resourcesList = ListBuffer[Resource]()
    resourcesSet.map(r => resourcesList += r)
    val resourceNames = resourcesList.map(r => r.fqname).sorted.toSet.mkString(", ")
    val resourceOtherNames = Seq(
      Resource(anotherUser.namespace.name.toPath, RULES, None),
      Resource(anotherUser.namespace.name.toPath, NAMESPACES, None)).map(r => r.fqname).toSet.mkString(", ")

    Await.ready(entitlementProvider.check(someUser, READ, resourcesSet), requestTimeout).eitherValue.get shouldBe Left(
      RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceOtherNames)))
    Await.ready(entitlementProvider.check(someUser, PUT, resourcesSet), requestTimeout).eitherValue.get shouldBe Left(
      RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceNames)))
    Await
      .ready(entitlementProvider.check(someUser, DELETE, resourcesSet), requestTimeout)
      .eitherValue
      .get shouldBe Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceNames)))
    Await
      .ready(entitlementProvider.check(someUser, ACTIVATE, resourcesSet), requestTimeout)
      .eitherValue
      .get shouldBe Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceNames)))
    Await
      .ready(entitlementProvider.check(someUser, REJECT, resourcesSet), requestTimeout)
      .eitherValue
      .get shouldBe Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceNames)))
  }

  it should "not authorize a user to list someone else's collection or access it by other other right" in {
    implicit val tid = transid()
    val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES, ACTIVATIONS, NAMESPACES)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, None) }
    resources foreach { r =>
      // it is permissible to list packages in any namespace (provided they are either owned by
      // the subject requesting access or the packages are public); that is, the entitlement is more
      // fine grained and applies to public vs private private packages (hence permit READ on PACKAGES to
      // be true
      if ((r.collection == PACKAGES)) {
        Await.ready(entitlementProvider.check(guestUser, READ, r), requestTimeout).eitherValue.get shouldBe Right({})
      } else {
        Await.ready(entitlementProvider.check(guestUser, READ, r), requestTimeout).eitherValue.get shouldBe Left(
          RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      }
      Await.ready(entitlementProvider.check(guestUser, PUT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, REJECT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
    }
  }

  it should "authorize a user to CRUD or activate (if supported) an entity in a collection" in {
    implicit val tid = transid()
    // packages are tested separately
    val collections = Seq(ACTIONS, RULES, TRIGGERS)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, Some("xyz")) }
    resources foreach { r =>
      Await.ready(entitlementProvider.check(someUser, READ, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, PUT, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Right({})
    }
  }

  it should "not authorize a user to CRUD an entity in a collection if authkey has no CRUD rights" in {
    implicit val tid = transid()
    val subject = Subject()
    val uuid = UUID()
    val someUser =
      Identity(
        subject,
        Namespace(EntityName(subject.asString), uuid),
        BasicAuthenticationAuthKey(uuid, Secret()),
        rights = Set(Privilege.ACTIVATE))
    val collections = Seq(ACTIONS, RULES, TRIGGERS)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, Some("xyz")) }
    resources foreach { r =>
      Await.ready(entitlementProvider.check(someUser, READ, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, PUT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Right({})
    }
  }

  it should "not authorize a user to CRUD or activate an entity in a collection that does not support CRUD or activate" in {
    implicit val tid = transid()
    val collections = Seq(NAMESPACES, ACTIVATIONS)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, Some("xyz")) }
    resources foreach { r =>
      Await.ready(entitlementProvider.check(someUser, READ, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, PUT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
    }
  }

  it should "not authorize a user to CRUD or activate an entity in someone else's collection" in {
    implicit val tid = transid()
    val collections = Seq(ACTIONS, RULES, TRIGGERS, PACKAGES)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, Some("xyz")) }
    resources foreach { r =>
      Await.ready(entitlementProvider.check(guestUser, READ, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, PUT, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
      Await.ready(entitlementProvider.check(guestUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
    }
  }

  it should "authorize a user to list, create/update/delete a package" in {
    implicit val tid = transid()
    val collections = Seq(PACKAGES)
    val resources = collections map { Resource(someUser.namespace.name.toPath, _, Some("xyz")) }
    resources foreach { r =>
      // read should fail because the lookup for the package will fail
      Await.ready(entitlementProvider.check(someUser, READ, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(NotFound))
      // create/put/delete should be allowed
      Await.ready(entitlementProvider.check(someUser, PUT, r), requestTimeout).eitherValue.get shouldBe Right({})
      Await.ready(entitlementProvider.check(someUser, DELETE, r), requestTimeout).eitherValue.get shouldBe Right({})
      // activate is not allowed on a package
      Await.ready(entitlementProvider.check(someUser, ACTIVATE, r), requestTimeout).eitherValue.get shouldBe Left(
        RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(r.fqname)))
    }
  }

  it should "grant access to entire collection to another user" in {
    implicit val tid = transid()
    val all = Resource(someUser.namespace.name.toPath, ACTIONS, None)
    val one = Resource(someUser.namespace.name.toPath, ACTIONS, Some("xyz"))
    Await.ready(entitlementProvider.check(adminUser, READ, all), requestTimeout).eitherValue.get should not be Right({})
    Await.ready(entitlementProvider.check(adminUser, READ, one), requestTimeout).eitherValue.get should not be Right({})
    Await.result(entitlementProvider.grant(adminUser, READ, all), requestTimeout) // granted
    Await.ready(entitlementProvider.check(adminUser, READ, all), requestTimeout).eitherValue.get shouldBe Right({})
    Await.ready(entitlementProvider.check(adminUser, READ, one), requestTimeout).eitherValue.get shouldBe Right({})
    Await.result(entitlementProvider.revoke(adminUser, READ, all), requestTimeout) // revoked
  }

  it should "grant access to specific resource to a user" in {
    implicit val tid = transid()
    val all = Resource(someUser.namespace.name.toPath, ACTIONS, None)
    val one = Resource(someUser.namespace.name.toPath, ACTIONS, Some("xyz"))
    Await.ready(entitlementProvider.check(adminUser, READ, all), requestTimeout).eitherValue.get should not be Right({})
    Await.ready(entitlementProvider.check(adminUser, READ, one), requestTimeout).eitherValue.get should not be Right({})
    Await
      .ready(entitlementProvider.check(adminUser, DELETE, one), requestTimeout)
      .eitherValue
      .get should not be Right({})
    Await.result(entitlementProvider.grant(adminUser, READ, one), requestTimeout) // granted
    Await.ready(entitlementProvider.check(adminUser, READ, all), requestTimeout).eitherValue.get should not be Right({})
    Await.ready(entitlementProvider.check(adminUser, READ, one), requestTimeout).eitherValue.get shouldBe Right({})
    Await
      .ready(entitlementProvider.check(adminUser, DELETE, one), requestTimeout)
      .eitherValue
      .get should not be Right({})
    Await.result(entitlementProvider.revoke(adminUser, READ, one), requestTimeout) // revoked
  }

  behavior of "Package Collection"

  it should "only allow read access for listing package collection" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(true)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          // any user can list any namespace packages
          // (because this performs a db view lookup which is later filtered)
          Resource(someUser.namespace.name.toPath, PACKAGES, None))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "reject entitlement if package doesn't exist" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Left(RejectRequest(NotFound))), // for owner, give more information
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(someUser.namespace.name.toPath, PACKAGES, Some("xyz")))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "reject entitlement if package doesn't deserialize" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Left(RejectRequest(Conflict, Messages.conformanceMessage))), // for owner, give more information
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    // this forces a doc mismatch error
    val action = WhiskAction(someUser.namespace.name.toPath, MakeName.next(), jsDefault(""))
    put(entityStore, action)
    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(someUser.namespace.name.toPath, PACKAGES, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "not allow guest access to private package" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next())
    put(entityStore, provider)

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(someUser.namespace.name.toPath, PACKAGES, Some(provider.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "not allow guest access to binding of private package" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    // simulate entitlement change on package for which binding was once entitled
    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next())
    val binding = WhiskPackage(guestUser.namespace.name.toPath, MakeName.next(), provider.bind)
    put(entityStore, provider, false)
    put(entityStore, binding)

    val paths = Seq(
      (READ, someUser, Right(false)),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)), // not allowed to read referenced package
      (PUT, guestUser, Right(true)), // can update
      (DELETE, guestUser, Right(true)), // and delete the binding however
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(guestUser.namespace.name.toPath, PACKAGES, Some(binding.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }

    // simulate package deletion for which binding was once entitled
    deletePackage(provider.docid)
    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(guestUser.namespace.name.toPath, PACKAGES, Some(binding.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "not allow guest access to public binding of package" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    // simulate entitlement change on package for which binding was once entitled
    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = true)
    val binding = WhiskPackage(guestUser.namespace.name.toPath, MakeName.next(), provider.bind, publish = true)
    put(entityStore, provider)
    put(entityStore, binding)

    val paths = Seq(
      (READ, someUser, Right(false)), // cannot access a public binding
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(true)), // can read
      (PUT, guestUser, Right(true)), // can update
      (DELETE, guestUser, Right(true)), // and delete the binding
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(guestUser.namespace.name.toPath, PACKAGES, Some(binding.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "allow guest access to binding of public package" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = true)
    val binding = WhiskPackage(guestUser.namespace.name.toPath, MakeName.next(), provider.bind)
    put(entityStore, provider)
    put(entityStore, binding)

    val paths = Seq(
      (READ, someUser, Right(false)),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(true)), // can read package + binding
      (PUT, guestUser, Right(true)), // can update
      (DELETE, guestUser, Right(true)), // and delete the binding
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new PackageCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(guestUser.namespace.name.toPath, PACKAGES, Some(binding.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  behavior of "Action Collection"

  it should "only allow read access for listing action collection" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Right(false)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          // any user can list any namespace packages
          // (because this performs a db view lookup which is later filtered)
          Resource(someUser.namespace.name.toPath, ACTIONS, None))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "allow guest access to read or activate an action in a package if package is public" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(true)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(true)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(true)),
      (REJECT, guestUser, Right(false)))

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = true)
    val action = WhiskAction(provider.fullPath, MakeName.next(), jsDefault(""))
    put(entityStore, provider)
    put(entityStore, action)

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(action.namespace, ACTIONS, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "reject guest access to read or activate an action in a package if package is private" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = false)
    val action = WhiskAction(provider.fullPath, MakeName.next(), jsDefault(""))
    put(entityStore, provider)
    put(entityStore, action)

    val resourceName = provider.fullyQualifiedName(false).asString

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(true)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(action.namespace, ACTIONS, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "allow guest access to read or activate an action in a package binding if package is public" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = true)
    val binding = WhiskPackage(guestUser.namespace.name.toPath, MakeName.next(), provider.bind)
    val action = WhiskAction(binding.fullPath, MakeName.next(), jsDefault(""))
    put(entityStore, provider)
    put(entityStore, binding)
    put(entityStore, action)

    val resourceName = binding.fullyQualifiedName(false).asString

    val paths = Seq(
      (READ, someUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(true)),
      (PUT, guestUser, Right(true)),
      (DELETE, guestUser, Right(true)),
      (ACTIVATE, guestUser, Right(true)),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(action.namespace, ACTIONS, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "reject guest access to read or activate an action in a package binding if package is private" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val provider = WhiskPackage(someUser.namespace.name.toPath, MakeName.next(), None, publish = false)
    val binding = WhiskPackage(guestUser.namespace.name.toPath, MakeName.next(), provider.bind)
    val action = WhiskAction(binding.fullPath, MakeName.next(), jsDefault(""))
    put(entityStore, provider)
    put(entityStore, binding)
    put(entityStore, action)

    val resourceName = binding.fullyQualifiedName(false).asString

    val paths = Seq(
      (READ, someUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (PUT, someUser, Right(false)),
      (DELETE, someUser, Right(false)),
      (ACTIVATE, someUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (PUT, guestUser, Right(true)),
      (DELETE, guestUser, Right(true)),
      (ACTIVATE, guestUser, Left(RejectRequest(Forbidden, Messages.notAuthorizedtoAccessResource(resourceName)))),
      (REJECT, guestUser, Right(false)))

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(action.namespace, ACTIONS, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "reject guest access to read or activate an action in default package" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider

    val paths = Seq(
      (READ, someUser, Right(true)),
      (PUT, someUser, Right(true)),
      (DELETE, someUser, Right(true)),
      (ACTIVATE, someUser, Right(true)),
      (REJECT, someUser, Right(false)),
      (READ, guestUser, Right(false)),
      (PUT, guestUser, Right(false)),
      (DELETE, guestUser, Right(false)),
      (ACTIVATE, guestUser, Right(false)),
      (REJECT, guestUser, Right(false)))

    val action = WhiskAction(someUser.namespace.name.toPath, MakeName.next(), jsDefault(""))
    put(entityStore, action)

    paths foreach {
      case (priv, who, expected) =>
        val check = new ActionCollection(entityStore).implicitRights(
          who,
          Set(who.namespace.name.asString),
          priv,
          Resource(action.namespace, ACTIONS, Some(action.name.asString)))
        Await.ready(check, requestTimeout).eitherValue.get shouldBe expected
    }
  }

  it should "restrict access to disallowed action kinds for a subject" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider
    val subject = WhiskAuthHelpers.newIdentity().copy(limits = UserLimits(allowedKinds = Some(allowedKinds)))

    disallowedKinds.foreach(k => {
      val ex = intercept[RejectRequest] {
        Await.result(ep.check(subject, Some(getExec(k))), 1.seconds)
      }

      ex.code shouldBe Forbidden
      ex.message.get.error shouldBe Messages.notAuthorizedtoActionKind(k)
    })
  }

  it should "allow access to whitelisted action kinds for a subject" in {
    implicit val tid = transid()
    implicit val ep = entitlementProvider
    val subject = WhiskAuthHelpers.newIdentity().copy(limits = UserLimits(allowedKinds = Some(allowedKinds)))

    Await.result(ep.check(subject, None), 1.seconds)
    allowedKinds.foreach(k => Await.result(ep.check(subject, Some(getExec(k))), 1.seconds))
  }

}
