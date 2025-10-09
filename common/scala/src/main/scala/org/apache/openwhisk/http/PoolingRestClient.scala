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

package org.apache.openwhisk.http

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.marshalling._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.settings.ConnectionPoolSettings
import org.apache.pekko.http.scaladsl.unmarshalling._
import org.apache.pekko.stream.scaladsl.{Flow, _}
import org.apache.pekko.stream.{KillSwitches, QueueOfferResult}
import org.apache.openwhisk.common.PekkoLogging
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Http client to talk to a known host.
 *
 * This class only handles the basic communication to the proper endpoints. It is up to its clients to interpret the
 * results. It is built on akka-http host-level connection pools; compared to single requests, it saves some time
 * on each request because it doesn't need to look up the pool corresponding to the host. It is also easier to add an
 * extra queueing mechanism.
 */
class PoolingRestClient(
  protocol: String,
  host: String,
  port: Int,
  queueSize: Int,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None,
  timeout: Option[FiniteDuration] = None)(implicit system: ActorSystem, ec: ExecutionContext) {
  require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

  private val logging = new PekkoLogging(system.log)

  //if specified, override the ClientConnection idle-timeout and keepalive socket option value
  private val timeoutSettings = {
    val cps = ConnectionPoolSettings(system.settings.config)
    timeout
      .map { t =>
        cps
          .withMaxConnectionBackoff(cps.maxConnectionBackoff.min(t))
          .withUpdatedConnectionSettings(_.withIdleTimeout(t))
      }
      .getOrElse(cps)
  }

  // Creates or retrieves a connection pool for the host.
  private val pool = if (protocol == "http") {
    Http().cachedHostConnectionPool[Promise[HttpResponse]](host = host, port = port, settings = timeoutSettings)
  } else {
    Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](host = host, port = port, settings = timeoutSettings)
  }

  // Additional queue in case all connections are busy. Should hardly ever be
  // filled in practice but can be useful, e.g., in tests starting many
  // asynchronous requests in a very short period of time.
  private val ((requestQueue, killSwitch), sinkCompletion) = Source
    .queue(queueSize)
    .via(httpFlow.getOrElse(pool))
    .viaMat(KillSwitches.single)(Keep.both)
    .toMat(Sink.foreach({
      case (Success(response), p) =>
        p.success(response)
      case (Failure(error), p) =>
        p.failure(error)
    }))(Keep.both)
    .run()

  sinkCompletion.onComplete(_ => shutdown())

  /**
   * Execute an HttpRequest on the underlying connection pool.
   *
   * WARNING: It is **very** important that the resulting entity is either drained or discarded fully, so the connection
   * can be reused. Otherwise, the pool will dry up.
   *
   * @return a future holding the response from the server.
   */
  def request(futureRequest: Future[HttpRequest]): Future[HttpResponse] = futureRequest.flatMap { request =>
    val promise = Promise[HttpResponse]

    // When the future completes, we know whether the request made it
    // through the queue.
    requestQueue.offer(request -> promise) match {
      case QueueOfferResult.Enqueued    => promise.future
      case QueueOfferResult.Dropped     => Future.failed(new Exception("Request queue is full."))
      case QueueOfferResult.QueueClosed => Future.failed(new Exception("Request queue was closed."))
      case QueueOfferResult.Failure(f)  => Future.failed(f)
    }
  }

  /**
   * Execute an HttpRequest on the underlying connection pool and return an unmarshalled result.
   *
   * @return either the unmarshalled result or a status code, if the status code is not a success (2xx class)
   */
  def requestJson[T: RootJsonReader](futureRequest: Future[HttpRequest]): Future[Either[StatusCode, T]] =
    request(futureRequest).flatMap { response =>
      if (response.status.isSuccess) {
        Unmarshal(response.entity.withoutSizeLimit).to[T].map(Right.apply)
      } else {
        Unmarshal(response.entity).to[String].flatMap { body =>
          val statusCode = response.status
          val reason =
            if (body.nonEmpty) s"${statusCode.reason} (details: $body)" else statusCode.reason
          val customStatusCode = StatusCodes
            .custom(intValue = statusCode.intValue, reason = reason, defaultMessage = statusCode.defaultMessage)
          // This is important, as it drains the entity stream.
          // Otherwise the connection stays open and the pool dries up.
          response.discardEntityBytes().future.map(_ => Left(customStatusCode))
        }
      }
    }

  def shutdown(): Future[Unit] = {
    killSwitch.shutdown()
    Try(requestQueue.complete()).recover {
      case t: IllegalStateException => logging.warn(this, t.getMessage)
    }
    Future.unit
  }
}

object PoolingRestClient {

  def mkRequest(method: HttpMethod,
                uri: Uri,
                body: Future[MessageEntity] = Future.successful(HttpEntity.Empty),
                headers: List[HttpHeader] = List.empty)(implicit ec: ExecutionContext): Future[HttpRequest] = {
    body.map { b =>
      HttpRequest(method, uri, headers, b)
    }
  }

  def mkJsonRequest(method: HttpMethod, uri: Uri, body: JsValue, headers: List[HttpHeader] = List.empty)(
    implicit ec: ExecutionContext): Future[HttpRequest] = {
    val b = Marshal(body).to[MessageEntity]
    mkRequest(method, uri, b, headers)
  }
}
