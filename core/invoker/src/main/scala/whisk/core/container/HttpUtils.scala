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

package whisk.core.container

import java.nio.charset.StandardCharsets

import scala.concurrent.duration.FiniteDuration
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

import spray.json.JsObject
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
 * @param timeoutMsec the timeout in msecs to wait for a response
 * @param maxResponse the maximum size in bytes the connection will accept
 */
protected[core] class HttpUtils(
    hostname: String,
    timeout: FiniteDuration,
    maxResponse: ByteSize) {

    def close = Try(connection.close)

    /**
     * Posts to hostname/endpoint the given JSON object.
     * Waits up to timeout before aborting on a good connection.
     * If the endpoint is not ready, retry up to timeout.
     * Every retry reduces the available timeout so that this method should not
     * wait longer than the total timeout (within a small slack allowance).
     *
     * @param endpoint the path the api call relative to hostname
     * @param body the json object to post
     * @param retry whether or not to retry on connection failure
     * @return Left(Error Message) or Right(Status Code, Response as UTF-8 String)
     */
    def post(endpoint: String, body: JsObject, retry: Boolean): Either[ContainerConnectionError, ContainerResponse] = {
        val entity = new StringEntity(body.compactPrint, StandardCharsets.UTF_8)
        entity.setContentType("application/json")

        val request = new HttpPost(baseUri.setPath(endpoint).build)
        request.addHeader(HttpHeaders.ACCEPT, "application/json")
        request.setEntity(entity)

        execute(request, timeout.toMillis.toInt, retry)
    }

    private def execute(request: HttpRequestBase, timeoutMsec: Integer, retry: Boolean): Either[ContainerConnectionError, ContainerResponse] = {
        Try(connection.execute(request)).map { response =>
            val containerResponse = Option(response.getEntity).map { entity =>
                val statusCode = response.getStatusLine.getStatusCode
                val contentLength = entity.getContentLength

                if (contentLength >= 0) {
                    val bytesToRead = Math.min(contentLength, maxResponseBytes)
                    val bytes = IOUtils.toByteArray(entity.getContent, bytesToRead)
                    val str = new String(bytes, StandardCharsets.UTF_8)
                    val truncated = if (contentLength <= maxResponseBytes) None else Some(contentLength.B, maxResponse)
                    Right(ContainerResponse(statusCode == 200, str, truncated))
                } else {
                    Left(NoResponseReceived())
                }
            }.getOrElse {
                // entity is null
                Left(NoResponseReceived())
            }

            response.close()
            containerResponse
        } match {
            case Success(r) => r
            case Failure(t: HttpHostConnectException) if retry =>
                if (timeoutMsec > 0) {
                    Thread sleep 100
                    val newTimeout = timeoutMsec - 100
                    execute(request, newTimeout, retry)
                } else {
                    Left(Timeout())
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

    private val connection = HttpClientBuilder
        .create
        .setDefaultRequestConfig(httpconfig)
        .build
}
