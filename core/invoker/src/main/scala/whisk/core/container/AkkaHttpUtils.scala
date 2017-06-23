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

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import java.util.concurrent.TimeoutException

object AkkaHttpUtils {
    def singleRequestBlocking(
        request: HttpRequest,
        timeout: FiniteDuration,
        retryOnTCPErrors: Boolean = false,
        retryOn4xxErrors: Boolean = false,
        retryOn5xxErrors: Boolean = false,
        retryInterval: FiniteDuration = 100.milliseconds)
        (implicit system: ActorSystem) : Try[HttpResponse] = {

        val f = singleRequest(
            request, timeout, retryOnTCPErrors, retryOn4xxErrors, retryOn5xxErrors, retryInterval
        )

        // Duration.Inf is not an issue, since singleRequest has a built-in timeout mechanism.
        Await.ready(f, Duration.Inf)

        f.value.get
    }

    // Makes a request, expects a successful within timeout, retries on selected
    // errors until timeout has passed.
    def singleRequest(
        request: HttpRequest,
        timeout: FiniteDuration,
        retryOnTCPErrors: Boolean = false,
        retryOn4xxErrors: Boolean = false,
        retryOn5xxErrors: Boolean = false,
        retryInterval: FiniteDuration = 100.milliseconds)
        (implicit system: ActorSystem) : Future[HttpResponse] = {

        implicit val executionContext = system.dispatcher
        implicit val materializer = ActorMaterializer()

        val timeoutException = new TimeoutException(s"Request to ${request.uri.authority} could not be completed in time.")

        val authority = request.uri.authority
        val relativeRequest = request.copy(uri = request.uri.toRelative)
        val flow = Http().outgoingConnection(authority.host.address, authority.port)

        val promise = Promise[HttpResponse]

        // Timeout includes all retries.
        system.scheduler.scheduleOnce(timeout) {
            promise.tryFailure(timeoutException)
        }

        def tryOnce() : Unit = if(!promise.isCompleted) {
            val f = Source.single(relativeRequest).via(flow).runWith(Sink.head)

            f.onSuccess {
                case r if r.status.intValue >= 400 && r.status.intValue < 500 && retryOn4xxErrors =>
                    // need to drain the response to close the connection
                    r.entity.dataBytes.runWith(Sink.ignore)
                    system.scheduler.scheduleOnce(retryInterval) { tryOnce() }

                case r if r.status.intValue >= 500 && r.status.intValue < 600 && retryOn5xxErrors =>
                    // need to drain the response to close the connection
                    r.entity.dataBytes.runWith(Sink.ignore)
                    system.scheduler.scheduleOnce(retryInterval) { tryOnce() }

                case r =>
                    promise.trySuccess(r)
            }

            f.onFailure {
                case s : akka.stream.StreamTcpException if retryOnTCPErrors =>
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
