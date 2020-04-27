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
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
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
import scala.util.{Success, Try}

@RunWith(classOf[JUnitRunner])
class ElasticSearchLogStoreTests
    extends TestKit(ActorSystem("ElasticSearchLogStore"))
    with FlatSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ScalaFutures
    with StreamLogging {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val uuid = UUID()
  private val tenantId = s"testSpace_${uuid}"
  private val user =
    Identity(Subject(), Namespace(EntityName(tenantId), uuid), BasicAuthenticationAuthKey(uuid, Secret()))
  private val activationId = ActivationId.generate()

  private val defaultLogSchema =
    ElasticSearchLogFieldConfig("user_logs", "message", "tenantId", "activationId_str", "stream_str", "time_date")
  private val defaultConfig =
    ElasticSearchLogStoreConfig("https", "host", 443, "/whisk_user_logs/_search", defaultLogSchema)
  private val defaultConfigRequiredHeaders =
    ElasticSearchLogStoreConfig(
      "https",
      "host",
      443,
      "/whisk_user_logs/_search",
      defaultLogSchema,
      Seq("x-auth-token", "x-auth-project-id"))

  private val defaultHttpResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      s"""{"took":799,"timed_out":false,"_shards":{"total":204,"successful":204,"failed":0},"hits":{"total":2,"max_score":null,"hits":[{"_index":"logstash-2018.03.05.02","_type":"user_logs","_id":"1c00007f-ecb9-4083-8d2e-4d5e2849621f","_score":null,"_source":{"time_date":"2018-03-05T02:10:38.196689522Z","accountId":null,"message":"some log stuff\\n","type":"user_logs","event_uuid":"1c00007f-ecb9-4083-8d2e-4d5e2849621f","activationId_str":"$activationId","action_str":"user@email.com/logs","tenantId":"${tenantId}","logmet_cluster":"topic1-elasticsearch_1","@timestamp":"2018-03-05T02:11:37.687Z","@version":"1","stream_str":"stdout","timestamp":"2018-03-05T02:10:39.131Z"},"sort":[1520215897687]},{"_index":"logstash-2018.03.05.02","_type":"user_logs","_id":"14c2a5b7-8cad-4ec0-992e-70fab1996465","_score":null,"_source":{"time_date":"2018-03-05T02:10:38.196754258Z","accountId":null,"message":"more logs\\n","type":"user_logs","event_uuid":"14c2a5b7-8cad-4ec0-992e-70fab1996465","activationId_str":"$activationId","action_str":"user@email.com/logs","tenantId":"tenant","${tenantId}":"topic1-elasticsearch_1","@timestamp":"2018-03-05T02:11:37.701Z","@version":"1","stream_str":"stdout","timestamp":"2018-03-05T02:10:39.131Z"},"sort":[1520215897701]}]}}"""))
  private val defaultPayload = JsObject(
    "query" -> JsObject("query_string" -> JsObject("query" -> JsString(
      s"_type: ${defaultConfig.logSchema.userLogs} AND ${defaultConfig.logSchema.tenantId}: $tenantId AND ${defaultConfig.logSchema.activationId}: $activationId"))),
    "sort" -> JsArray(JsObject(defaultConfig.logSchema.time -> JsObject("order" -> JsString("asc")))),
    "from" -> JsNumber(0)).compactPrint
  private val defaultHttpRequest = HttpRequest(
    POST,
    Uri(s"/whisk_user_logs/_search"),
    List(Accept(MediaTypes.`application/json`)),
    HttpEntity(ContentTypes.`application/json`, defaultPayload))
  private val defaultLogStoreHttpRequest =
    HttpRequest(method = GET, uri = "https://some.url", entity = HttpEntity.Empty)
  private val defaultContext = UserContext(user, defaultLogStoreHttpRequest)

  private val expectedLogs = ActivationLogs(
    Vector("2018-03-05T02:10:38.196689522Z stdout: some log stuff", "2018-03-05T02:10:38.196754258Z stdout: more logs"))

  private val activation = WhiskActivation(
    namespace = EntityPath(tenantId),
    name = EntityName("name"),
    Subject(),
    activationId = activationId,
    start = ZonedDateTime.now.toInstant,
    end = ZonedDateTime.now.toInstant,
    response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
    logs = expectedLogs,
    annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def testFlow(httpResponse: HttpResponse = HttpResponse(), httpRequest: HttpRequest = HttpRequest())
    : Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          request shouldBe httpRequest
          Future.successful((Success(httpResponse), userContext))
      }

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  behavior of "ElasticSearch Log Store"

  it should "fail when loading out of box configs since whisk.logstore.elasticsearch does not exist" in {
    a[ConfigReaderException[_]] should be thrownBy new ElasticSearchLogStore(system)
  }

  it should "get user logs from ElasticSearch when there are no required headers needed" in {
    val esLogStore =
      new ElasticSearchLogStore(
        system,
        Some(testFlow(defaultHttpResponse, defaultHttpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esLogStore.fetchLogs(
        activation.withoutLogs.namespace.asString,
        activation.withoutLogs.activationId,
        None,
        None,
        Some(activation.withoutLogs.logs),
        defaultContext)) shouldBe expectedLogs
  }

  it should "get logs from supplied activation record when required headers are not present" in {
    val esLogStore =
      new ElasticSearchLogStore(
        system,
        Some(testFlow(defaultHttpResponse, defaultHttpRequest)),
        elasticSearchConfig = defaultConfigRequiredHeaders)

    await(
      esLogStore.fetchLogs(
        activation.namespace.asString,
        activation.activationId,
        None,
        None,
        Some(activation.logs),
        defaultContext)) shouldBe expectedLogs
  }

  it should "get user logs from ElasticSearch when required headers are needed" in {
    val authToken = "token"
    val authProjectId = "projectId"
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(
        Accept(MediaTypes.`application/json`),
        RawHeader("x-auth-token", authToken),
        RawHeader("x-auth-project-id", authProjectId)),
      HttpEntity(ContentTypes.`application/json`, defaultPayload))
    val esLogStore =
      new ElasticSearchLogStore(
        system,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfigRequiredHeaders)
    val requiredHeadersHttpRequest = HttpRequest(
      uri = "https://some.url",
      headers = List(RawHeader("x-auth-token", authToken), RawHeader("x-auth-project-id", authProjectId)),
      entity = HttpEntity.Empty)
    val context = UserContext(user, requiredHeadersHttpRequest)

    await(
      esLogStore.fetchLogs(
        activation.withoutLogs.namespace.asString,
        activation.withoutLogs.activationId,
        None,
        None,
        Some(activation.withoutLogs.logs),
        context)) shouldBe expectedLogs
  }

  it should "dynamically replace $UUID in request path" in {
    val dynamicPathConfig =
      ElasticSearchLogStoreConfig("https", "host", 443, "/elasticsearch/logstash-%s*/_search", defaultLogSchema)
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/elasticsearch/logstash-${user.namespace.uuid.asString}*/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultPayload))
    val esLogStore = new ElasticSearchLogStore(
      system,
      Some(testFlow(defaultHttpResponse, httpRequest)),
      elasticSearchConfig = dynamicPathConfig)

    await(
      esLogStore.fetchLogs(
        activation.withoutLogs.namespace.asString,
        activation.withoutLogs.activationId,
        None,
        None,
        Some(activation.withoutLogs.logs),
        defaultContext)) shouldBe expectedLogs
  }

  it should "fail to connect to invalid host" in {
    val esLogStore = new ElasticSearchLogStore(system, elasticSearchConfig = defaultConfig)

    a[Throwable] should be thrownBy await(
      esLogStore.fetchLogs(
        activation.namespace.asString,
        activation.activationId,
        None,
        None,
        Some(activation.logs),
        defaultContext))
  }

  it should "forward errors from ElasticSearch" in {
    val httpResponse = HttpResponse(StatusCodes.InternalServerError)
    val esLogStore =
      new ElasticSearchLogStore(
        system,
        Some(testFlow(httpResponse, defaultHttpRequest)),
        elasticSearchConfig = defaultConfig)

    a[RuntimeException] should be thrownBy await(
      esLogStore.fetchLogs(
        activation.namespace.asString,
        activation.activationId,
        None,
        None,
        Some(activation.logs),
        defaultContext))
  }

  it should "error when configuration protocol is invalid" in {
    val invalidHostConfig =
      ElasticSearchLogStoreConfig("protocol", "host", 443, "/whisk_user_logs", defaultLogSchema, Seq.empty)

    a[IllegalArgumentException] should be thrownBy new ElasticSearchLogStore(
      system,
      elasticSearchConfig = invalidHostConfig)
  }

}
