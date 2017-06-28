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

package common

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import play.api.http.{ContentTypeOf, Writeable}
import play.api.libs.ws.WSResponse
import play.api.libs.ws.ahc.AhcWSClient
import play.api.mvc.Codec
import spray.json.JsValue

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.util.Try

object AkkaHttpUtils {

    // Writable for spray-json is required
    implicit def sprayJsonContentType(implicit codec: Codec): ContentTypeOf[JsValue] = {
        ContentTypeOf[JsValue](Some(ContentTypes.`application/json`.toString()))
    }
    implicit def sprayJsonWriteable(implicit codec: Codec): Writeable[JsValue] = {
        Writeable(message => codec.encode(message.toString()))
    }

    def singleRequestBlocking(
        uri: String,
        content: JsValue,
        timeout: FiniteDuration,
        retryOnTCPErrors: Boolean = false,
        retryOn4xxErrors: Boolean = false,
        retryOn5xxErrors: Boolean = false,
        retryInterval: FiniteDuration = 100.milliseconds)
        (implicit system: ActorSystem) : Try[WSResponse] = {

        val f = singleRequest(
            uri, content, timeout, retryOnTCPErrors, retryOn4xxErrors, retryOn5xxErrors, retryInterval
        )

        // Duration.Inf is not an issue, since singleRequest has a built-in timeout mechanism.
        Await.ready(f, Duration.Inf)

        f.value.get
    }

    // Makes a request, expects a successful within timeout, retries on selected
    // errors until timeout has passed.
    def singleRequest(
         uri: String,
         content: JsValue,
         timeout: FiniteDuration,
         retryOnTCPErrors: Boolean = false,
         retryOn4xxErrors: Boolean = false,
         retryOn5xxErrors: Boolean = false,
         retryInterval: FiniteDuration = 100.milliseconds)
        (implicit system: ActorSystem) : Future[WSResponse] = {
        implicit val executionContext = system.dispatcher
        implicit val materializer = ActorMaterializer()
        val wsClient = AhcWSClient()

        val timeoutException = new TimeoutException(s"Request to ${uri} could not be completed in time.")

        val promise = Promise[WSResponse]

        // Timeout includes all retries.
        system.scheduler.scheduleOnce(timeout) {
            promise.tryFailure(timeoutException)
        }

        def tryOnce() : Unit = if(!promise.isCompleted) {
            val f = wsClient.url(uri).withRequestTimeout(timeout).post(content)
            f.onSuccess {
                case r if r.status >= 400 && r.status < 500 && retryOn4xxErrors =>
                    system.scheduler.scheduleOnce(retryInterval) { tryOnce() }
                case r if r.status >= 500 && r.status < 600 && retryOn5xxErrors =>
                    system.scheduler.scheduleOnce(retryInterval) { tryOnce() }
                case r =>
                    wsClient.close()
                    promise.trySuccess(r)
            }

            f.onFailure {
                case s : java.net.ConnectException if retryOnTCPErrors =>
                    // TCP error (e.g. connection couldn't be opened)
                    system.scheduler.scheduleOnce(retryInterval) { tryOnce() }

                case t : Throwable =>
                    // Other error. We fail the promise.
                    promise.tryFailure(t)
            }
        }

        tryOnce()

        promise.future
    }
}
