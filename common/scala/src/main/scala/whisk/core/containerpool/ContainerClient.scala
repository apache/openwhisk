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

package whisk.core.containerpool

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MessageEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Connection
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
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.ActivationResponse.ContainerHttpError
import whisk.core.entity.ActivationResponse._
import whisk.core.entity.ByteSize
import whisk.core.entity.size.SizeLong
import whisk.http.PoolingRestClient

trait ContainerClient {
  def close(): Unit
  def post(endpoint: String, body: JsValue, retry: Boolean)(
    implicit tid: TransactionId): Future[Either[ContainerHttpError, ContainerResponse]]

}

/**
 * This HTTP client is used only in the invoker to communicate with the action container.
 * It allows to POST a JSON object and receive JSON object back; that is the
 * content type and the accept headers are both 'application/json.
 * The reason we still use this class for the action container is a mysterious hang
 * in the Akka http client where a future fails to properly timeout and we have not
 * determined why that is.
 *
 * @param hostname the host name
 * @param timeout the timeout in msecs to wait for a response
 * @param maxResponse the maximum size in bytes the connection will accept
 * @param queueSize once all connections are used, how big of queue to allow for additional requests
 * @param retryInterval duration between retries for TCP connection errors
 */
protected class PoolingContainerClient(
  hostname: String,
  port: Int,
  timeout: FiniteDuration,
  maxResponse: ByteSize,
  queueSize: Int,
  retryInterval: FiniteDuration = 100.milliseconds)(implicit logging: Logging, as: ActorSystem)
    extends PoolingRestClient("http", hostname, port, queueSize, timeout = Some(timeout))
    with ContainerClient
    with AutoCloseable {

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
   * @return Left(Error Message) or Right(Status Code, Response as UTF-8 String)
   */
  def post(endpoint: String, body: JsValue, retry: Boolean)(
    implicit tid: TransactionId): Future[Either[ContainerHttpError, ContainerResponse]] = {

    //create the request
    val req = Marshal(body).to[MessageEntity].map { b =>
      //DO NOT reuse the connection (in case of paused containers)
      //For details on Connection: Close handling, see:
      // - https://doc.akka.io/docs/akka-http/current/common/http-model.html#http-headers
      // - http://github.com/akka/akka-http/tree/v10.1.3/akka-http-core/src/test/scala/akka/http/impl/engine/rendering/ResponseRendererSpec.scala#L470-L571
      HttpRequest(HttpMethods.POST, endpoint, entity = b).withHeaders(Connection("close"))
    }

    retryingRequest(req, timeout, retry)
      .flatMap(r => {
        if (r._2 > 0) {
          logging.info(this, s"completed after ${r._2} retries")
        }
        val response = r._1
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
            //also handle 204 as NoResponseReceived, for parity with HttpUtils client
            Future { Left(NoResponseReceived()) }
        }
      })
      .recover {
        case t: StreamTcpException => Left(Timeout(t))
        case t: TimeoutException   => Left(Timeout(t))
        case NonFatal(t)           => Left(ConnectionError(t))
      }
  }
  //returns a Future HttpResponse -> Int (where Int is the retryCount)
  private def retryingRequest(req: Future[HttpRequest],
                              timeout: FiniteDuration,
                              retry: Boolean,
                              retryCount: Int = 0): Future[(HttpResponse, Int)] = {
    request(req)
      .map((_, retryCount))
      .recoverWith {
        case t: akka.stream.StreamTcpException if retry =>
          val newTimeout = timeout - retryInterval
          if (newTimeout > Duration.Zero) {
            akka.pattern.after(retryInterval, as.scheduler)(retryingRequest(req, newTimeout, retry, retryCount + 1))
          } else {
            logging.warn(
              this,
              s"POST failed after $retryCount retries with $t - no more retries because timeout exceeded.")
            Future.failed(t)
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
        if (truncatedResponse.size < maxResponse.toBytes) {
          truncated(tail, truncatedResponse)
        } else {
          //ignore the tail (MUST CONSUME ENTIRE ENTITY!)
          //captured string MAY be larger than the max response, so take only maxResponse bytes to get the exact length
          tail.runWith(Sink.ignore).map(_ => truncatedResponse.take(maxResponse.toBytes.toInt).utf8String)
        }
    }
  }
}

object PoolingContainerClient {

  /** A helper method to post one single request to a connection. Used for container tests. */
  def post(host: String, port: Int, endPoint: String, content: JsValue, timeout: Duration = 30.seconds)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext,
    tid: TransactionId): (Int, Option[JsObject]) = {
    val connection = new PoolingContainerClient(host, port, 90.seconds, 1.MB, 1)
    val response = executeRequest(connection, endPoint, content)
    connection.close()
    Await.result(response, timeout)
  }

  /** A helper method to post multiple concurrent requests to a single connection. Used for container tests. */
  def concurrentPost(host: String, port: Int, endPoint: String, contents: Seq[JsValue], timeout: Duration)(
    implicit logging: Logging,
    tid: TransactionId,
    as: ActorSystem,
    ec: ExecutionContext): Seq[(Int, Option[JsObject])] = {
    val connection = new PoolingContainerClient(host, port, 90.seconds, 1.MB, 1)
    val futureResults = contents.map { executeRequest(connection, endPoint, _) }
    val results = Await.result(Future.sequence(futureResults), timeout)
    connection.close()
    results
  }

  private def executeRequest(connection: PoolingContainerClient, endpoint: String, content: JsValue)(
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
