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

import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.controller.WhiskActionsApi
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.controller.actions.ControllerActivationConfig

import scala.concurrent.{ExecutionContext, Future}

/**
 * Tests PollingFromDb configuration.
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
class PollingFromDbTests extends ControllerTestCommon with WhiskActionsApi {

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
  def aname() = MakeName.next("action_tests")
  override protected val controllerActivationConfig = ControllerActivationConfig(false)

  override val loadBalancer = new AlwaysReturnLeftLoadBalancerService(whiskConfig)

  it should "complete blocking activation request immediately when a Left is returned and pollingFromDb is set to false" in {
    implicit val tid = transid()
    val timeLimit = TimeLimit(1.minute)
    val action = WhiskAction(namespace, aname(), jsDefault("??"), limits = ActionLimits(timeLimit))
    val activationId = activationIdFactory.make()
    val start = Instant.now
    put(entityStore, action)
    Post(s"$collectionPath/${action.name}?blocking=true") ~> Route.seal(routes(creds)) ~> check {
      status should be(Accepted)
      val duration = Instant.now.toEpochMilli - start.toEpochMilli
      duration should be < timeLimit.millis.toLong
      val response = responseAs[JsObject]
      response should be(JsObject("activationId" -> JsString(activationId.asString)))
    }
  }
}

class AlwaysReturnLeftLoadBalancerService(config: WhiskConfig)(implicit ec: ExecutionContext)
    extends DegenerateLoadBalancerService(config) {

  override def activeActivationsFor(namespace: UUID): Future[Int] = Future.successful(0)
  override def totalActiveActivations: Future[Int] = Future.successful(0)

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    Future.successful(Future.successful(Left(msg.activationId)))
  }

  override def invokerHealth() = Future.successful(IndexedSeq.empty)
}
