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

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.common.AkkaLogging
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.entity.ExecManifest.Runtimes
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import system.rest.RestUtil
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

/**
 * Tests controller readiness.
 *
 * These tests will validate the endpoints that provide information on how many healthy invokers are available
 */

@RunWith(classOf[JUnitRunner])
class ControllerRoutesTests extends ControllerTestCommon with BeforeAndAfterEach with RestUtil {

  implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

  behavior of "Controller"

  it should "return unhealthy invokers status" in {

    configureBuildInfo()

    val controller =
      new Controller(instance, Runtimes(Set.empty, Set.empty, None), whiskConfig, system, logger)
    Get("/invokers/ready") ~> Route.seal(controller.internalInvokerHealth) ~> check {
      status shouldBe InternalServerError
      responseAs[JsObject].fields("unhealthy") shouldBe JsString("0/0")
    }
  }

  it should "return ready state true when healthy == total invokers" in {

    val res = Controller.readyState(5, 5, 1.0)
    res shouldBe true
  }

  it should "return ready state false when 0 invokers" in {

    val res = Controller.readyState(0, 0, 0.5)
    res shouldBe false
  }

  it should "return ready state false when threshold < (healthy / total)" in {

    val res = Controller.readyState(7, 3, 0.5)
    res shouldBe false
  }

  private def configureBuildInfo(): Unit = {
    System.setProperty("whisk.info.build-no", "")
    System.setProperty("whisk.info.date", "")
  }

}
