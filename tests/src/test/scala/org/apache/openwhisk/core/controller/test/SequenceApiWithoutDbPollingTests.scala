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
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Route
import java.time.Instant
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.database.UserContext
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
 * Tests Sequence API - stand-alone tests that require only the controller to be up
 */
@RunWith(classOf[JUnitRunner])
class SequenceApiWithoutDbPollingTests extends ControllerTestCommon with WhiskActionsApi {

  behavior of "Sequence API"

  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val defaultNamespace = EntityPath.DEFAULT
  def aname() = MakeName.next("sequence_tests")

  val allowedActionDuration = 120 seconds
  val maxBlockingWait = 1.seconds
  override val controllerActivationConfig = ControllerActivationConfig(false, maxBlockingWait)
  override val sequenceActivationResponseConfig =
    ActivationResponseConfig(
      true,
      maxBlockingWait - 500.milliseconds,
      controllerActivationConfig = controllerActivationConfig)
  override val loadBalancer = new FakeLoadBalancerService(whiskConfig)
  override val activationIdFactory = new ActivationId.ActivationIdGenerator() {}

  it should "invoke a blocking sequence and exclude the result " in {
    implicit val tid = transid()
    val seqName = s"${aname()}_seq"
    val compName1 = s"${aname()}_comp1"
    val compName2 = s"${aname()}_comp2"

    putSimpleSequenceInDB(seqName, namespace, Vector(compName1, compName2))

    Post(s"$collectionPath/${seqName}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      // status should be accepted because there is no active ack response and
      // db polling will fail since there is no record of the activation
      // as a result, the api handler will convert this to a non-blocking request
      status should be(OK)
      val response = responseAs[JsObject]

      response.fields.size shouldBe 12
      response.fields("activationId") should not be None
      val activationId = ActivationId(response.fields("activationId").convertTo[String])

      headers should contain(RawHeader(ActivationIdHeader, activationId.toString))

      //verify that result is not in db record
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))
      val dbActivation = org.apache.openwhisk.utils.retry({
        //do not perform assertions here since they will be swallowed by retry()
        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation
      }, 30, Some(200.milliseconds))

      dbActivation.response.result shouldBe None
    }
  }

  it should "invoke a blocking sequence exceeds timeout and includes result in db" in {
    implicit val tid = transid()
    val seqName = s"${aname()}_seq"
    val compName1 = s"${aname()}_comp1_slow"
    val compName2 = s"${aname()}_comp2"

    putSimpleSequenceInDB(seqName, namespace, Vector(compName1, compName2))

    Post(s"$collectionPath/${seqName}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      // status should be accepted because there is no active ack response and
      // db polling will fail since there is no record of the activation
      // as a result, the api handler will convert this to a non-blocking request
      status should be(Accepted)
      val response = responseAs[JsObject]

      response.fields.size shouldBe 1
      response.fields("activationId") should not be None
      val activationId = ActivationId(response.fields("activationId").convertTo[String])

      headers should contain(RawHeader(ActivationIdHeader, activationId.toString))

      //verify that result is not in db record
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))
      val dbActivation = org.apache.openwhisk.utils.retry({
        //do not perform assertions here since they will be swallowed by retry()
        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation
      }, 30, Some(400.milliseconds))

      dbActivation.response.result shouldBe Some(Map("slowfield" -> "slowvalue".toJson).toJson)
    }
  }
  it should "invoke a blocking sequence with debug and includes result in db" in {
    implicit val tid = transid()
    val seqName = s"${aname()}_seq"
    val compName1 = s"${aname()}_comp1"
    val compName2 = s"${aname()}_comp2"

    putSimpleSequenceInDB(seqName, namespace, Vector(compName1, compName2))

    Post(s"$collectionPath/${seqName}?blocking=true&debug=true") ~> Route.seal(routes(creds)) ~> check {
      // status should be accepted because there is no active ack response and
      // db polling will fail since there is no record of the activation
      // as a result, the api handler will convert this to a non-blocking request
      status should be(OK)
      val response = responseAs[JsObject]

      response.fields.size shouldBe 12
      response.fields("activationId") should not be None
      val activationId = ActivationId(response.fields("activationId").convertTo[String])

      headers should contain(RawHeader(ActivationIdHeader, activationId.toString))

      //verify that result is not in db record
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))
      val dbActivation = org.apache.openwhisk.utils.retry({
        //do not perform assertions here since they will be swallowed by retry()
        val activation = getActivation(ActivationId(activationDoc.asString), context)
        deleteActivation(ActivationId(activationDoc.asString), context)
        activation
      }, 30, Some(200.milliseconds))

      dbActivation.response.result shouldBe Some(JsObject())
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
      println("emitting activation ")
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

    val Fast = "(.*comp[0-9])".r
    val Slow = "(.*comp[0-9]_slow)".r
    override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
      implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
      msg.content map { args =>
        println(s"action ${action.name}  responding to activation id ${msg.activationId} ${msg.cause}")

        Future.successful {
//          Future(Right(respond(action, msg, Map("somefield" -> "somevalue".toJson).toJson.asJsObject)))
          action.name.toString match {
            case Fast(actionName) => // echo action
              println("running fast")
              Future(Right(respond(action, msg, args)))
            case Slow(actionName) => // echo action
              println("running slow")
              Thread.sleep((maxBlockingWait + 100.milliseconds).toMillis)
              Future(
                Right(
                  respond(
                    action,
                    msg,
                    Map("slowfield" -> "slowvalue".toJson).toJson.asJsObject,
                    maxBlockingWait + 100.milliseconds)))
//            case "conductor" => // see tests/dat/actions/conductor.js
//              val result =
//                if (args.fields.get("error") isDefined) args
//                else {
//                  val action = args.fields.get("action") map { action =>
//                    Map("action" -> action)
//                  } getOrElse Map.empty
//                  val state = args.fields.get("state") map { state =>
//                    Map("state" -> state)
//                  } getOrElse Map.empty
//                  val wrappedParams = args.fields.getOrElse("params", JsObject.empty).asJsObject.fields
//                  val escapedParams = args.fields - "action" - "state" - "params"
//                  val params = Map("params" -> JsObject(wrappedParams ++ escapedParams))
//                  JsObject(params ++ action ++ state)
//                }
//              Future(Right(respond(action, msg, result)))
//            case "step" => // see tests/dat/actions/step.js
//              val result = args.fields.get("n") map { n =>
//                JsObject("n" -> (n.convertTo[BigDecimal] + 1).toJson)
//              } getOrElse {
//                JsObject("error" -> "missing parameter".toJson)
//              }
//              Future(Right(respond(action, msg, result)))
            case _ =>
              Future.failed(new IllegalArgumentException("Unkown action invoked in conductor test"))
          }
        }
      } getOrElse Future.failed(new IllegalArgumentException("No invocation parameters in conductor test"))
  }

}
