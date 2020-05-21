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

import java.net.NoRouteToHostException
import java.nio.charset.StandardCharsets
import java.time.Instant

import org.apache.commons.io.IOUtils
import org.apache.http.{HttpHeaders, NoHttpResponseException}
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.{HttpPost, HttpRequestBase}
import org.apache.http.client.utils.{HttpClientUtils, URIBuilder}
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.entity.StringEntity
import org.apache.http.impl.NoConnectionReuseStrategy
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager
import org.apache.http.util.EntityUtils
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.ActivationResponse._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size.SizeLong
import pureconfig._
import pureconfig.generic.auto._

import scala.annotation.tailrec
import scala.concurrent._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.util.control.NoStackTrace

// Used internally to wrap all exceptions for which the request can be retried
protected[containerpool] case class RetryableConnectionError(t: Throwable) extends Exception(t) with NoStackTrace

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
protected class ApacheBlockingContainerClient(hostname: String,
                                              timeout: FiniteDuration,
                                              maxResponse: ByteSize,
                                              truncation: ByteSize,
                                              maxConcurrent: Int = 1)(implicit logging: Logging, ec: ExecutionContext)
    extends ContainerClient {

  /**
   * Closes the HttpClient and all resources allocated by it.
   *
   * This will close the HttpClient that is generated for this instance of ApacheBlockingContainerClient. That will also cause the
   * ConnectionManager to be closed alongside.
   */
  def close(): Future[Unit] = Future.successful(HttpClientUtils.closeQuietly(connection))

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
  def post(endpoint: String, body: JsValue, retry: Boolean, reschedule: Boolean = false)(
    implicit tid: TransactionId): Future[Either[ContainerHttpError, ContainerResponse]] = {
    val entity = new StringEntity(body.compactPrint, StandardCharsets.UTF_8)
    entity.setContentType("application/json")

    val request = new HttpPost(baseUri.setPath(endpoint).build)
    request.addHeader(HttpHeaders.ACCEPT, "application/json")
    request.setEntity(entity)

    Future {
      blocking {
        execute(request, timeout, maxConcurrent, retry, reschedule)
      }
    }
  }

  // Annotation will make the compiler complain if no tail recursion is possible
  @tailrec private def execute(
    request: HttpRequestBase,
    timeout: FiniteDuration,
    maxConcurrent: Int,
    retry: Boolean,
    reschedule: Boolean = false)(implicit tid: TransactionId): Either[ContainerHttpError, ContainerResponse] = {
    val start = Instant.now

    Try(connection.execute(request)).map { response =>
      val containerResponse = Option(response.getEntity)
        .map { entity =>
          val statusCode = response.getStatusLine.getStatusCode
          val contentLength = entity.getContentLength

          // Negative contentLength means unknown or overflow. We don't want to consume in either case.
          if (contentLength >= 0) {
            if (contentLength <= maxResponseBytes) {
              // optimized route to consume the entire stream into a string
              val str = EntityUtils.toString(entity, StandardCharsets.UTF_8) // consumes and closes the whole stream
              Right(ContainerResponse(statusCode, str, None))
            } else {
              // only consume a bounded number of bytes according to the system limits
              val str = new String(IOUtils.toByteArray(entity.getContent, truncationBytes), StandardCharsets.UTF_8)
              EntityUtils.consumeQuietly(entity) // consume the rest of the stream to free the connection
              Right(ContainerResponse(statusCode, str, Some(contentLength.B, maxResponse)))
            }
          } else {
            EntityUtils.consumeQuietly(entity) // silently consume the whole stream to free the connection
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

      //In general with NoHttpResponseException it cannot be said if server has processed the request or not
      //For some cases like in standalone mode setup it should be fine to retry
      case t: NoHttpResponseException if ApacheBlockingContainerClient.clientConfig.retryNoHttpResponseException =>
        Failure(RetryableConnectionError(t))
    } match {
      case Success(response)                                  => response
      case Failure(_: RetryableConnectionError) if reschedule =>
        //propagate as a failed future; clients can retry at a different container
        throw ContainerHealthError(tid, request.getURI.toString)
      case Failure(t: RetryableConnectionError) if retry =>
        if (timeout > Duration.Zero) {
          Thread.sleep(50) // Sleep for 50 milliseconds
          val newTimeout = timeout - (Instant.now.toEpochMilli - start.toEpochMilli).milliseconds
          execute(request, newTimeout, maxConcurrent, retry = true)
        } else {
          logging.warn(this, s"POST failed with $t - no retry because timeout exceeded.")
          Left(Timeout(t))
        }
      case Failure(t: Throwable) => Left(ConnectionError(t))
    }
  }

  private val maxResponseBytes = maxResponse.toBytes
  private val truncationBytes = truncation.toBytes

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
    // Connections are not reused by most of the available runtimes. To circumvent any issues we might have regarding
    // connections randomly breaking due to our pause/resume cycle, we don't reuse connections at all.
    .setConnectionReuseStrategy(new NoConnectionReuseStrategy)
    .setConnectionManager {
      // A PoolingHttpClientConnectionManager is the default when not specifying any ConnectionManager.
      // The PoolingHttpClientConnectionManager has the benefit of actively checking if a connection has become stale,
      // which is very important because pausing/resuming containers can cause a connection to become silently broken.
      // This causes very subtle bugs, especially when containers are reused after a pretty long time (like > 5 minutes).
      //
      // The BasicHttpClientConnectionManager (which would be alternative here) doesn't have such a mechanism and thus
      // isn't suitable for our usage.
      val cm = new PoolingHttpClientConnectionManager()
      // perRoute effectively means per host in our use-case, which means setting it to the same value as the maximum
      // total of all connections in the pool is appropriate here.
      cm.setDefaultMaxPerRoute(maxConcurrent)
      cm.setMaxTotal(maxConcurrent)
      cm
    }
    .useSystemProperties()
    .disableAutomaticRetries()
    .build
}

case class ApacheClientConfig(retryNoHttpResponseException: Boolean)

object ApacheBlockingContainerClient {
  val clientConfig: ApacheClientConfig = loadConfigOrThrow[ApacheClientConfig](ConfigKeys.apacheClientConfig)

  /** A helper method to post one single request to a connection. Used for container tests. */
  def post(host: String, port: Int, endPoint: String, content: JsValue)(
    implicit logging: Logging,
    tid: TransactionId,
    ec: ExecutionContext): (Int, Option[JsObject]) = {
    val timeout = 90.seconds
    val connection = new ApacheBlockingContainerClient(s"$host:$port", timeout, 1.MB, 1.MB)
    val response = executeRequest(connection, endPoint, content)
    val result = Await.result(response, timeout)
    connection.close()
    result
  }

  /** A helper method to post multiple concurrent requests to a single connection. Used for container tests. */
  def concurrentPost(host: String, port: Int, endPoint: String, contents: Seq[JsValue], timeout: Duration)(
    implicit logging: Logging,
    tid: TransactionId,
    ec: ExecutionContext): Seq[(Int, Option[JsObject])] = {
    val connection = new ApacheBlockingContainerClient(s"$host:$port", 90.seconds, 1.MB, 1.MB, contents.size)
    val futureResults = contents.map { content =>
      executeRequest(connection, endPoint, content)
    }
    val results = Await.result(Future.sequence(futureResults), timeout)
    connection.close()
    results
  }

  private def executeRequest(connection: ApacheBlockingContainerClient, endpoint: String, content: JsValue)(
    implicit logging: Logging,
    tid: TransactionId,
    ec: ExecutionContext): Future[(Int, Option[JsObject])] = {
    connection.post(endpoint, content, retry = true) map {
      case Right(r)                   => (r.statusCode, Try(r.entity.parseJson.asJsObject).toOption)
      case Left(NoResponseReceived()) => throw new IllegalStateException("no response from container")
      case Left(Timeout(_))           => throw new java.util.concurrent.TimeoutException()
      case Left(ConnectionError(_: java.net.SocketTimeoutException)) =>
        throw new java.util.concurrent.TimeoutException()
      case Left(ConnectionError(t)) => throw new IllegalStateException(t.getMessage)
    }

  }
}
