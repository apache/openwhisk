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

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import java.time.Instant

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.database.{NoDocumentException, UserContext}
import org.apache.openwhisk.core.entity._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import spray.json.DefaultJsonProtocol._
import spray.json._

/**
 * Tests Conductor API - stand-alone tests that require only the controller to be up
 */
@RunWith(classOf[JUnitRunner])
class ConductorsApiWithoutDbPollingTests extends ControllerTestCommon with WhiskActionsApi {

  behavior of "Conductor API"

  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val defaultNamespace = EntityPath.DEFAULT
  val alternateCreds = WhiskAuthHelpers.newIdentity()
  val alternateNamespace = EntityPath(alternateCreds.subject.asString)

  // test actions
  val conductor = MakeName.next("conductor")
  val step = MakeName.next("step")

  override val disableStoreResultConfig = true

  override val loadBalancer = new FakeLoadBalancerService(whiskConfig)
  override val activationIdFactory = new ActivationId.ActivationIdGenerator() {}

  it should "not store a successful blocking Conductor when disable store is configured" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, conductor, jsDefault("??"), annotations = Parameters("conductor", "true")))
    put(entityStore, WhiskAction(namespace, step, jsDefault("??")))

    // dynamically invoke step action
    Post(
      s"$collectionPath/${conductor}?blocking=true",
      JsObject("action" -> step.toJson, "params" -> JsObject("n" -> 1.toJson))) ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("n" -> 2.toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3

      //verify that result is not in db record
      val activationId = ActivationId(response.fields("activationId").convertTo[String])
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))

      // no document should be stored
      assertThrows[NoDocumentException] {
        org.apache.openwhisk.utils.retry({
          getActivation(ActivationId(activationDoc.asString), context)
        }, 10, Some(200.milliseconds))
      }
    }
  }

  it should "store a successful non-blocking Conductor when disable store is configured" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, conductor, jsDefault("??"), annotations = Parameters("conductor", "true")))
    put(entityStore, WhiskAction(namespace, step, jsDefault("??")))

    // dynamically invoke step action
    Post(s"$collectionPath/${conductor}", JsObject("action" -> step.toJson, "params" -> JsObject("n" -> 1.toJson))) ~> Route
      .seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]
      response.fields.size shouldBe 1

      //verify that result is not in db record
      val activationId = ActivationId(response.fields("activationId").convertTo[String])
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))

      // document should be stored
      val dbActivation = org.apache.openwhisk.utils.retry({
        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation
      }, 10, Some(200.milliseconds))

      dbActivation.activationId should not be None
    }
  }

  it should "store a unsuccessful blocking Conductor when disable store is configured" in {
    implicit val tid = transid()
    put(entityStore, WhiskAction(namespace, conductor, jsDefault("??"), annotations = Parameters("conductor", "true")))
    put(entityStore, WhiskAction(namespace, step, jsDefault("??")))

    // dynamically invoke step action
    Post(s"$collectionPath/${conductor}?blocking=true", JsObject("action" -> step.toJson)) ~> Route.seal(routes(creds)) ~> check {
      val response = responseAs[JsObject]
      status should not be (OK)
      response.fields("response").asJsObject.fields("status") shouldBe "application error".toJson
      response.fields("response").asJsObject.fields("result") shouldBe JsObject("error" -> "missing parameter".toJson)
      response.fields("logs").convertTo[JsArray].elements.size shouldBe 3

      //verify that result is not in db record
      val activationId = ActivationId(response.fields("activationId").convertTo[String])
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))

      // document should be stored
      val dbActivation = org.apache.openwhisk.utils.retry({
        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation
      }, 10, Some(200.milliseconds))

      dbActivation.activationId should not be None
    }
  }

  // fake load balancer to emulate a handful of actions
  class FakeLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext)
      extends DegenerateLoadBalancerService(config) {

    private def respond(action: ExecutableWhiskActionMetaData,
                        msg: ActivationMessage,
                        result: JsObject,
                        duration: FiniteDuration = 42.milliseconds) = {
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
        end = start.plusMillis(duration.toMillis),
        duration = Some(duration.toMillis),
        response = response)
    }

    override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
      implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
      msg.content map { args =>
        Future.successful {
          action.name match {
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
