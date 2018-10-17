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

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.Messages._

import scala.util.Success

/**
 * Tests Conductor Actions API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 */
@RunWith(classOf[JUnitRunner])
class ConductorsApiTests extends ControllerTestCommon with WhiskActionsApi {

  /** Conductors API tests */
  behavior of "Conductor"

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"

  val alternateCreds = WhiskAuthHelpers.newIdentity()
  val alternateNamespace = EntityPath(alternateCreds.subject.asString)

  // test actions
  val echo = MakeName.next("echo")
  val conductor = MakeName.next("conductor")
  val step = MakeName.next("step")
  val missing = MakeName.next("missingAction") // undefined
  val invalid = "invalid#Action" // invalid name

  val testString = "this is a test"
  val duration = 42

  val limit = whiskConfig.actionSequenceLimit.toInt

  override val loadBalancer = new FakeLoadBalancerService(whiskConfig)
  override val activationIdFactory = new ActivationId.ActivationIdGenerator() {}

  it should "invoke a conductor action with no dynamic steps" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, echo, jsDefault("??"), annotations = Parameters("conductor", "true")))

    // a normal result
    Post(s"$collectionPath/${echo}?blocking=true", JsObject("payload" -> testString.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("payload" -> testString.toJson)
      response.fields("duration") shouldBe duration.toJson
      val annotations = response.fields("annotations").convertTo[Parameters]
      annotations.getAs[Boolean]("conductor") shouldBe Success(true)
      annotations.getAs[String]("kind") shouldBe Success("sequence")
      annotations.getAs[Boolean]("topmost") shouldBe Success(true)
      annotations.get("limits") should not be None
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 1
    }

    // an error result
    Post(s"$collectionPath/${echo}?blocking=true", JsObject("error" -> testString.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> testString.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 1
    }

    // a wrapped result { params: result } is unwrapped by the controller
    Post(s"$collectionPath/${echo}?blocking=true", JsObject("params" -> JsObject("payload" -> testString.toJson))) ~> Route
      .seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("payload" -> testString.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 1
    }

    // an invalid action name
    Post(s"$collectionPath/${echo}?blocking=true", JsObject("payload" -> testString.toJson, "action" -> invalid.toJson)) ~> Route
      .seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject(
        "error" -> compositionComponentInvalid(invalid.toJson).toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 2
    }

    // an undefined action
    Post(s"$collectionPath/${echo}?blocking=true", JsObject("payload" -> testString.toJson, "action" -> missing.toJson)) ~> Route
      .seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject(
        "error" -> compositionComponentNotFound(s"$namespace/$missing").toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 2
    }
  }

  it should "invoke a conductor action with dynamic steps" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, conductor, jsDefault("??"), annotations = Parameters("conductor", "true")))
    put(entityStore, WhiskAction(namespace, step, jsDefault("??")))
    put(entityStore, WhiskAction(alternateNamespace, step, jsDefault("??"))) // forbidden action
    val forbidden = s"/$alternateNamespace/$step" // forbidden action name

    // dynamically invoke step action
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> step.toJson, "params" -> JsObject("n" -> 1.toJson))) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 2.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3
      response.fields("duration") shouldBe (3 * duration).toJson
    }

    // dynamically invoke step action with an error result
    Post(s"$collectionPath/${conductor}?blocking=true", JsObject("action" -> step.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> "missing parameter".toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3
      response.fields("duration") shouldBe (3 * duration).toJson
    }

    // dynamically invoke step action, forwarding state
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> step.toJson, "state" -> JsObject("witness" -> 42.toJson), "n" -> 1.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 2.toJson, "witness" -> 42.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3
      response.fields("duration") shouldBe (3 * duration).toJson
    }

    // dynamically invoke a forbidden action
    Post(s"$collectionPath/${conductor}?blocking=true", JsObject("action" -> forbidden.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject(
        "error" -> compositionComponentNotAccessible(forbidden.drop(1)).toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 2
    }

    // dynamically invoke step action twice, forwarding state
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> step.toJson, "state" -> JsObject("action" -> step.toJson), "n" -> 1.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 3.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 5
      response.fields("duration") shouldBe (5 * duration).toJson
    }

    // invoke nested conductor with single step
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> conductor.toJson, "params" -> JsObject("action" -> step.toJson), "n" -> 1.toJson)) ~> Route
      .seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 2.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3
      response.fields("duration") shouldBe (5 * duration).toJson
    }

    // nested step followed by outer step
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject(
        "action" -> conductor.toJson,
        "state" -> JsObject("action" -> step.toJson),
        "params" -> JsObject("action" -> step.toJson),
        "n" -> 1.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 3.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 5
      response.fields("duration") shouldBe (7 * duration).toJson
    }

    // two levels of nesting, three steps
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject(
        "action" -> conductor.toJson,
        "state" -> JsObject("action" -> step.toJson),
        "params" -> JsObject(
          "action" -> conductor.toJson,
          "state" -> JsObject("action" -> step.toJson),
          "params" -> JsObject("action" -> step.toJson)),
        "n" -> 1.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 4.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 5
      response.fields("duration") shouldBe (11 * duration).toJson
    }
  }

  it should "abort if composition is too long" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, conductor, jsDefault("??"), annotations = Parameters("conductor", "true")))
    put(entityStore, WhiskAction(namespace, step, jsDefault("??")))

    // stay just below limit
    var params = Map[String, JsValue]()
    for (i <- 1 to limit) {
      params = Map("action" -> step.toJson, "state" -> JsObject(params))
    }
    Post(s"$collectionPath/${conductor}?blocking=true", JsObject(params + ("n" -> 0.toJson))) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> limit.toJson)
      response.fields("duration") shouldBe (101 * duration).toJson
    }

    // add one extra step
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> step.toJson, "state" -> JsObject(params), "n" -> 0.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> compositionIsTooLong.toJson)
    }

    // nesting a composition at the limit should be ok
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> conductor.toJson, "params" -> JsObject(params), "n" -> 0.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> limit.toJson)
    }

    // nesting a composition beyond the limit should fail
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject(
        "action" -> conductor.toJson,
        "params" -> JsObject("action" -> step.toJson, "state" -> JsObject(params)),
        "n" -> 0.toJson)) ~> Route.seal(routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> compositionIsTooLong.toJson)
    }

    // recursing at the limit should be ok
    params = Map[String, JsValue]()
    for (i <- 1 to limit) {
      params = Map("action" -> conductor.toJson, "params" -> JsObject(params))
    }
    Post(s"$collectionPath/${conductor}?blocking=true", JsObject(params + ("n" -> 0.toJson))) ~> Route.seal(
      routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 0.toJson)
    }

    // recursing beyond the limit should fail
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> conductor.toJson, "params" -> JsObject(params), "n" -> 0.toJson)) ~> Route.seal(
      routes(creds)) ~> check {
      status should not be (OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> compositionIsTooLong.toJson)
    }
  }

  // fake load balancer to emulate a handful of actions
  class FakeLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext)
      extends DegenerateLoadBalancerService(config) {

    private def respond(action: ExecutableWhiskActionMetaData, msg: ActivationMessage, result: JsObject) = {
      val response =
        if (result.fields.get("error") isDefined) ActivationResponse(ActivationResponse.ApplicationError, Some(result))
        else ActivationResponse.success(Some(result))
      val start = Instant.now
      WhiskActivation(
        action.namespace,
        action.name,
        msg.user.subject,
        msg.activationId,
        start,
        end = start.plusMillis(duration),
        response = response)
    }

    override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
      implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
      msg.content map { args =>
        Future.successful {
          action.name match {
            case `echo` => // echo action
              Future(Right(respond(action, msg, args)))
            case `conductor` => // see tests/dat/actions/conductor.js
              val result =
                if (args.fields.get("error") isDefined) args
                else {
                  val action = args.fields.get("action") map { action =>
                    Map("action" -> action)
                  } getOrElse Map.empty
                  val state = args.fields.get("state") map { state =>
                    Map("state" -> state)
                  } getOrElse Map.empty
                  val wrappedParams = args.fields.getOrElse("params", JsObject.empty).asJsObject.fields
                  val escapedParams = args.fields - "action" - "state" - "params"
                  val params = Map("params" -> JsObject(wrappedParams ++ escapedParams))
                  JsObject(params ++ action ++ state)
                }
              Future(Right(respond(action, msg, result)))
            case `step` => // see tests/dat/actions/step.js
              val result = args.fields.get("n") map { n =>
                JsObject("n" -> (n.convertTo[BigDecimal] + 1).toJson)
              } getOrElse {
                JsObject("error" -> "missing parameter".toJson)
              }
              Future(Right(respond(action, msg, result)))
            case _ =>
              Future.failed(new IllegalArgumentException("Unkown action invoked in conductor test"))
          }
        }
      } getOrElse Future.failed(new IllegalArgumentException("No invocation parameters in conductor test"))
  }
}
