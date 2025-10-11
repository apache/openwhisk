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

package org.apache.openwhisk.core.invoker.test

import org.apache.pekko.http.scaladsl.model.StatusCodes.{OK, Unauthorized}
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import common.StreamLogging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.v2.{NotSupportedPoolState, TotalContainerPoolState}
import org.apache.openwhisk.core.invoker.Invoker.InvokerEnabled
import org.apache.openwhisk.core.invoker.{DefaultInvokerServer, InvokerCore}
import org.apache.openwhisk.http.BasicHttpService
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.junit.JUnitRunner

import scala.concurrent.Future

/**
 * Tests InvokerServer API.
 */
@RunWith(classOf[JUnitRunner])
class DefaultInvokerServerTests
    extends AnyFlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalatestRouteTest
    with Matchers
    with StreamLogging
    with MockFactory {

  def transid() = TransactionId("tid")

  val systemUsername = "username"
  val systemPassword = "password"

  val reactive = new TestInvokerReactive
  val server = new DefaultInvokerServer(reactive, systemUsername, systemPassword)

  override protected def afterEach(): Unit = reactive.reset()

  /** DefaultInvokerServer API tests */
  behavior of "DefaultInvokerServer API"

  it should "enable invoker" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Post(s"/enable") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      reactive.enableCount shouldBe 1
      reactive.disableCount shouldBe 0
    }
  }

  it should "disable invoker" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Post(s"/disable") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      reactive.enableCount shouldBe 0
      reactive.disableCount shouldBe 1
    }
  }

  it should "check if invoker is enabled" in {
    implicit val tid = transid()
    val validCredentials = BasicHttpCredentials(systemUsername, systemPassword)
    Get(s"/isEnabled") ~> addCredentials(validCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(OK)
      Unmarshal(responseEntity)
        .to[String]
        .map(response => {
          InvokerEnabled.parseJson(response) shouldEqual InvokerEnabled(true)
        })
    }
  }

  it should "not enable invoker with invalid credential" in {
    implicit val tid = transid()
    val invalidCredentials = BasicHttpCredentials("invaliduser", "invalidpass")
    Post(s"/enable") ~> addCredentials(invalidCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      reactive.enableCount shouldBe 0
      reactive.disableCount shouldBe 0
    }
  }

  it should "not disable invoker with invalid credential" in {
    implicit val tid = transid()
    val invalidCredentials = BasicHttpCredentials("invaliduser", "invalidpass")
    Post(s"/disable") ~> addCredentials(invalidCredentials) ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      reactive.enableCount shouldBe 0
      reactive.disableCount shouldBe 0
    }
  }

  it should "not enable invoker with empty credential" in {
    implicit val tid = transid()
    Post(s"/enable") ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      reactive.enableCount shouldBe 0
      reactive.disableCount shouldBe 0
    }
  }

  it should "not disable invoker with empty credential" in {
    implicit val tid = transid()
    Post(s"/disable") ~> Route.seal(server.routes(tid)) ~> check {
      status should be(Unauthorized)
      reactive.enableCount shouldBe 0
      reactive.disableCount shouldBe 0
    }
  }

}

class TestInvokerReactive extends InvokerCore with BasicHttpService {
  var enableCount = 0
  var disableCount = 0

  override def enable(): String = {
    enableCount += 1
    s""
  }

  override def disable(): String = {
    disableCount += 1
    s""
  }

  override def isEnabled(): String = {
    complete(InvokerEnabled(true).serialize())
    s""
  }

  override def backfillPrewarm(): String = {
    ""
  }

  override def getPoolState(): Future[Either[NotSupportedPoolState, TotalContainerPoolState]] = {
    Future.successful(Left(NotSupportedPoolState()))
  }

  def reset(): Unit = {
    enableCount = 0
    disableCount = 0
  }

  /**
   * Gets the routes implemented by the HTTP service.
   *
   * @param transid the id for the transaction (every request is assigned an id)
   */
  override def routes(implicit transid: TransactionId): Route = ???

}
