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

package org.apache.openwhisk.core.containerpool

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.model.MessageEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.StreamTcpException
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal
import spray.json._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.LoggingMarkers.CONTAINER_CLIENT_RETRIES
import org.apache.openwhisk.common.MetricEmitter
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.ActivationResponse.ContainerHttpError
import org.apache.openwhisk.core.entity.ActivationResponse._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size.SizeLong
import org.apache.openwhisk.http.PoolingRestClient
import java.time.Instant

/**
 * This HTTP client is used only in the invoker to communicate with the action container.
 * It allows to POST a JSON object and receive JSON object back; that is the
 * content type and the accept headers are both 'application/json.
 * This implementation uses the akka http host-level client API.
 * NOTE: Keepalive is disabled to prevent issues with paused containers
 *
 * @param hostname the host name
 * @param port the port
 * @param timeout the timeout in msecs to wait for a response
 * @param maxResponse the maximum size in bytes the connection will accept
 * @param queueSize once all connections are used, how big of queue to allow for additional requests
 * @param retryInterval duration between retries for TCP connection errors
 */
protected class AkkaContainerClient(
  hostname: String,
  port: Int,
  timeout: FiniteDuration,
  maxResponse: ByteSize,
  truncation: ByteSize,
  queueSize: Int,
  retryInterval: FiniteDuration = 100.milliseconds)(implicit logging: Logging, as: ActorSystem)
    extends PoolingRestClient("http", hostname, port, queueSize, timeout = Some(timeout))
    with ContainerClient {

  def close() = shutdown()

  /**
   * Posts to hostname/endpoint the given JSON object.
   * Waits up to timeout before aborting on a good connection.
   * If the endpoint is not ready, retry up to timeout.
   * Every retry reduces the available timeout so that this method should not
   * wait longer than the total timeout (within a small slack allowance).
   *
   * @param endpoint the path the api call relative to hostname
   * @param body the JSON value to post (this is usually a JSON objecT)
   * @param retry whether or not to retry on connection failure
   * @param reschedule whether or not to throw ContainerHealthError (triggers reschedule) on connection failure
   * @return Left(Error Message) or Right(Status Code, Response as UTF-8 String)
   */
  def post(endpoint: String, body: JsValue, retry: Boolean, reschedule: Boolean = false)(
    implicit tid: TransactionId): Future[Either[ContainerHttpError, ContainerResponse]] = {

    //create the request
    val req = Marshal(body).to[MessageEntity].map { b =>
      HttpRequest(HttpMethods.POST, endpoint, entity = b)
        .withHeaders(Accept(MediaTypes.`application/json`))
    }

    retryingRequest(req, timeout, retry, reschedule, endpoint)
      .flatMap {
        case (response, retries) => {
          if (retries > 0) {
            logging.debug(this, s"completed request to $endpoint after $retries retries")
            MetricEmitter.emitHistogramMetric(CONTAINER_CLIENT_RETRIES, retries)
          }

          response.entity.contentLengthOption match {
            case Some(contentLength) if response.status != StatusCodes.NoContent =>
              if (contentLength <= maxResponse.toBytes) {
                Unmarshal(response.entity.withSizeLimit(maxResponse.toBytes)).to[String].map { o =>
                  Right(ContainerResponse(response.status.intValue, o, None))
                }
              } else {
                truncated(response.entity.dataBytes).map { s =>
                  Right(ContainerResponse(response.status.intValue, s, Some(contentLength.B, maxResponse)))
                }
              }
            case _ =>
              //handle missing Content-Length as NoResponseReceived
              //also handle 204 as NoResponseReceived, for parity with ApacheBlockingContainerClient client
              //per https://github.com/akka/akka-http/issues/1459, don't use discardEntityBytes!
              //(discardEntityBytes was causing failures in WskUnicodeTests)
              response.entity.dataBytes.runWith(Sink.ignore).map(_ => Left(NoResponseReceived()))
          }
        }
      }
      .recoverWith {
        case t: TimeoutException =>
          Future.successful(Left(Timeout(t)))
        case t: ContainerHealthError =>
          //propagate as a failed future; clients can retry at a different container
          Future.failed(t)
        case NonFatal(t) =>
          Future.successful(Left(ConnectionError(t)))
      }
  }
  //returns a Future HttpResponse -> Int (where Int is the retryCount)
  private def retryingRequest(req: Future[HttpRequest],
                              timeout: FiniteDuration,
                              retry: Boolean,
                              reschedule: Boolean,
                              endpoint: String,
                              retryCount: Int = 0)(implicit tid: TransactionId): Future[(HttpResponse, Int)] = {
    val start = Instant.now

    request(req)
      .map((_, retryCount))
      .recoverWith {
        case _: StreamTcpException if reschedule =>
          Future.failed(ContainerHealthError(tid, endpoint))
        case t: StreamTcpException if retry =>
          if (timeout > Duration.Zero) {
            akka.pattern.after(retryInterval, as.scheduler)({
              val newTimeout = timeout - (Instant.now.toEpochMilli - start.toEpochMilli).milliseconds
              retryingRequest(req, newTimeout, retry, reschedule, endpoint, retryCount + 1)
            })
          } else {
            logging.warn(
              this,
              s"POST failed after $retryCount retries with $t - no more retries because timeout exceeded.")
            Future.failed(new TimeoutException(t.getMessage))
          }
      }
  }

  private def truncated(responseBytes: Source[ByteString, _],
                        previouslyCaptured: ByteString = ByteString.empty): Future[String] = {
    responseBytes.prefixAndTail(1).runWith(Sink.head).flatMap {
      case (Nil, tail) =>
        //ignore the tail (MUST CONSUME ENTIRE ENTITY!)
        tail.runWith(Sink.ignore).map(_ => previouslyCaptured.utf8String)
      case (Seq(prefix), tail) =>
        val truncatedResponse = previouslyCaptured ++ prefix
        if (truncatedResponse.size < truncation.toBytes) {
          truncated(tail, truncatedResponse)
        } else {
          //ignore the tail (MUST CONSUME ENTIRE ENTITY!)
          //captured string MAY be larger than the truncation size, so take only truncation bytes to get the exact length
          tail.runWith(Sink.ignore).map(_ => truncatedResponse.take(truncation.toBytes.toInt).utf8String)
        }
    }
  }
}

