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

package whisk.core.containerpool.logging

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.Authorization
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.StreamTcpException
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import common.StreamLogging
import java.time.ZonedDateTime
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import scala.util.Failure
import whisk.core.entity.ActivationLogs
import org.scalatest.FlatSpecLike
import org.scalatest.junit.JUnitRunner
import pureconfig.error.ConfigReaderException
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Success
import scala.util.Try
import spray.json.JsNumber
import spray.json.JsObject
import spray.json._
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationResponse
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.LogLimit
import whisk.core.entity.MemoryLimit
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.core.entity.TimeLimit
import whisk.core.entity.WhiskActivation
import whisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class SplunkLogStoreTests
    extends TestKit(ActorSystem("SplunkLogStore"))
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with StreamLogging {
  val testConfig = SplunkLogStoreConfig(
    "splunk-host",
    8080,
    "splunk-user",
    "splunk-pass",
    "splunk-index",
    "log_message",
    "activation_id",
    false)

  behavior of "Splunk LogStore"

  val startTime = "2007-12-03T10:15:30Z"
  val endTime = "2007-12-03T10:15:45Z"
  val endTimePlus5 = "2007-12-03T10:15:50Z" //queried end time range is endTime+5

  val activation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId(),
    start = ZonedDateTime.parse(startTime).toInstant,
    end = ZonedDateTime.parse(endTime).toInstant,
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  implicit val ec = system.dispatcher
  implicit val materializer = ActorMaterializer()

  val testFlow: Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          //we use cachedHostConnectionPoolHttps so won't get the host+port with the request
          Unmarshal(request.entity)
            .to[FormData]
            .map { form =>
              val earliestTime = form.fields.get("earliest_time")
              val latestTime = form.fields.get("latest_time")
              val outputMode = form.fields.get("output_mode")
              val search = form.fields.get("search")
              val execMode = form.fields.get("exec_mode")

              request.uri.path.toString() shouldBe "/services/search/jobs"
              request.headers shouldBe List(Authorization.basic(testConfig.username, testConfig.password))
              earliestTime shouldBe Some(startTime)
              latestTime shouldBe Some(endTimePlus5)
              outputMode shouldBe Some("json")
              execMode shouldBe Some("oneshot")
              search shouldBe Some(
                s"""search index="${testConfig.index}"| spath ${testConfig.activationIdField}| search ${testConfig.activationIdField}=${activation.activationId.toString}| table ${testConfig.logMessageField}| reverse""")

              (
                Success(
                  HttpResponse(
                    StatusCodes.OK,
                    entity = HttpEntity(
                      ContentTypes.`application/json`,
                      """{"preview":false,"init_offset":0,"messages":[],"fields":[{"name":"log_message"}],"results":[{"log_message":"some log message"},{"log_message":"some other log message"}], "highlighted":{}}"""))),
                userContext)
            }
            .recover {
              case e =>
                println("failed")
                (Failure(e), userContext)
            }
      }
  val failFlow: Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .map {
        case (request, userContext) =>
          (Success(HttpResponse(StatusCodes.InternalServerError)), userContext)

      }

  it should "fail when loading out of box configs (because whisk.logstore.splunk doesn't exist)" in {
    assertThrows[ConfigReaderException[_]] {
      val splunkStore = new SplunkLogStore(system)
    }

  }
  it should "find logs based on activation timestamps" in {
    //use the a flow that asserts the request structure and provides a response in the expected format
    val splunkStore = new SplunkLogStore(system, Some(testFlow), testConfig)
    val result = Await.result(splunkStore.fetchLogs(activation), 1.second)
    result shouldBe ActivationLogs(Vector("some log message", "some other log message"))
  }

  it should "fail to connect to bogus host" in {
    //use the default http flow with the default bogus-host config
    val splunkStore = new SplunkLogStore(system, splunkConfig = testConfig)
    val result = splunkStore.fetchLogs(activation)
    whenReady(result.failed, Timeout(1.second)) { ex =>
      ex shouldBe an[StreamTcpException]
    }
  }
  it should "display an error if API cannot be reached" in {
    //use a flow that generates a 500 response
    val splunkStore = new SplunkLogStore(system, Some(failFlow), testConfig)
    val result = splunkStore.fetchLogs(activation)
    whenReady(result.failed, Timeout(1.second)) { ex =>
      ex shouldBe an[RuntimeException]
    }

  }

}
