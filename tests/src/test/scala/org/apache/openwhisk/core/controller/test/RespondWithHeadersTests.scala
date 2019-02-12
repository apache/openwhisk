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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route

import org.apache.openwhisk.core.controller.RespondWithHeaders

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
class RespondWithHeadersTests extends ControllerTestCommon with RespondWithHeaders {

  behavior of "General API"

  val routes = {
    pathPrefix("api" / "v1") {
      sendCorsHeaders {
        path("one") {
          complete(OK)
        } ~ path("two") {
          complete(OK)
        } ~ options {
          complete(OK)
        } ~ reject
      }
    } ~ pathPrefix("other") {
      complete(OK)
    }
  }

  it should "respond to options" in {
    Options("/api/v1") ~> Route.seal(routes) ~> check {
      headers should contain allOf (allowOrigin, allowHeaders)
    }
  }

  it should "respond to options on every route under /api/v1" in {
    Options("/api/v1/one") ~> Route.seal(routes) ~> check {
      headers should contain allOf (allowOrigin, allowHeaders)
    }
    Options("/api/v1/two") ~> Route.seal(routes) ~> check {
      headers should contain allOf (allowOrigin, allowHeaders)
    }
  }

  it should "respond to options even on bogus routes under /api/v1" in {
    Options("/api/v1/bogus") ~> Route.seal(routes) ~> check {
      headers should contain allOf (allowOrigin, allowHeaders)
    }
  }

  it should "not respond to options on routes before /api/v1" in {
    Options("/api") ~> Route.seal(routes) ~> check {
      status shouldBe NotFound
    }
  }

}