object AkkaContainerClient {

  /** A helper method to post one single request to a connection. Used for container tests. */
  def post(host: String, port: Int, endPoint: String, content: JsValue, timeout: FiniteDuration)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext,
    tid: TransactionId): (Int, Option[JsObject]) = {
    val connection = new AkkaContainerClient(host, port, timeout, 1.MB, 1.MB, 1)
    val response = executeRequest(connection, endPoint, content)
    val result = Await.result(response, timeout + 10.seconds) //additional timeout to complete futures
    connection.close()
    result
  }

  /** A helper method to post multiple concurrent requests to a single connection. Used for container tests. */
  def concurrentPost(host: String, port: Int, endPoint: String, contents: Seq[JsValue], timeout: FiniteDuration)(
    implicit logging: Logging,
    tid: TransactionId,
    as: ActorSystem,
    ec: ExecutionContext): Seq[(Int, Option[JsObject])] = {
    val connection = new AkkaContainerClient(host, port, timeout, 1.MB, 1.MB, 1)
    val futureResults = contents.map { executeRequest(connection, endPoint, _) }
    val results = Await.result(Future.sequence(futureResults), timeout + 10.seconds) //additional timeout to complete futures
    connection.close()
    results
  }

  private def executeRequest(connection: AkkaContainerClient, endpoint: String, content: JsValue)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext,
    tid: TransactionId): Future[(Int, Option[JsObject])] = {

    val res = connection
      .post(endpoint, content, true)
      .map({
        case Right(r)                   => (r.statusCode, Try(r.entity.parseJson.asJsObject).toOption)
        case Left(NoResponseReceived()) => throw new IllegalStateException("no response from container")
        case Left(Timeout(_))           => throw new java.util.concurrent.TimeoutException()
        case Left(ConnectionError(t: java.net.SocketTimeoutException)) =>
          throw new java.util.concurrent.TimeoutException()
        case Left(ConnectionError(t)) => throw new IllegalStateException(t.getMessage)
      })

    res
  }
}
