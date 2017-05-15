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

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer
import spray.json._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.entity._
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationResponse._

/**
 * Reifies a whisk container - one that respects the whisk container API.
 */
class WhiskContainer(
    originalId: TransactionId,
    useRunc: Boolean,
    dockerhost: String,
    mounted: Boolean,
    key: ActionContainerId,
    containerName: ContainerName,
    image: String,
    network: String,
    cpuShare: Int,
    policy: Option[String],
    dnsServers: Seq[String],
    env: Map[String, String],
    limits: ActionLimits,
    args: Array[String] = Array())(
        override implicit val logging: Logging)
    extends Container(originalId, useRunc, dockerhost, mounted, key, Some(containerName), image, network, cpuShare, policy, dnsServers, limits, env, args) {

    var lastLogSize = 0L

    /** HTTP connection to container. Initialized on /init. */
    private var connection: Option[HttpUtils] = None

    /**
     * Sends initialization payload to container.
     */
    def init(args: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem, transid: TransactionId): RunResult = {
        val startMarker = transid.started("Invoker", LoggingMarkers.INVOKER_ACTIVATION_INIT, s"sending initialization to ${this.details}")
        // when invoking /init, don't wait longer than the timeout configured for this action
        val result = sendPayload("/init", JsObject("value" -> args), timeout, retry = true)
        val RunResult(Interval(startActivation, endActivation), _) = result
        transid.finished("Invoker", startMarker.copy(startActivation), s"initialization result: ${result.toBriefString}", endTime = endActivation)
        result
    }

    private def constructActivationMetadata(msg: ActivationMessage, args: JsObject, timeout: FiniteDuration): JsObject = {
        JsObject(
            "value" -> args,
            "api_key" -> msg.user.authkey.compact.toJson,
            "namespace" -> msg.user.namespace.toJson,
            "action_name" -> msg.action.qualifiedNameWithLeadingSlash.toJson,
            "activation_id" -> msg.activationId.toString.toJson,
            // compute deadline on invoker side avoids discrepancies inside container
            // but potentially under-estimates actual deadline
            "deadline" -> (Instant.now(Clock.systemUTC()).toEpochMilli + timeout.toMillis).toString.toJson)
    }

    /**
     * Sends a run command to action container to run once.
     *
     * @param state the value of the status to compare the actual state against
     * @return triple of start time, end time, response for user action.
     */
    def run(msg: ActivationMessage, args: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem, transid: TransactionId): RunResult = {
        val startMarker = transid.started("Invoker", LoggingMarkers.INVOKER_ACTIVATION_RUN, s"sending arguments to ${msg.action} $details")
        val result = sendPayload("/run", constructActivationMetadata(msg, args, timeout), timeout, retry = false)
        // Use start and end time of the activation
        val RunResult(Interval(startActivation, endActivation), _) = result
        transid.finished("Invoker", startMarker.copy(startActivation), s"running result: ${result.toBriefString}", endTime = endActivation)
        result
    }

    /**
     * An alternative entry point for direct testing of action container.
     */
    def run(payload: String, activationId: String)(implicit system: ActorSystem): RunResult = {
        val params = JsObject("payload" -> JsString(payload))
        val meta = JsObject("activationId" -> JsString(activationId))
        val msg = ActivationMessage(
            TransactionId.testing,
            FullyQualifiedEntityName(EntityPath("no_namespace"), EntityName("no_action")),
            DocRevision.empty,
            WhiskAuth(Subject(), AuthKey()).toIdentity,
            ActivationId(),
            EntityPath("no_namespace"),
            None)
        run(msg, params, 30000.milliseconds)(system, TransactionId.testing)
    }

    /**
     * Tear down the container and retrieve the logs.
     */
    def teardown()(implicit transid: TransactionId): String = {
        connection.foreach(_.close)
        getContainerLogs(containerName).toOption.getOrElse("none")
    }

    /**
     * Posts a message to the container.
     *
     * @param msg the message to post
     * @param retry whether or not to retry on connection failure
     * @return response from container if any as array of byte
     */
    private def sendPayload(endpoint: String, msg: JsObject, timeout: FiniteDuration, retry: Boolean)(implicit system: ActorSystem): RunResult = {
        sendPayloadApache(endpoint, msg, timeout, retry)
    }

    private def sendPayloadAkka(endpoint: String, msg: JsObject, timeout: FiniteDuration)(implicit system: ActorSystem): RunResult = {
        import system.dispatcher

        val start = ContainerCounter.now()

        val f = sendPayloadAsync(endpoint, msg, timeout)

        f.onFailure {
            case t: Throwable =>
                logging.warn(this, s"Exception while posting to action container ${t.getMessage}")
        }

        // Should never timeout because the future has a built-in timeout.
        // Keeping a finite duration for safety.
        Await.ready(f, timeout + 1.minute)

        val end = ContainerCounter.now()

        val r = f.value.get.toOption.flatten
        RunResult(Interval(start, end), ???)
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
                response <- AkkaHttpUtils.singleRequest(request, timeout, retryOnTCPErrors = true, retryInterval = 100.milliseconds);
                responseBody <- Unmarshal(response.entity).to[String]
            ) yield {
                Some((response.status.intValue, responseBody))
            }
        } getOrElse {
            Future.successful(None)
        }
    }

    private def sendPayloadApache(endpoint: String, msg: JsObject, timeout: FiniteDuration, retry: Boolean): RunResult = {
        val start = ContainerCounter.now()

        val result = for {
            hp <- containerHostAndPort
            c <- connection orElse {
                val hostWithPort = s"${hp.host}:${hp.port}"
                connection = Some(new HttpUtils(hostWithPort, timeout, ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT))
                connection
            }
        } yield {
            c.post(endpoint, msg, retry)
        }

        val end = ContainerCounter.now()
        RunResult(Interval(start, end), result getOrElse Left(NoHost()))
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

    def containerName(containerPrefix: String, containerSuffix: String): ContainerName = {
        val name = s"wsk${containerPrefix}_${ContainerCounter.next()}_${containerSuffix}_${now()}".replaceAll("[^a-zA-Z0-9_]", "")
        ContainerName.fromString(name)
    }
}
