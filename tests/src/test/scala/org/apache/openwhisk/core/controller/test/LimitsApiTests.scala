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
import akka.http.scaladsl.model.StatusCodes.{BadRequest, MethodNotAllowed, OK}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.core.controller.WhiskLimitsApi
import org.apache.openwhisk.core.entity.{ConcurrencyLimit, EntityPath, LogLimit, MemoryLimit, TimeLimit, UserLimits}
import org.apache.openwhisk.core.entity.size._

import scala.concurrent.duration._

/**
 * Tests Packages API.
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
class LimitsApiTests extends ControllerTestCommon with WhiskLimitsApi {

  /** Limits API tests */
  behavior of "Limits API"

  // test namespace limit configurations
  val testInvokesPerMinute = 100
  val testConcurrent = 200
  val testFiresPerMinute = 300
  val testAllowedKinds = Set("java:8")
  val testStoreActivations = false

  val testMemoryMin = MemoryLimit(100.MB)
  val testMemoryMax = MemoryLimit(200.MB)
  val testLogMin = LogLimit(3.MB)
  val testLogMax = LogLimit(15.MB)
  val testDurationMax = TimeLimit(20.seconds)
  val testDurationMin = TimeLimit(10.seconds)
  val testConcurrencyMax = ConcurrencyLimit(20)
  val testConcurrencyMin = ConcurrencyLimit(10)

  val creds = WhiskAuthHelpers.newIdentity()
  val credsWithSetLimits = WhiskAuthHelpers
    .newIdentity()
    .copy(
      limits = UserLimits(
        Some(testInvokesPerMinute),
        Some(testConcurrent),
        Some(testFiresPerMinute),
        Some(testAllowedKinds),
        Some(testStoreActivations),
        memoryMin = Some(testMemoryMin),
        memoryMax = Some(testMemoryMax),
        logMin = Some(testLogMin),
        logMax = Some(testLogMax),
        durationMax = Some(testDurationMax),
        durationMin = Some(testDurationMin),
        concurrencyMax = Some(testConcurrencyMax),
        concurrencyMin = Some(testConcurrencyMin)))
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"

  //// GET /limits
  it should "list default system limits if no namespace limits are set" in {
    implicit val tid = transid()
    Seq("", "/").foreach { p =>
      Get(collectionPath + p) ~> Route.seal(routes(creds)) ~> check {
        status should be(OK)
        responseAs[UserLimits].invocationsPerMinute shouldBe Some(whiskConfig.actionInvokePerMinuteLimit.toInt)
        responseAs[UserLimits].concurrentInvocations shouldBe Some(whiskConfig.actionInvokeConcurrentLimit.toInt)
        responseAs[UserLimits].firesPerMinute shouldBe Some(whiskConfig.triggerFirePerMinuteLimit.toInt)
        responseAs[UserLimits].allowedKinds shouldBe None
        responseAs[UserLimits].storeActivations shouldBe None

        // provide default action limits
        responseAs[UserLimits].memoryMin.get.megabytes shouldBe MemoryLimit.MIN_MEMORY_DEFAULT.toMB
        responseAs[UserLimits].memoryMax.get.megabytes shouldBe MemoryLimit.MAX_MEMORY_DEFAULT.toMB
        responseAs[UserLimits].logMin.get.megabytes shouldBe LogLimit.MIN_LOGSIZE_DEFAULT.toMB
        responseAs[UserLimits].logMax.get.megabytes shouldBe LogLimit.MAX_LOGSIZE_DEFAULT.toMB
        responseAs[UserLimits].durationMin.get.duration shouldBe TimeLimit.MIN_DURATION_DEFAULT
        responseAs[UserLimits].durationMax.get.duration shouldBe TimeLimit.MAX_DURATION_DEFAULT
        responseAs[UserLimits].concurrencyMin.get.maxConcurrent shouldBe ConcurrencyLimit.MIN_CONCURRENT_DEFAULT
        responseAs[UserLimits].concurrencyMax.get.maxConcurrent shouldBe ConcurrencyLimit.MAX_CONCURRENT_DEFAULT
      }
    }
  }

  it should "list set limits if limits have been set for the namespace" in {
    implicit val tid = transid()
    Seq("", "/").foreach { p =>
      Get(collectionPath + p) ~> Route.seal(routes(credsWithSetLimits)) ~> check {
        status should be(OK)
        responseAs[UserLimits].invocationsPerMinute shouldBe Some(testInvokesPerMinute)
        responseAs[UserLimits].concurrentInvocations shouldBe Some(testConcurrent)
        responseAs[UserLimits].firesPerMinute shouldBe Some(testFiresPerMinute)
        responseAs[UserLimits].allowedKinds shouldBe Some(testAllowedKinds)
        responseAs[UserLimits].storeActivations shouldBe Some(testStoreActivations)

        // provide action limits for namespace
        responseAs[UserLimits].memoryMin.get.megabytes shouldBe testMemoryMin.megabytes
        responseAs[UserLimits].memoryMax.get.megabytes shouldBe testMemoryMax.megabytes
        responseAs[UserLimits].logMin.get.megabytes shouldBe testLogMin.megabytes
        responseAs[UserLimits].logMax.get.megabytes shouldBe testLogMax.megabytes
        responseAs[UserLimits].durationMin.get.duration shouldBe testDurationMin.duration
        responseAs[UserLimits].durationMax.get.duration shouldBe testDurationMax.duration
        responseAs[UserLimits].concurrencyMin.get.maxConcurrent shouldBe testConcurrencyMin.maxConcurrent
        responseAs[UserLimits].concurrencyMax.get.maxConcurrent shouldBe testConcurrencyMax.maxConcurrent
      }
    }
  }

  it should "reject requests for unsupported methods" in {
    implicit val tid = transid()
    Seq(Put, Post, Delete).foreach { m =>
      m(collectionPath) ~> Route.seal(routes(creds)) ~> check {
        status should be(MethodNotAllowed)
      }
    }
  }

  it should "reject all methods for entity level request" in {
    implicit val tid = transid()
    Seq(Put, Post, Delete).foreach { m =>
      m(s"$collectionPath/limitsEntity") ~> Route.seal(routes(creds)) ~> check {
        status should be(MethodNotAllowed)
      }
    }

    Seq(Get).foreach { m =>
      m(s"$collectionPath/limitsEntity") ~> Route.seal(routes(creds)) ~> check {
        status should be(BadRequest)
      }
    }
  }
}
