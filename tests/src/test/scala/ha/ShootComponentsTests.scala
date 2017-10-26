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

import java.io.File
import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import common.TestUtils
import common.WhiskProperties
import common.rest.WskRest
import common.WskActorSystem
import common.WskProps
import common.WskTestHelpers
import whisk.core.WhiskConfig
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class ShootComponentsTests extends FlatSpec with Matchers with WskTestHelpers with ScalaFutures with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk = new WskRest
  val defaultAction = Some(TestUtils.getTestActionFilename("hello.js"))

  implicit val materializer = ActorMaterializer()
  implicit val testConfig = PatienceConfig(1.minute)

  // Throttle requests to the remaining controllers to avoid getting 429s. (60 req/min)
  val amountOfControllers = WhiskProperties.getProperty(WhiskConfig.controllerInstances).toInt
  val limit = WhiskProperties.getProperty(WhiskConfig.actionInvokeConcurrentLimit).toDouble
  val limitPerController = limit / amountOfControllers
  val allowedRequestsPerMinute = (amountOfControllers - 1.0) * limitPerController
  val timeBeweenRequests = 60.seconds / allowedRequestsPerMinute

  val controller0DockerHost = WhiskProperties.getBaseControllerHost() + ":" + WhiskProperties.getProperty(
    WhiskConfig.dockerPort)

  def restartComponent(host: String, component: String) = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption
    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")

    val cmd = Seq(docker, "--host", host, "restart", component)
    println(s"Running command: ${cmd.mkString(" ")}")

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def ping(host: String, port: Int) = {
    val response = Try { Http().singleRequest(HttpRequest(uri = s"http://$host:$port/ping")).futureValue }.toOption

    response.map { res =>
      (res.status, Unmarshal(res).to[String].futureValue)
    }
  }

  def isControllerAlive(instance: Int): Boolean = {
    require(instance >= 0 && instance < 2, "Controller instance not known.")

    val host = WhiskProperties.getProperty("controller.hosts").split(",")(instance)
    val port = WhiskProperties.getControllerBasePort + instance

    val res = ping(host, port)
    res == Some((StatusCodes.OK, "pong"))
  }

  def doRequests(amount: Int, actionName: String): Seq[(Int, Int)] = {
    (0 until amount).map { i =>
      val start = Instant.now

      // Do POSTs and GETs
      val invokeExit = Future { wsk.action.invoke(actionName, expectedExitCode = TestUtils.DONTCARE_EXIT).exitCode }
      val getExit = Future { wsk.action.get(actionName, expectedExitCode = TestUtils.DONTCARE_EXIT).exitCode }

      println(s"Done rerquests with responses: invoke: ${invokeExit.futureValue} and get: ${getExit.futureValue}")

      val remainingWait = timeBeweenRequests.toMillis - (Instant.now.toEpochMilli - start.toEpochMilli)
      Thread.sleep(if (remainingWait < 0) 0L else remainingWait)
      (invokeExit.futureValue, getExit.futureValue)
    }
  }

  behavior of "Controllers hot standby"

  it should "use controller1 if controller0 goes down" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    if (amountOfControllers >= 2) {
      val actionName = "shootcontroller"

      assetHelper.withCleaner(wsk.action, actionName) { (action, _) =>
        action.create(actionName, defaultAction)
      }

      // Produce some load on the system for 100 seconds. Kill the controller after 4 requests
      val totalRequests = (100.seconds / timeBeweenRequests).toInt

      val requestsBeforeRestart = doRequests(4, actionName)

      // Kill the controller
      restartComponent(controller0DockerHost, "controller0")
      // Wait until down
      retry({
        isControllerAlive(0) shouldBe false
      }, 100, Some(100.milliseconds))
      // Check that second controller is still up
      isControllerAlive(1) shouldBe true

      val requestsAfterRestart = doRequests(totalRequests - 4, actionName)

      val requests = requestsBeforeRestart ++ requestsAfterRestart

      val unsuccessfulInvokes = requests.map(_._1).count(_ != TestUtils.SUCCESS_EXIT)
      // Allow 3 failures for the 100 seconds
      unsuccessfulInvokes should be <= 3

      val unsuccessfulGets = requests.map(_._2).count(_ != TestUtils.SUCCESS_EXIT)
      // Only allow 1 failure in GET requests, because they are idempotent and they should be passed to the next controller if one crashes
      unsuccessfulGets shouldBe 0

      // Check that both controllers are up
      // controller0
      isControllerAlive(0) shouldBe true
      //controller1
      isControllerAlive(1) shouldBe true
    }
  }
}
