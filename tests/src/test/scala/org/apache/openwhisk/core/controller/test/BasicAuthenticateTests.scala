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

import scala.concurrent.Await

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.headers.BasicHttpCredentials

import org.apache.openwhisk.core.controller.BasicAuthenticationDirective
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entitlement.Privilege

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
class BasicAuthenticateTests extends ControllerTestCommon {
  behavior of "Authenticate"

  it should "authorize a known user using different namespaces and cache key, and reject invalid secret" in {
    implicit val tid = transid()
    val subject = Subject()

    val uuid1 = UUID()
    val uuid2 = UUID()

    val namespaces = Set(
      WhiskNamespace(
        Namespace(MakeName.next("authenticatev_tests"), uuid1),
        BasicAuthenticationAuthKey(uuid1, Secret())),
      WhiskNamespace(
        Namespace(MakeName.next("authenticatev_tests"), uuid2),
        BasicAuthenticationAuthKey(uuid2, Secret())))
    val entry = WhiskAuth(subject, namespaces)
    put(authStore, entry) // this test entry is reclaimed when the test completes

    // Try to login with each specific namespace
    namespaces.foreach { ns =>
      withClue(s"Trying to login to $ns") {
        waitOnView(authStore, ns.authkey, 1) // wait for the view to be updated
        val pass = BasicHttpCredentials(ns.authkey.uuid.asString, ns.authkey.key.asString)
        val user = Await.result(
          BasicAuthenticationDirective
            .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
          dbOpTimeout)
        user.get shouldBe Identity(subject, ns.namespace, ns.authkey, rights = Privilege.ALL)

        // first lookup should have been from datastore
        stream.toString should include(s"serving from datastore: ${CacheKey(ns.authkey)}")
        stream.reset()

        // repeat query, now should be served from cache
        val cachedUser = Await.result(
          BasicAuthenticationDirective
            .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
          dbOpTimeout)
        cachedUser.get shouldBe Identity(subject, ns.namespace, ns.authkey, rights = Privilege.ALL)

        stream.toString should include(s"serving from cache: ${CacheKey(ns.authkey)}")
        stream.reset()
      }
    }

    // check that invalid keys are rejected
    val ns = namespaces.head
    val key = ns.authkey.key.asString
    Seq(key.drop(1), key.dropRight(1), key + "x", BasicAuthenticationAuthKey().key.asString).foreach { k =>
      val pass = BasicHttpCredentials(ns.authkey.uuid.asString, k)
      val user = Await.result(
        BasicAuthenticationDirective
          .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
        dbOpTimeout)
      user shouldBe empty
    }
  }

  it should "not log key during validation" in {
    implicit val tid = transid()
    val creds = WhiskAuthHelpers.newIdentity()
    val pass = creds.authkey.getCredentials.asInstanceOf[Option[BasicHttpCredentials]]
    val user = Await.result(
      BasicAuthenticationDirective.validateCredentials(pass)(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
    stream.toString should not include pass.get.password
  }

  it should "not authorize an unknown user" in {
    implicit val tid = transid()
    val creds = WhiskAuthHelpers.newIdentity()
    val pass = creds.authkey.getCredentials.asInstanceOf[Option[BasicHttpCredentials]]
    val user = Await.result(
      BasicAuthenticationDirective.validateCredentials(pass)(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
  }

  it should "not authorize when no user creds are provided" in {
    implicit val tid = transid()
    val user = Await.result(
      BasicAuthenticationDirective.validateCredentials(None)(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
  }

  it should "not authorize when malformed user is provided" in {
    implicit val tid = transid()
    val pass = BasicHttpCredentials("x", Secret().asString)
    val user = Await.result(
      BasicAuthenticationDirective
        .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
  }

  it should "not authorize when malformed secret is provided" in {
    implicit val tid = transid()
    val pass = BasicHttpCredentials(UUID().asString, "x")
    val user = Await.result(
      BasicAuthenticationDirective
        .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
  }

  it should "not authorize when malformed creds are provided" in {
    implicit val tid = transid()
    val pass = BasicHttpCredentials("x", "y")
    val user = Await.result(
      BasicAuthenticationDirective
        .validateCredentials(Some(pass))(transid, executionContext, logging, authStore),
      dbOpTimeout)
    user should be(None)
  }
}
