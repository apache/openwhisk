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

import java.time.Instant

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.controller.actions.ControllerActivationConfig
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.duration.DurationInt

/**
 * Tests Actions API. These tests disable the secondary activation completion path.
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
class ActionsApiWithoutDbPollingTests extends ControllerTestCommon with WhiskActionsApi {

  /** Actions API tests */
  behavior of "Actions API without DB Polling"

  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"

  def aname() = MakeName.next("action_tests")

  val actionLimit = Exec.sizeLimit
  val parametersLimit = Parameters.sizeLimit

  override val controllerActivationConfig = ControllerActivationConfig(false, 2.seconds)

  it should "invoke a blocking action which is converted to a non-blocking due to delayed active ack" in {
    implicit val tid = transid()
    val action = WhiskAction(
      namespace,
      aname(),
      jsDefault("??"),
      limits = ActionLimits(
        TimeLimit(controllerActivationConfig.maxWaitForBlockingActivation - 1.second),
        MemoryLimit(),
        LogLimit()))

    put(entityStore, action)
    val start = Instant.now
    Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      // status should be accepted because there is no active ack response and
      // db polling will fail since there is no record of the activation
      // as a result, the api handler will convert this to a non-blocking request
      status should be(Accepted)
      val duration = Instant.now.toEpochMilli - start.toEpochMilli
      val response = responseAs[JsObject]

      response.fields.size shouldBe 1
      response.fields("activationId") should not be None
      headers should contain(RawHeader(ActivationIdHeader, response.fields("activationId").convertTo[String]))

      // all blocking requests wait up to the specified blocking timeout regadless of the action time limit
      duration should be >= controllerActivationConfig.maxWaitForBlockingActivation.toMillis
    }
  }

  it should "invoke a blocking action which completes with an activation id only" in {
    implicit val tid = transid()
    val action = WhiskAction(namespace, aname(), jsDefault("??"))
    put(entityStore, action)

    try {
      // do not store the activation in the db, instead register it as the response to generate on active ack
      loadBalancer.whiskActivationStub = Some((1.milliseconds, Left(activationIdFactory.make())))

      val start = Instant.now
      Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
        // status should be accepted because the test is simulating a response which only has
        // an activation id response from the invoker; unlike the previous test, here the invoker
        // does respond to complete the api handler's waiting promise
        status should be(Accepted)
        val duration = Instant.now.toEpochMilli - start.toEpochMilli
        val response = responseAs[JsObject]

        response.fields.size shouldBe 1
        response.fields("activationId") should not be None
        headers should contain(RawHeader(ActivationIdHeader, response.fields("activationId").convertTo[String]))
        duration should be <= 1.second.toMillis
      }
    } finally {
      loadBalancer.whiskActivationStub = None
    }
  }

}
