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

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestKit
import common.StreamLogging
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpecLike, Matchers}
import pureconfig.error.ConfigReaderException
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.entity._
import whisk.core.database.{
  ArtifactElasticSearchActivationStore,
  ElasticSearchActivationFieldConfig,
  ElasticSearchActivationStoreConfig,
  NoDocumentException,
  UserContext
}

import whisk.common.TransactionId
import whisk.core.entity.size.SizeInt

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Success, Try}

/*
TODO:
Required headers
 */
@RunWith(classOf[JUnitRunner])
class ElasticSearchActivationStoreTests
    extends TestKit(ActorSystem("ElasticSearchActivationStore"))
    with FlatSpecLike
    with Matchers
    with ScalaFutures
    with StreamLogging {

  val materializer = ActorMaterializer()

  implicit val transid: TransactionId = TransactionId.testing

  private val uuid = UUID()
  private val subject = Subject()
  private val user =
    Identity(subject, Namespace(EntityName("testSpace"), uuid), BasicAuthenticationAuthKey(uuid, Secret()), Set())
  private val activationId = ActivationId.generate()
  private val namespace = EntityPath("namespace")
  private val name = EntityName("name")
  private val response = JsObject("result key" -> JsString("result value"))
  private val start = Instant.now
  private val end = Instant.now
  private val since = Instant.now
  private val upto = Instant.now
  private val logs =
    Vector("2018-03-05T02:10:38.196689522Z stdout: some log stuff", "2018-03-05T02:10:38.196754258Z stdout: more logs")
  private val expectedLogs = ActivationLogs(logs)
  private val activation = WhiskActivation(
    namespace = namespace,
    name = name,
    subject,
    activationId = activationId,
    start = start,
    end = end,
    response = ActivationResponse.success(Some(response)),
    logs = expectedLogs,
    duration = Some(101L),
    annotations = Parameters("kind", "nodejs:6") ++ Parameters(
      "limits",
      ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
      Parameters("waitTime", 16.toJson) ++
      Parameters("initTime", 44.toJson))

  // Elasticsearch configuration
  private val defaultSchema =
    ElasticSearchActivationFieldConfig(
      "name",
      "namespace",
      "subject",
      "version",
      "start",
      "end",
      "duration",
      "result",
      "statusCode",
      "activationId",
      "activation_record",
      "stream")
  private val defaultConfig =
    ElasticSearchActivationStoreConfig("https", "host", 443, "/whisk_user_logs/_search", defaultSchema)

  // Elasticsearch query responses
  private val defaultQueryResponse =
    JsObject(
      "took" -> 4.toJson,
      "timed_out" -> false.toJson,
      "_shards" -> JsObject("total" -> 5.toJson, "successful" -> 5.toJson, "failed" -> 0.toJson),
      "hits" -> JsObject(
        "total" -> 2.toJson,
        "max_score" -> 3.74084.toJson,
        "hits" -> JsArray(
          JsObject(
            "_index" -> "whisk_user_logs".toJson,
            "_type" -> defaultConfig.schema.activationRecord.toJson,
            "_id" -> "AWUQoCrVV6WHiq7A5LL8".toJson,
            "_score" -> 3.74084.toJson,
            "_source" -> JsObject(
              defaultConfig.schema.statusCode -> 0.toJson,
              defaultConfig.schema.duration -> 101.toJson,
              defaultConfig.schema.name -> name.toJson,
              defaultConfig.schema.subject -> subject.toJson,
              "waitTime" -> 16.toJson,
              defaultConfig.schema.activationId -> activationId.toJson,
              defaultConfig.schema.result -> response.compactPrint.toJson,
              defaultConfig.schema.version -> "0.0.1".toJson,
              "cause" -> JsNull,
              defaultConfig.schema.end -> end.toEpochMilli.toJson,
              "kind" -> "nodejs:6".toJson,
              "logs" -> logs.toJson,
              defaultConfig.schema.start -> start.toEpochMilli.toJson,
              "limits" -> JsObject("timeout" -> 60000.toJson, "memory" -> 256.toJson, "logs" -> 10.toJson),
              "initTime" -> 44.toJson,
              defaultConfig.schema.namespace -> namespace.toJson,
              "@version" -> "1".toJson,
              "type" -> defaultConfig.schema.activationRecord.toJson,
              "ALCH_TENANT_ID" -> "9cfe57a0-7ac1-4bf4-9026-d7e9e591271f".toJson // UUID
            )),
          JsObject(
            "_index" -> "whisk_user_logs".toJson,
            "_type" -> defaultConfig.schema.activationRecord.toJson,
            "_id" -> "AWUQoCrVV6WHiq7A5LL8".toJson,
            "_score" -> 3.74084.toJson,
            "_source" -> JsObject(
              defaultConfig.schema.statusCode -> 0.toJson,
              defaultConfig.schema.duration -> 101.toJson,
              defaultConfig.schema.name -> name.toJson,
              defaultConfig.schema.subject -> subject.toJson,
              "waitTime" -> 16.toJson,
              defaultConfig.schema.activationId -> activationId.toJson,
              defaultConfig.schema.result -> response.compactPrint.toJson,
              defaultConfig.schema.version -> "0.0.1".toJson,
              "cause" -> JsNull,
              defaultConfig.schema.end -> end.toEpochMilli.toJson,
              "kind" -> "nodejs:6".toJson,
              "logs" -> logs.toJson,
              defaultConfig.schema.start -> start.toEpochMilli.toJson,
              "limits" -> JsObject("timeout" -> 60000.toJson, "memory" -> 256.toJson, "logs" -> 10.toJson),
              "initTime" -> 44.toJson,
              defaultConfig.schema.namespace -> namespace.toJson,
              "@version" -> "1".toJson,
              "type" -> defaultConfig.schema.activationRecord.toJson,
              "ALCH_TENANT_ID" -> "9cfe57a0-7ac1-4bf4-9026-d7e9e591271f".toJson // UUID
            )))))

  // Elasticsearch query requests
  private val defaultPayload = JsObject(
    "query" -> JsObject(
      "query_string" -> JsObject("query" -> JsString(
        s"_type: ${defaultConfig.schema.activationRecord} AND ${defaultConfig.schema.activationId}: $activationId"))),
    "from" -> JsNumber(0)).compactPrint
  private val defaultGetPayload = JsObject(
    "query" -> JsObject(
      "query_string" -> JsObject("query" -> JsString(
        s"_type: ${defaultConfig.schema.activationRecord} AND ${defaultConfig.schema.activationId}: $activationId"))),
    "from" -> JsNumber(0)).compactPrint
  private val defaultCountPayload = JsObject(
    "query" -> JsObject(
      "bool" -> JsObject(
        "must" -> JsArray(
          JsObject("match" -> JsObject("_type" -> JsString(defaultConfig.schema.activationRecord))),
          JsObject("match" -> JsObject(defaultConfig.schema.name -> JsString(name.name)))),
        "filter" -> JsArray(
          JsObject(
            "range" -> JsObject(defaultConfig.schema.start -> JsObject("gt" -> JsString(since.toEpochMilli.toString)))),
          JsObject("range" -> JsObject(
            defaultConfig.schema.start -> JsObject("lt" -> JsString(upto.toEpochMilli.toString))))))),
    "sort" -> JsArray(JsObject(defaultConfig.schema.start -> JsObject("order" -> JsString("desc")))),
    "from" -> JsNumber(1)).compactPrint
  private val defaultListEntityPayload = JsObject(
    "query" -> JsObject(
      "bool" -> JsObject(
        "must" -> JsArray(
          JsObject("match" -> JsObject("_type" -> JsString(defaultConfig.schema.activationRecord))),
          JsObject("match" -> JsObject(defaultConfig.schema.name -> JsString(name.name)))),
        "filter" -> JsArray(
          JsObject(
            "range" -> JsObject(defaultConfig.schema.start -> JsObject("gt" -> JsString(since.toEpochMilli.toString)))),
          JsObject("range" -> JsObject(
            defaultConfig.schema.start -> JsObject("lt" -> JsString(upto.toEpochMilli.toString))))))),
    "sort" -> JsArray(JsObject(defaultConfig.schema.start -> JsObject("order" -> JsString("desc")))),
    "size" -> JsNumber(2),
    "from" -> JsNumber(1)).compactPrint
  private val defaultListPayload = JsObject(
    "query" -> JsObject(
      "bool" -> JsObject(
        "must" -> JsArray(
          JsObject("match" -> JsObject("_type" -> JsString(defaultConfig.schema.activationRecord))),
          JsObject("match" -> JsObject(defaultConfig.schema.subject -> JsString(user.namespace.name.asString)))),
        "filter" -> JsArray(
          JsObject(
            "range" -> JsObject(defaultConfig.schema.start -> JsObject("gt" -> JsString(since.toEpochMilli.toString)))),
          JsObject("range" -> JsObject(
            defaultConfig.schema.start -> JsObject("lt" -> JsString(upto.toEpochMilli.toString))))))),
    "sort" -> JsArray(JsObject(defaultConfig.schema.start -> JsObject("order" -> JsString("desc")))),
    "size" -> JsNumber(2),
    "from" -> JsNumber(1)).compactPrint

  // Elasticsearch HTTP responses
  private val defaultHttpResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(ContentTypes.`application/json`, defaultQueryResponse.compactPrint))
  private val emptyHttpResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      s"""{"took":2,"timed_out":false,"_shards":{"total":5,"successful":5,"failed":0},"hits":{"total":0,"max_score":null,"hits":[]}}"""))

  // Elasticsearch HTTP requests
  private val defaultHttpRequest = HttpRequest(
    POST,
    Uri(s"/whisk_user_logs/_search"),
    List(Accept(MediaTypes.`application/json`)),
    HttpEntity(ContentTypes.`application/json`, defaultPayload))
  private val defaultLogStoreHttpRequest =
    HttpRequest(method = GET, uri = "https://some.url", entity = HttpEntity.Empty)

  private def testFlow(httpResponse: HttpResponse = HttpResponse(), httpRequest: HttpRequest = HttpRequest())
    : Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), NotUsed] =
    Flow[(HttpRequest, Promise[HttpResponse])]
      .mapAsyncUnordered(1) {
        case (request, userContext) =>
          //println(request)
          //println(httpRequest)
          request shouldBe httpRequest
          Future.successful((Success(httpResponse), userContext))
      }

  private def await[T](awaitable: Future[T], timeout: FiniteDuration = 10.seconds) = Await.result(awaitable, timeout)

  behavior of "ElasticSearch Activation Store"

  it should "fail to connect to invalid host" in {
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(system, materializer, logging, elasticSearchConfig = defaultConfig)

    a[Throwable] should be thrownBy await(
      esActivationStore.get(activation.activationId, UserContext(user, defaultLogStoreHttpRequest)))
  }

  it should "get an activation" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultGetPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(esActivationStore.get(activationId, UserContext(user, defaultLogStoreHttpRequest))) shouldBe activation
  }

  it should "get an activation with error response" in {
    val errorMessage = "message".toJson
    val errorResponse = JsObject("error" -> errorMessage)
    val activationResponses = Seq(
      (1, ActivationResponse.applicationError(errorMessage)),
      (2, ActivationResponse.containerError(errorMessage)),
      (3, ActivationResponse.whiskError(errorMessage)))

    activationResponses.foreach {
      case (statusCode, activationResponse) =>
        val content = JsObject(
          "took" -> 4.toJson,
          "timed_out" -> false.toJson,
          "_shards" -> JsObject("total" -> 5.toJson, "successful" -> 5.toJson, "failed" -> 0.toJson),
          "hits" -> JsObject(
            "total" -> 1.toJson,
            "max_score" -> 3.74084.toJson,
            "hits" -> JsArray(JsObject(
              "_index" -> "whisk_user_logs".toJson,
              "_type" -> defaultConfig.schema.activationRecord.toJson,
              "_id" -> "AWUQoCrVV6WHiq7A5LL8".toJson,
              "_score" -> 3.74084.toJson,
              "_source" -> JsObject(
                defaultConfig.schema.statusCode -> statusCode.toJson,
                defaultConfig.schema.duration -> 101.toJson,
                defaultConfig.schema.name -> name.toJson,
                defaultConfig.schema.subject -> subject.toJson,
                "waitTime" -> 16.toJson,
                defaultConfig.schema.activationId -> activationId.toJson,
                defaultConfig.schema.result -> errorResponse.compactPrint.toJson,
                defaultConfig.schema.version -> "0.0.1".toJson,
                "cause" -> JsNull,
                defaultConfig.schema.end -> end.toEpochMilli.toJson,
                "kind" -> "nodejs:6".toJson,
                "logs" -> logs.toJson,
                defaultConfig.schema.start -> start.toEpochMilli.toJson,
                "limits" -> JsObject("timeout" -> 60000.toJson, "memory" -> 256.toJson, "logs" -> 10.toJson),
                "initTime" -> 44.toJson,
                defaultConfig.schema.namespace -> namespace.toJson,
                "@version" -> "1".toJson,
                "type" -> defaultConfig.schema.activationRecord.toJson,
                "ALCH_TENANT_ID" -> "9cfe57a0-7ac1-4bf4-9026-d7e9e591271f".toJson // UUID
              )))))
        val activationWithError = WhiskActivation(
          namespace = namespace,
          name = name,
          subject,
          activationId = activationId,
          start = start,
          end = end,
          response = activationResponse,
          logs = expectedLogs,
          duration = Some(101L),
          annotations = Parameters("kind", "nodejs:6") ++ Parameters(
            "limits",
            ActionLimits(TimeLimit(60.second), MemoryLimit(256.MB), LogLimit(10.MB)).toJson) ++
            Parameters("waitTime", 16.toJson) ++
            Parameters("initTime", 44.toJson))
        val defaultHttpErrorResponse =
          HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, content.compactPrint))
        val httpRequest = HttpRequest(
          POST,
          Uri(s"/whisk_user_logs/_search"),
          List(Accept(MediaTypes.`application/json`)),
          HttpEntity(ContentTypes.`application/json`, defaultGetPayload))
        val esActivationStore =
          new ArtifactElasticSearchActivationStore(
            system,
            materializer,
            logging,
            Some(testFlow(defaultHttpErrorResponse, httpRequest)),
            elasticSearchConfig = defaultConfig)

        await(esActivationStore.get(activationId, UserContext(user, defaultLogStoreHttpRequest))) shouldBe activationWithError
    }
  }

  it should "error when getting an activation that does not exist" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultGetPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(emptyHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    a[NoDocumentException] should be thrownBy await(
      esActivationStore.get(activationId, UserContext(user, defaultLogStoreHttpRequest)))
  }

  it should "dynamically replace $UUID when getting an activation" in {
    val dynamicPathConfig =
      ElasticSearchActivationStoreConfig("https", "host", 443, "/elasticsearch/logstash-%s*/_search", defaultSchema)
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/elasticsearch/logstash-${user.namespace.uuid.asString}*/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultGetPayload))

    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = dynamicPathConfig)

    await(esActivationStore.get(activation.activationId, UserContext(user, defaultLogStoreHttpRequest))) shouldBe activation
  }

  it should "count activations in namespace" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultCountPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.countActivationsInNamespace(
        user.namespace.name.toPath,
        Some(name.toPath),
        1,
        since = Some(since),
        upto = Some(upto),
        UserContext(user, defaultLogStoreHttpRequest))) shouldBe JsObject("activations" -> JsNumber(1))
  }

  it should "count activations in namespace with no entity name" in {
    val payload = JsObject(
      "query" -> JsObject(
        "bool" -> JsObject(
          "must" -> JsArray(JsObject("match" -> JsObject("_type" -> JsString(defaultConfig.schema.activationRecord)))),
          "filter" -> JsArray(
            JsObject("range" -> JsObject(
              defaultConfig.schema.start -> JsObject("gt" -> JsString(since.toEpochMilli.toString)))),
            JsObject("range" -> JsObject(
              defaultConfig.schema.start -> JsObject("lt" -> JsString(upto.toEpochMilli.toString))))))),
      "sort" -> JsArray(JsObject(defaultConfig.schema.start -> JsObject("order" -> JsString("desc")))),
      "from" -> JsNumber(1)).compactPrint
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, payload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.countActivationsInNamespace(
        user.namespace.name.toPath,
        skip = 1,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe JsObject("activations" -> JsNumber(1))
  }

  it should "count zero activations in when there are not any activations that match entity" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultCountPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(emptyHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.countActivationsInNamespace(
        user.namespace.name.toPath,
        Some(name.toPath),
        1,
        since = Some(since),
        upto = Some(upto),
        UserContext(user, defaultLogStoreHttpRequest))) shouldBe JsObject("activations" -> JsNumber(0))
  }

  it should "dynamically replace $UUID in request when counting activations" in {
    val dynamicPathConfig =
      ElasticSearchActivationStoreConfig("https", "host", 443, "/elasticsearch/logstash-%s*/_search", defaultSchema)
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/elasticsearch/logstash-${user.namespace.uuid.asString}*/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultCountPayload))

    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = dynamicPathConfig)

    await(
      esActivationStore.countActivationsInNamespace(
        user.namespace.name.toPath,
        Some(name.toPath),
        1,
        since = Some(since),
        upto = Some(upto),
        UserContext(user, defaultLogStoreHttpRequest))) shouldBe JsObject("activations" -> JsNumber(1))
  }

  it should "list activations matching entity name" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListEntityPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.listActivationsMatchingName(
        user.namespace.name.toPath,
        name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List(activation, activation))
  }

  it should "display empty activations list when there are not any activations that match entity name" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListEntityPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(emptyHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.listActivationsMatchingName(
        user.namespace.name.toPath,
        name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List.empty)
  }

  it should "dynamically replace $UUID in request when getting activations matching entity name" in {
    val dynamicPathConfig =
      ElasticSearchActivationStoreConfig("https", "host", 443, "/elasticsearch/logstash-%s*/_search", defaultSchema)
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/elasticsearch/logstash-${user.namespace.uuid.asString}*/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListEntityPayload))

    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = dynamicPathConfig)

    await(
      esActivationStore.listActivationsMatchingName(
        user.namespace.name.toPath,
        name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List(activation, activation))
  }

  it should "list activations in namespace" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.listActivationsInNamespace(
        user.namespace.name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List(activation, activation))
  }

  it should "display empty activations list when there are not any activations in namespace" in {
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/whisk_user_logs/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListPayload))
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(emptyHttpResponse, httpRequest)),
        elasticSearchConfig = defaultConfig)

    await(
      esActivationStore.listActivationsInNamespace(
        user.namespace.name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List.empty)
  }

  it should "dynamically replace $UUID in request when listing activations in namespace" in {
    val dynamicPathConfig =
      ElasticSearchActivationStoreConfig("https", "host", 443, "/elasticsearch/logstash-%s*/_search", defaultSchema)
    val httpRequest = HttpRequest(
      POST,
      Uri(s"/elasticsearch/logstash-${user.namespace.uuid.asString}*/_search"),
      List(Accept(MediaTypes.`application/json`)),
      HttpEntity(ContentTypes.`application/json`, defaultListPayload))

    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(defaultHttpResponse, httpRequest)),
        elasticSearchConfig = dynamicPathConfig)

    await(
      esActivationStore.listActivationsInNamespace(
        user.namespace.name.toPath,
        1,
        2,
        since = Some(since),
        upto = Some(upto),
        context = UserContext(user, defaultLogStoreHttpRequest))) shouldBe Right(List(activation, activation))
  }

  it should "forward errors from Elasticsearch" in {
    val httpResponse = HttpResponse(StatusCodes.InternalServerError)
    val esActivationStore =
      new ArtifactElasticSearchActivationStore(
        system,
        materializer,
        logging,
        Some(testFlow(httpResponse, defaultHttpRequest)),
        elasticSearchConfig = defaultConfig)

    a[RuntimeException] should be thrownBy await(
      esActivationStore.get(activation.activationId, UserContext(user, defaultLogStoreHttpRequest)))
    a[RuntimeException] should be thrownBy await(
      esActivationStore
        .listActivationsInNamespace(EntityPath(""), 0, 0, context = UserContext(user, defaultLogStoreHttpRequest)))
    a[RuntimeException] should be thrownBy await(
      esActivationStore.listActivationsMatchingName(
        EntityPath(""),
        EntityPath(""),
        0,
        0,
        context = UserContext(user, defaultLogStoreHttpRequest)))
    a[RuntimeException] should be thrownBy await(
      esActivationStore
        .countActivationsInNamespace(EntityPath(""), None, 0, context = UserContext(user, defaultLogStoreHttpRequest)))
  }

  it should "fail when loading out of box configs since whisk.activationstore.elasticsearch does not exist" in {
    a[ConfigReaderException[_]] should be thrownBy new ArtifactElasticSearchActivationStore(
      system,
      materializer,
      logging)
  }

  it should "error when configuration protocol is invalid" in {
    val invalidHostConfig =
      ElasticSearchActivationStoreConfig("protocol", "host", 443, "/whisk_user_logs", defaultSchema, Seq.empty)

    a[IllegalArgumentException] should be thrownBy new ArtifactElasticSearchActivationStore(
      system,
      materializer,
      logging,
      elasticSearchConfig = invalidHostConfig)
  }

}
