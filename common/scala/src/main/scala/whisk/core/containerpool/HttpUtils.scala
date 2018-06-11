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

import java.net.NoRouteToHostException
import java.nio.charset.StandardCharsets

import scala.concurrent.duration._
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.apache.commons.io.IOUtils
import org.apache.http.HttpHeaders
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.utils.URIBuilder
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import spray.json._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.ActivationResponse._
import whisk.core.entity.ByteSize
import whisk.core.entity.size.SizeLong

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
 * @param maxConcurrent the maximum number of concurrent requests allowed (Default is 1)
 */
protected class HttpUtils(hostname: String, timeout: FiniteDuration, maxResponse: ByteSize, maxConcurrent: Int = 1)(
  implicit logging: Logging) {

  def close() = Try(connection.close())

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
    implicit tid: TransactionId): Either[ContainerHttpError, ContainerResponse] = {
    val entity = new StringEntity(body.compactPrint, StandardCharsets.UTF_8)
    entity.setContentType("application/json")

    val request = new HttpPost(baseUri.setPath(endpoint).build)
    request.addHeader(HttpHeaders.ACCEPT, "application/json")
    request.setEntity(entity)

    execute(request, timeout, maxConcurrent, retry)
  }

  // Used internally to wrap all exceptions for which the request can be retried
  private case class RetryableConnectionError(t: Throwable) extends Exception(t) with NoStackTrace

  // Annotation will make the compiler complain if no tail recursion is possible
  @tailrec private def execute(request: HttpRequestBase, timeout: FiniteDuration, maxConcurrent: Int, retry: Boolean)(
    implicit tid: TransactionId): Either[ContainerHttpError, ContainerResponse] = {
    Try(connection.execute(request)).map { response =>
      val containerResponse = Option(response.getEntity)
        .map { entity =>
          val statusCode = response.getStatusLine.getStatusCode
          val contentLength = entity.getContentLength

          if (contentLength >= 0) {
            val bytesToRead = Math.min(contentLength, maxResponseBytes)
            val bytes = IOUtils.toByteArray(entity.getContent, bytesToRead)
            val str = new String(bytes, StandardCharsets.UTF_8)
            val truncated = if (contentLength <= maxResponseBytes) None else Some(contentLength.B, maxResponse)
            Right(ContainerResponse(statusCode, str, truncated))
          } else {
            Left(NoResponseReceived())
          }
        }
        .getOrElse {
          // entity is null
          Left(NoResponseReceived())
        }

      response.close()
      containerResponse
    } recoverWith {
      // The route to target socket as well as the target socket itself may need some time to be available -
      // particularly on a loaded system.
      // The following exceptions occur on such transient conditions. In addition, no data has been transmitted
      // yet if these exceptions occur. For this reason, it is safe and reasonable to retry.
      //
      // HttpHostConnectException: no target socket is listening (yet).
      case t: HttpHostConnectException => Failure(RetryableConnectionError(t))
      //
      // NoRouteToHostException: route to target host is not known (yet).
      case t: NoRouteToHostException => Failure(RetryableConnectionError(t))
    } match {
      case Success(response) => response
      case Failure(t: RetryableConnectionError) if retry =>
        val sleepTime = 50.milliseconds
        if (timeout > Duration.Zero) {
          Thread.sleep(sleepTime.toMillis)
          val newTimeout = timeout - sleepTime
          execute(request, newTimeout, maxConcurrent, retry = true)
        } else {
          logging.warn(this, s"POST failed with $t - no retry because timeout exceeded.")
          Left(Timeout(t))
        }
      case Failure(t: Throwable) => Left(ConnectionError(t))
    }
  }

  private val maxResponseBytes = maxResponse.toBytes

  private val baseUri = new URIBuilder()
    .setScheme("http")
    .setHost(hostname)

  private val httpconfig = RequestConfig.custom
    .setConnectTimeout(timeout.toMillis.toInt)
    .setConnectionRequestTimeout(timeout.toMillis.toInt)
    .setSocketTimeout(timeout.toMillis.toInt)
    .build

  private val connection = HttpClientBuilder.create
    .setDefaultRequestConfig(httpconfig)
    .setConnectionManager(if (maxConcurrent > 1) {
      // Use PoolingHttpClientConnectionManager so that concurrent activation processing (if enabled) will reuse connections
      val cm = new PoolingHttpClientConnectionManager
      // Increase default max connections per route (default is 2)
      cm.setDefaultMaxPerRoute(maxConcurrent)
      // Increase max total connections (default is 20)
      cm.setMaxTotal(maxConcurrent)
      cm
    } else null) //set the Pooling connection manager IFF maxConcurrent > 1
    .useSystemProperties()
    .disableAutomaticRetries()
    .build
}

object HttpUtils {

  /** A helper method to post one single request to a connection. Used for container tests. */
  def post(host: String, port: Int, endPoint: String, content: JsValue)(implicit logging: Logging,
                                                                        tid: TransactionId): (Int, Option[JsObject]) = {
    val connection = new HttpUtils(s"$host:$port", 90.seconds, 1.MB)
    val response = executeRequest(connection, endPoint, content)
    connection.close()
    response
  }

  /** A helper method to post multiple concurrent requests to a single connection. Used for container tests. */
  def concurrentPost(host: String, port: Int, endPoint: String, contents: Seq[JsValue], timeout: Duration)(
    implicit logging: Logging,
    tid: TransactionId,
    ec: ExecutionContext): Seq[(Int, Option[JsObject])] = {
    val connection = new HttpUtils(s"$host:$port", 90.seconds, 1.MB, contents.size)
    val futureResults = contents.map(content => Future { executeRequest(connection, endPoint, content) })
    val results = Await.result(Future.sequence(futureResults), timeout)
    connection.close()
    results
  }

  private def executeRequest(connection: HttpUtils, endpoint: String, content: JsValue)(
    implicit logging: Logging,
    tid: TransactionId): (Int, Option[JsObject]) = {
    connection.post(endpoint, content, retry = true) match {
      case Right(r)                   => (r.statusCode, Try(r.entity.parseJson.asJsObject).toOption)
      case Left(NoResponseReceived()) => throw new IllegalStateException("no response from container")
      case Left(Timeout(_))           => throw new java.util.concurrent.TimeoutException()
      case Left(ConnectionError(t: java.net.SocketTimeoutException)) =>
        throw new java.util.concurrent.TimeoutException()
      case Left(ConnectionError(t)) => throw new IllegalStateException(t.getMessage)
    }
  }
}
