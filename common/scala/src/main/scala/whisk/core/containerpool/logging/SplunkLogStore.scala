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
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.FormData
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.Authorization
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.OverflowStrategy
import akka.stream.QueueOfferResult
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.typesafe.sslconfig.akka.AkkaSSLConfig
import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import spray.json.DefaultJsonProtocol._
import spray.json.JsArray
import spray.json._
import whisk.core.entity.ActivationLogs
import whisk.core.entity.WhiskActivation

/**
 * A Splunk based impl of LogDriverLogStore. Logs are routed to splunk via docker log driver, and retrieved via Splunk REST API
 * @param actorSystem
 */
class SplunkLogStore(actorSystem: ActorSystem) extends LogDriverLogStore {
  implicit val as = actorSystem
  implicit val ec = as.dispatcher
  implicit val materializer = ActorMaterializer()

  private val splunkHost = config.getString("whisk.logstore.splunk.host")
  private val splunkPort = config.getInt("whisk.logstore.splunk.port")
  private val splunkApi = "/services/search/jobs" //see http://docs.splunk.com/Documentation/Splunk/6.6.3/RESTREF/RESTsearch#search.2Fjobs
  private val splunkUser = config.getString("whisk.logstore.splunk.user")
  private val splunkPass = config.getString("whisk.logstore.splunk.password")
  private val splunkIndex = config.getString("whisk.logstore.splunk.index")
  private val logMessageFieldName = config.getString("whisk.logstore.splunk.log-message-field")
  private val activationIdFieldName = config.getString("whisk.logstore.splunk.activation-id-field")

  val badSslConfig = AkkaSSLConfig().mapSettings(s => s.withLoose(s.loose.withDisableSNI(true)))
  val insecure = Http().createClientHttpsContext(badSslConfig)

  override def logs(activation: WhiskActivation): Future[ActivationLogs] = {

    val search =
      s"""search index="${splunkIndex}"| spath ${activationIdFieldName} | search ${activationIdFieldName}=${activation.activationId.toString} | table ${logMessageFieldName}"""
    println(s"search: ${search}")
    val entity = FormData(
      Map(
        "exec_mode" -> "oneshot",
        "search" -> search,
        "output_mode" -> "json",
        "earliest_time" -> "2017-08-29T12:00:00",
        "latest_time" -> "2017-10-29T12:00:00")).toEntity

    println("sending request")
    queueRequest(
      Post(splunkApi)
        .withEntity(entity)
        .withHeaders(List(Authorization(BasicHttpCredentials(splunkUser, splunkPass))))).flatMap(response => {
      println(s"got response ${response}")
      val finished = Instant.now()
      if (response.status.isSuccess()) {
        Unmarshal(response.entity)
          .to[String]
          .map(resultsString => {
            println(s"got results: ${resultsString}")
            val jsObject = JsonParser(resultsString).asJsObject
            //format of results is detailed here: http://docs.splunk.com/Documentation/Splunk/latest/RESTUM/RESTusing#Example_B:_JSON_response_format_example
            val messages = jsObject
              .fields("results")
              .convertTo[JsArray]
              .elements
              .map(msgJsValue => {
                msgJsValue.asJsObject.fields(logMessageFieldName).asInstanceOf[JsString].value
              })
            new ActivationLogs(messages)
          })
      } else {
        Future.failed(new RuntimeException(s"failed to read logs from splunk ${response}"))
      }
    })

  }

  //based on http://doc.akka.io/docs/akka-http/10.0.6/scala/http/client-side/host-level.html
  val maxPendingRequests = 500
  val poolClientFlow = Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](
    host = splunkHost,
    port = splunkPort,
    connectionContext = insecure)
  val queue =
    Source
      .queue[(HttpRequest, Promise[HttpResponse])](maxPendingRequests, OverflowStrategy.backpressure)
      .via(poolClientFlow)
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => p.success(resp)
        case ((Failure(e), p))    => p.failure(e)
      }))(Keep.left)
      .run()

  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued => responsePromise.future
      case QueueOfferResult.Dropped =>
        Future.failed(new RuntimeException("Splunk API Client Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed =>
        Future.failed(
          new RuntimeException(
            "Splunk API Client Queue was closed (pool shut down) while running the request. Try again later."))
    }
  }
}
object SplunkLogStoreProvider extends LogStoreProvider {
  var cachedLogStore: Option[LogStore] = None
  override def logStore(actorSystem: ActorSystem) =
    cachedLogStore.getOrElse({
      val logStore = new SplunkLogStore(actorSystem)
      cachedLogStore = Some(logStore)
      logStore
    })
}
