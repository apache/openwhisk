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

import scala.concurrent.{Await, Future}
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
import common._
import common.rest.WskRest
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.WhiskConfig
import whisk.core.database.test.ExtendedCouchDbRestClient
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class ShootComponentsTests
    extends FlatSpec
    with Matchers
    with WskTestHelpers
    with ScalaFutures
    with WskActorSystem
    with StreamLogging {

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

  val couchDB0DockerHost = WhiskProperties.getBaseDBHost() + ":" + WhiskProperties.getProperty(WhiskConfig.dockerPort)

  val dbProtocol = WhiskProperties.getProperty(WhiskConfig.dbProtocol)
  val dbHostsList = WhiskProperties.getDBHosts
  val dbPort = WhiskProperties.getProperty(WhiskConfig.dbPort)
  val dbUsername = WhiskProperties.getProperty(WhiskConfig.dbUsername)
  val dbPassword = WhiskProperties.getProperty(WhiskConfig.dbPassword)
  val dbPrefix = WhiskProperties.getProperty(WhiskConfig.dbPrefix)
  val dbWhiskAuth = WhiskProperties.getProperty(WhiskConfig.dbAuths)

  private def getDockerCommand(host: String, component: String, cmd: String) = {
    def file(path: String) = Try(new File(path)).filter(_.exists).map(_.getAbsolutePath).toOption

    val docker = (file("/usr/bin/docker") orElse file("/usr/local/bin/docker")).getOrElse("docker")

    Seq(docker, "--host", host, cmd, component)
  }

  def restartComponent(host: String, component: String) = {
    val cmd: Seq[String] = getDockerCommand(host, component, "restart")
    println(s"Running command: ${cmd.mkString(" ")}")

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def stopComponent(host: String, component: String) = {
    val cmd: Seq[String] = getDockerCommand(host, component, "stop")
    println(s"Running command: ${cmd.mkString(" ")}")

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def startComponent(host: String, component: String) = {
    val cmd: Seq[String] = getDockerCommand(host, component, "start")
    println(s"Running command: ${cmd.mkString(" ")}")

    TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  def ping(host: String, port: Int, path: String = "/") = {
    val response = Try {
      Http().singleRequest(HttpRequest(uri = s"http://$host:$port$path")).futureValue
    }.toOption

    response.map { res =>
      (res.status, Unmarshal(res).to[String].futureValue)
    }
  }

  def isControllerAlive(instance: Int): Boolean = {
    require(instance >= 0 && instance < 2, "Controller instance not known.")

    val host = WhiskProperties.getProperty("controller.hosts").split(",")(instance)
    val port = WhiskProperties.getControllerBasePort + instance

    val res = ping(host, port, "/ping")
    res == Some((StatusCodes.OK, "pong"))
  }

  def isDBAlive(instance: Int): Boolean = {
    require(instance >= 0 && instance < 2, "DB instance not known.")

    val host = WhiskProperties.getProperty("db.hosts").split(",")(instance)
    val port = WhiskProperties.getDBPort + instance

    val res = ping(host, port)
    res == Some(
      (
        StatusCodes.OK,
        "{\"couchdb\":\"Welcome\",\"version\":\"2.1.1\",\"features\":[\"scheduler\"],\"vendor\":{\"name\":\"The Apache Software Foundation\"}}\n"))
  }

  def doRequests(amount: Int, actionName: String): Seq[(Int, Int)] = {
    (0 until amount).map { i =>
      val start = Instant.now

      // Do POSTs and GETs
      val invokeExit = Future {
        wsk.action.invoke(actionName, expectedExitCode = TestUtils.DONTCARE_EXIT).exitCode
      }
      val getExit = Future {
        wsk.action.get(actionName, expectedExitCode = TestUtils.DONTCARE_EXIT).exitCode
      }

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

  behavior of "CouchDB HA"

  it should "be able to retrieve documents from couchdb1 if couchdb0 goes down" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      if (WhiskProperties.getProperty(WhiskConfig.dbInstances).toInt >= 2) {

        val dbName: String = dbWhiskAuth
        val db1 = new ExtendedCouchDbRestClient(
          dbProtocol,
          dbHostsList.split(",")(0),
          dbPort.toInt,
          dbUsername,
          dbPassword,
          dbName)
        val db2 = new ExtendedCouchDbRestClient(
          dbProtocol,
          dbHostsList.split(",")(1),
          dbPort.toInt,
          dbUsername,
          dbPassword,
          dbName)

        println("Creating test document")
        val docId = "couchdb-ha-test"
        val testDocument = JsObject(
          "_id" -> docId.toJson,
          "namespaces" -> JsArray(
            JsObject(
              "name" -> docId.toJson,
              "uuid" -> "789c46b1-71f6-4ed5-8c54-816aa4f8c502".toJson,
              "key" -> "abczO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP".toJson)),
          "subject" -> docId.toJson)

        val docId2 = "couchdb-ha-test2"
        val testDocument2 = JsObject(
          "_id" -> docId2.toJson,
          "namespaces" -> JsArray(
            JsObject(
              "name" -> docId2.toJson,
              "uuid" -> "789c46b1-71f6-4ed5-8c54-816aa4f8c502".toJson,
              "key" -> "abczO3xZCLrMN6v2BKK1dXYFpXlPkccOFqm12CdAsMgRU4VrNZ9lyGVCGuMDGIwP".toJson)),
          "subject" -> docId2.toJson)

        isDBAlive(0) shouldBe true

        retry(db1.putDoc(docId, testDocument))

        stopComponent(couchDB0DockerHost, "couchdb")

        retry({
          isDBAlive(0) shouldBe false
        }, 100, Some(100.milliseconds))

        retry({
          val result = Await.result(db2.getDoc(docId), 15.seconds)
          result should be('right)
          result.right.get.getFields("_id") shouldBe testDocument.getFields("_id")
        })

        retry(
          {
            val result = Await.result(
              db2.executeView("subjects", "identities")(startKey = List(docId), endKey = List(docId)),
              15.seconds)
            result should be('right)
            result.right.get.getFields("_id") shouldBe testDocument.getFields("namespace")
          },
          100,
          Some(100.milliseconds))

        retry(db2.putDoc(docId2, testDocument2))

        isDBAlive(0) shouldBe false

        startComponent(couchDB0DockerHost, "couchdb")

        retry({
          isDBAlive(0) shouldBe true
        }, 100, Some(100.milliseconds))

        retry({
          val result = Await.result(db1.getDoc(docId2), 15.seconds)
          result should be('right)
          result.right.get.getFields("_id") shouldBe testDocument2.getFields("_id")
        })

        retry(
          {
            val result = Await.result(
              db1.executeView("subjects", "identities")(startKey = List(docId2), endKey = List(docId2)),
              15.seconds)
            result should be('right)
            result.right.get.getFields("_id") shouldBe testDocument2.getFields("namespace")
          },
          100,
          Some(100.milliseconds))

        val doc1Result = Await.result(db1.getDoc(docId), 15.seconds)
        val doc2Result = Await.result(db1.getDoc(docId2), 15.seconds)
        val rev1 = doc1Result.right.get.fields.get("_rev").get.convertTo[String]
        val rev2 = doc2Result.right.get.fields.get("_rev").get.convertTo[String]
        Await.result(db1.deleteDoc(docId, rev1), 15.seconds)
        Await.result(db1.deleteDoc(docId2, rev2), 15.seconds)

        retry({
          val result = Await.result(db1.getDoc(docId), 15.seconds)
          result should be('left)
        })
        retry({
          val result = Await.result(db1.getDoc(docId2), 15.seconds)
          result should be('left)
        })
      }
  }
}
