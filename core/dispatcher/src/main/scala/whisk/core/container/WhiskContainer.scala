/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.container

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer

import spray.json.JsObject
import spray.json.JsString
import whisk.common.TransactionId
import whisk.core.entity.ActionLimits
import whisk.common.LoggingMarkers
import whisk.common.PrintStreamEmitter
import whisk.common.NewHttpUtils

/**
 * Reifies a whisk container - one that respects the whisk container API.
 */
class WhiskContainer(
    originalId: TransactionId,
    pool: ContainerPool,
    key: ActionContainerId,
    containerName: String,
    image: String,
    network: String,
    cpuShare: Int,
    policy: Option[String],
    env: Map[String, String],
    limits: ActionLimits,
    args: Array[String] = Array(),
    val isBlackbox: Boolean)
    extends Container(originalId, pool, key, Some(containerName), image, network, cpuShare, policy, limits, env, args) {

    var boundParams = JsObject() // Mutable to support pre-alloc containers
    var lastLogSize = 0L
    private implicit val emitter: PrintStreamEmitter = this

    /**
     * Merges previously bound parameters with arguments form payload.
     */
    def mergeParams(payload: JsObject, recurse: Boolean = true)(implicit transid: TransactionId): JsObject = {
        //debug(this, s"merging ${boundParams.compactPrint} with ${payload.compactPrint}")
        JsObject(boundParams.fields ++ payload.fields)
    }

    /**
     * Sends initialization payload to container.
     */
    def init(args: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem, transid: TransactionId): RunResult = {
        info(this, s"sending initialization to ${this.details}")
        // when invoking /init, don't wait longer than the timeout configured for this action
        val result = sendPayload("/init", JsObject("value" -> args), timeout) // this will retry
        info(this, s"initialization result: ${result}")
        result
    }

    /**
     * Sends a run command to action container to run once.
     *
     * @param state the value of the status to compare the actual state against
     * @return triple of start time, end time, response for user action.
     */
    def run(args: JsObject, meta: JsObject, authKey: String, timeout: FiniteDuration, actionName: String, activationId: String)(implicit system: ActorSystem, transid: TransactionId): RunResult = {
        val startMarker = transid.started("Invoker", LoggingMarkers.INVOKER_ACTIVATION_RUN, s"sending arguments to $actionName $details")
        val result = sendPayload("/run", JsObject(meta.fields + ("value" -> args) + ("authKey" -> JsString(authKey))), timeout)
        // Use start and end time of the activation
        val RunResult(Interval(startActivation, endActivation), _) = result
        transid.finished("Invoker", startMarker.copy(startActivation), s"finished running activation id: $activationId", endTime = endActivation)
        result
    }

    /**
     * An alternative entry point for direct testing of action container.
     */
    def run(payload: String, activationId: String)(implicit system: ActorSystem): RunResult = {
        val params = JsObject("payload" -> JsString(payload))
        val meta = JsObject("activationId" -> JsString(activationId))
        run(params, meta, "no_auth_key", 30000.milliseconds, "no_action", "no_activation_id")(system, TransactionId.testing)
    }

    /**
     * Tear down the container and retrieve the logs.
     */
    def teardown()(implicit transid: TransactionId): String = {
        getContainerLogs(Some(containerName)).getOrElse("none")
    }

    /**
     * Posts a message to the container.
     *
     * @param msg the message to post
     * @return response from container if any as array of byte
     */
    private def sendPayload(endpoint: String, msg: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem): RunResult = {
        import system.dispatcher

        val start = ContainerCounter.now()

        val f = sendPayloadAsync(endpoint, msg, timeout)

        f.onFailure {
            case t: Throwable =>
                warn(this, s"Exception while posting to action container ${t.getMessage}")
        }

        // Should never timeout because the future has a built-in timeout.
        // Keeping a finite duration for safety.
        Await.ready(f, timeout + 1.minute)

        val end = ContainerCounter.now()

        val r = f.value.get.toOption.flatten
        RunResult(Interval(start, end), r)
    }

    /**
     * Asynchronously posts a message to the container.
     *
     *  @param msg the message to post
     *  @return response from the container if any
     */
    private def sendPayloadAsync(endpoint: String, msg: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem): Future[Option[(Int, String)]] = {
        implicit val ec = system.dispatcher
        implicit val materializer = ActorMaterializer()

        containerHostAndPort map { hp =>

            val flow = Http().outgoingConnection(hp.host, hp.port)

            val uri = Uri(
                scheme = "http",
                authority = Uri.Authority(host = Uri.Host(hp.host), port = hp.port),
                path = Uri.Path(endpoint))

            for (
                entity <- Marshal(msg).to[MessageEntity];
                request = HttpRequest(method = HttpMethods.POST, uri = uri, entity = entity);
                response <- NewHttpUtils.singleRequest(request, timeout, retryOnTCPErrors = true, retryInterval = 100.milliseconds);
                responseBody <- Unmarshal(response.entity).to[String]
            ) yield {
                Some((response.status.intValue, responseBody))
            }
        } getOrElse {
            Future.successful(None)
        }
    }
}

/**
 * Singleton to thread-safely count containers.
 */
protected[container] object ContainerCounter {
    private val cnt = new AtomicInteger(0)
    private def next(): Int = {
        cnt.incrementAndGet()
    }
    private def cut(): Int = {
        cnt.get()
    }

    def now() = Instant.now(Clock.systemUTC())

    def containerName(containerPrefix: String, containerSuffix: String): String = {
        s"wsk${containerPrefix}_${ContainerCounter.next()}_${containerSuffix}_${now()}".replaceAll("[^a-zA-Z0-9_]", "")
    }
}
