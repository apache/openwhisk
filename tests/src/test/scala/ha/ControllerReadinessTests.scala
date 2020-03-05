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

package ha

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import common._
import common.rest.HttpConnection
import org.apache.openwhisk.core.controller.Controller
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig._

import scala.concurrent.duration.DurationInt
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class ControllerReadinessTests
    extends FlatSpec
    with Matchers
    with WskTestHelpers
    with WskActorSystem
    with ScalaFutures {

  implicit val wskprops = WskProps()
  implicit val materializer = ActorMaterializer()
  implicit val testConfig = PatienceConfig(1.minute)

  val amountOfControllers = WhiskProperties.getControllerInstances
  val controllerProtocol = loadConfigOrThrow[String]("whisk.controller.protocol")

  val controller0DockerHost = WhiskProperties.getBaseControllerHost()
  val controllerPort = WhiskProperties.getControllerBasePort

  def request(host: String, port: Int, path: String = "/") = {

    val connectionContext = HttpConnection.getContext(controllerProtocol)

    val response = Try {
      Http()
        .singleRequest(
          HttpRequest(uri = s"$controllerProtocol://$host:$port$path"),
          connectionContext = connectionContext)
        .futureValue
    }.toOption

    response.map { res =>
      (res.status, Unmarshal(res).to[String].futureValue)
    }
  }

  behavior of "Controller"

  it should "return list of invokers" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    if (amountOfControllers >= 1) {
      val res = request(controller0DockerHost, controllerPort, "/invokers")
      res shouldBe Some((StatusCodes.OK, "{\"invoker0/0\":\"up\",\"invoker1/1\":\"up\"}"))

      Controller.requiredProperties
    }
  }

  it should "return healthy invokers" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    if (amountOfControllers >= 1) {
      val res = request(controller0DockerHost, controllerPort, "/invokers/healthy/count")
      res shouldBe Some((StatusCodes.OK, "2"))
    }
  }

  it should "return ready state" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    if (amountOfControllers >= 1) {
      val res = request(controller0DockerHost, controllerPort, "/invokers/ready")
      res shouldBe Some((StatusCodes.OK, "healthy 2/2"))
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

}
