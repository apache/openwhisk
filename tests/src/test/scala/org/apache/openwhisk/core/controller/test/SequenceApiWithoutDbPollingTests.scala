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

@RunWith(classOf[JUnitRunner])
class SequenceApiWithoutDbPollingTests extends ControllerTestCommon with WhiskActionsApi {

  behavior of "Sequence API"

  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  val creds = WhiskAuthHelpers.newIdentity()
  val context = UserContext(creds)
  val namespace = EntityPath(creds.subject.asString)
  val defaultNamespace = EntityPath.DEFAULT

  val allowedActionDuration = 120 seconds
  val maxBlockingWait = 1.seconds

  // test actions
  val echo = MakeName.next("echo")
  val sequence = MakeName.next("sequence")
  val step = MakeName.next("step")
  val action1 = MakeName.next("action1")
  val action2 = MakeName.next("action1")
  val action_fail = MakeName.next("action_fail")

  override val disableSequenceStoreResultConfig = Some(true)

  override val loadBalancer = new FakeLoadBalancerService(whiskConfig)
  override val activationIdFactory = new ActivationId.ActivationIdGenerator() {}

  it should "not store a successful blocking Sequence when disable store is configured" in {
    implicit val tid = transid()
    val seqName = sequence.toString
    val compName1 = action1.toString
    val compName2 = action2.toString

    putSimpleSequenceInDB(seqName, namespace, Vector(compName1, compName2))

    Post(s"$collectionPath/${seqName}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(OK)
      val response = responseAs[JsObject]

      response.fields.size shouldBe 12
      response.fields("activationId") should not be None
      val activationId = ActivationId(response.fields("activationId").convertTo[String])

      headers should contain(RawHeader(ActivationIdHeader, activationId.toString))

      //verify that result is not in db record
      val activationDoc = DocId(WhiskEntity.qualifiedName(namespace, activationId))

      // no document should be stored
      assertThrows[NoDocumentException] {
        org.apache.openwhisk.utils.retry({
          getActivation(ActivationId(activationDoc.asString), context)
        }, 10, Some(200.milliseconds))
      }
    }
  }

  it should "store a successful non-blocking Sequence when disable store is configured" in {
    implicit val tid = transid()
    val seqName = sequence.toString
    val compName1 = action1.toString
    val compName2 = action2.toString

    putSimpleSequenceInDB(seqName, namespace, Vector(compName1, compName2))

    Post(s"$collectionPath/${seqName}") ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val response = responseAs[JsObject]

      response.fields.size shouldBe 1
      response.fields("activationId") should not be None
      val activationId = ActivationId(response.fields("activationId").convertTo[String])

      headers should contain(RawHeader(ActivationIdHeader, activationId.toString))

      //verify that result is not in db record
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

    override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
      implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] =
      msg.content map { args =>
        println(s"action ${action.name}  responding to activation id ${msg.activationId} ${msg.cause}")

        Future.successful {
          action.name match {
            case `action1` => // echo action1
              Future(Right(respond(action, msg, args)))
            case `action2` => // echo action2
              Future(Right(respond(action, msg, args)))
            case _ =>
              Future.failed(new IllegalArgumentException("Unkown action invoked in conductor test"))
          }
        }
      } getOrElse Future.failed(new IllegalArgumentException("No invocation parameters in conductor test"))
  }

}
