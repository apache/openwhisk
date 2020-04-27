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

package org.apache.openwhisk.core.containerpool.logging

import java.time.ZonedDateTime

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.Authorization
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import pureconfig.error.ConfigReaderException
import spray.json._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

@RunWith(classOf[JUnitRunner])
class SplunkLogStoreTests
    extends TestKit(ActorSystem("SplunkLogStore"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StreamLogging {

  def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  val testConfig = SplunkLogStoreConfig(
    "splunk-host",
    8080,
    "splunk-user",
    "splunk-pass",
    "splunk-index",
    "log_timestamp",
    "log_stream",
    "log_message",
    "namespace",
    "activation_id",
    "somefield::somevalue",
    10.seconds,
    7.days,
    22.seconds,
    disableSNI = false)

  behavior of "Splunk LogStore"

  val startTime = "2007-12-03T10:15:30Z"
  val startTimePlusOffset = "2007-12-03T10:15:08Z" //queried end time range is endTime-22
  val endTime = "2007-12-03T10:15:45Z"
  val endTimePlusOffset = "2007-12-03T10:16:07Z" //queried end time range is endTime+22
  val uuid = UUID()
  val user =
    Identity(Subject(), Namespace(EntityName("testSpace"), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  val request = HttpRequest(
    method = POST,
    uri = "https://some.url",
    headers = List(RawHeader("key", "value")),
    entity = HttpEntity(MediaTypes.`application/json`, JsObject.empty.compactPrint))

  val activation = WhiskActivation(
    namespace = EntityPath("ns"),
    name = EntityName("a"),
    Subject(),
    activationId = ActivationId.generate(),
    start = ZonedDateTime.parse(startTime).toInstant,
    end = ZonedDateTime.parse(endTime).toInstant,
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson),
    duration = Some(123))

  val context = UserContext(user, request)

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

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
              val maxTime = form.fields.get("max_time")

              request.uri.path.toString() shouldBe "/services/search/jobs"
              request.headers shouldBe List(Authorization.basic(testConfig.username, testConfig.password))
              earliestTime shouldBe Some(startTimePlusOffset)
              latestTime shouldBe Some(endTimePlusOffset)
              outputMode shouldBe Some("json")
              execMode shouldBe Some("oneshot")
              maxTime shouldBe Some("10")
              search shouldBe Some(
                s"""search index="${testConfig.index}" | search ${testConfig.queryConstraints} | search ${testConfig.namespaceField}=${activation.namespace.asString} | search ${testConfig.activationIdField}=${activation.activationId.toString} | spath ${testConfig.logMessageField} | table ${testConfig.logTimestampField}, ${testConfig.logStreamField}, ${testConfig.logMessageField} | reverse""")

              (
                Success(
                  HttpResponse(
                    StatusCodes.OK,
                    entity = HttpEntity(
                      ContentTypes.`application/json`,
                      """{"preview":false,"init_offset":0,"messages":[],"fields":[{"name":"log_message"}],"results":[{"log_timestamp": "2007-12-03T10:15:30Z", "log_stream":"stdout", "log_message":"some log message"},{"log_timestamp": "2007-12-03T10:15:31Z", "log_stream":"stderr", "log_message":"some other log message"},{},{"log_timestamp": "2007-12-03T10:15:32Z", "log_stream":"stderr"}], "highlighted":{}}"""))),
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
    a[ConfigReaderException[_]] should be thrownBy new SplunkLogStore(system)
  }

  it should "find logs based on activation timestamps" in {
    //use the a flow that asserts the request structure and provides a response in the expected format
    val splunkStore = new SplunkLogStore(system, Some(testFlow), testConfig)
    val result = await(
      splunkStore.fetchLogs(
        activation.namespace.asString,
        activation.activationId,
        Some(activation.start),
        Some(activation.end),
        None,
        context))
    result shouldBe ActivationLogs(
      Vector(
        "2007-12-03T10:15:30Z           stdout: some log message",
        "2007-12-03T10:15:31Z           stderr: some other log message",
        "The log message can't be retrieved, key not found: log_timestamp",
        "The log message can't be retrieved, key not found: log_message"))
  }

  it should "fail to connect to bogus host" in {
    //use the default http flow with the default bogus-host config
    val splunkStore = new SplunkLogStore(system, splunkConfig = testConfig)
    a[Throwable] should be thrownBy await(
      splunkStore.fetchLogs(activation.namespace.asString, activation.activationId, None, None, None, context))
  }

  it should "display an error if API cannot be reached" in {
    //use a flow that generates a 500 response
    val splunkStore = new SplunkLogStore(system, Some(failFlow), testConfig)
    a[RuntimeException] should be thrownBy await(
      splunkStore.fetchLogs(activation.namespace.asString, activation.activationId, None, None, None, context))
  }

}
