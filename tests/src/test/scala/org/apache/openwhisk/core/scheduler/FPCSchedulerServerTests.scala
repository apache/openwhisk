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

package org.apache.openwhisk.core.scheduler

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import common.StreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.StatusData
import org.apache.openwhisk.core.entity.SchedulerInstanceId
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.Future

/**
 * Tests SchedulerServer API.
 */
@RunWith(classOf[JUnitRunner])
class FPCSchedulerServerTests
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with StreamLogging
    with MockFactory {

  def transid() = TransactionId("tid")

  val systemUsername = "username"
  val systemPassword = "password"

  val queues = List((SchedulerInstanceId("0"), 2), (SchedulerInstanceId("1"), 3))
  val creationCount = 1
  val testQueueSize = 2
  val statusDatas = List(
    StatusData("testns1", "testaction1", 10, "Running", "RunningData"),
    StatusData("testns2", "testaction2", 5, "Running", "RunningData"))

  // Create scheduler
  val scheduler = new TestScheduler(queues, creationCount, testQueueSize, statusDatas)
  val server = new FPCSchedulerServer(scheduler, systemUsername, systemPassword)

  override protected def afterEach(): Unit = scheduler.reset()

  /** FPCSchedulerServer API tests */
  behavior of "FPCSchedulerServer API"

  // POST /disable
  it should "disable scheduler" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Post(s"/disable") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      scheduler.shutdownCount shouldBe 1
    }
  }

  // GET /state
  it should "get scheduler state" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Get(s"/state") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      responseAs[JsObject] shouldBe (queues.map(s => s._1.asString -> s._2.toString).toMap ++ Map(
        "creationCount" -> creationCount.toString)).toJson
    }
  }

  // GET /queue/total
  it should "get total queue" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Get(s"/queue/total") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      responseAs[String] shouldBe testQueueSize.toString
    }
  }

  // GET /queue/status
  it should "get all queue status" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Get(s"/queue/status") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      responseAs[List[JsObject]] shouldBe statusDatas.map(_.toJson)
    }
  }

  // POST /disable with invalid credential
  it should "not call scheduler api with invalid credential" in {
    implicit val tid = transid()
    val invalidCredentials = BasicHttpCredentials("invaliduser", "invalidpass")
    Post(s"/disable") ~> addCredentials(invalidCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      scheduler.shutdownCount shouldBe 0
    }
  }

  // POST /disable with empty credential
  it should "not call scheduler api with empty credential" in {
    implicit val tid = transid()
    Post(s"/disable") ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      scheduler.shutdownCount shouldBe 0
    }
  }

}

class TestScheduler(schedulerStates: List[(SchedulerInstanceId, Int)],
                    creationCount: Int,
                    queueSize: Int,
                    statusDatas: List[StatusData])
    extends SchedulerCore {
  var shutdownCount = 0

  override def getState: Future[(List[(SchedulerInstanceId, Int)], Int)] =
    Future.successful(schedulerStates, creationCount)

  override def getQueueSize: Future[Int] = Future.successful(queueSize)

  override def getQueueStatusData: Future[List[StatusData]] = Future.successful(statusDatas)

  override def disable(): Unit = shutdownCount += 1

  def reset(): Unit = {
    shutdownCount = 0
  }
}
