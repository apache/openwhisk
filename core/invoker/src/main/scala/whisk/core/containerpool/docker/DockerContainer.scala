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

package whisk.core.containerpool.docker

import java.nio.charset.StandardCharsets
import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.container.HttpUtils
import whisk.core.container.Interval
import whisk.core.container.RunResult
import whisk.core.containerpool.BlackboxStartupError
import whisk.core.containerpool.Container
import whisk.core.containerpool.InitializationError
import whisk.core.containerpool.WhiskContainerStartupError
import whisk.core.entity.ActivationResponse
import whisk.core.entity.ByteSize
import whisk.core.entity.size._
import whisk.core.invoker.ActionLogDriver
import whisk.http.Messages

object DockerContainer {
    /**
     * Creates a container running on a docker daemon.
     *
     * @param transid transaction creating the container
     * @param image image to create the container from
     * @param userProvidedImage whether the image is provided by the user
     *     or is an OpenWhisk provided image
     * @param memory memorylimit of the container
     * @param cpuShares sharefactor for the container
     * @param environment environment variables to set on the container
     * @param network network to launch the container in
     * @param dnsServers list of dns servers to use in the container
     * @param name optional name for the container
     * @return a Future which either completes with a DockerContainer or one of two specific failures
     */
    def create(transid: TransactionId,
               image: String,
               userProvidedImage: Boolean = false,
               memory: ByteSize = 256.MB,
               cpuShares: Int = 0,
               environment: Map[String, String] = Map(),
               network: String = "bridge",
               dnsServers: Seq[String] = Seq(),
               name: Option[String] = None)(
                   implicit docker: DockerApiWithFileAccess, runc: RuncApi, ec: ExecutionContext, log: Logging): Future[DockerContainer] = {
        implicit val tid = transid

        val environmentArgs = (environment + ("SERVICE_IGNORE" -> true.toString)).map {
            case (key, value) => Seq("-e", s"$key=$value")
        }.flatten

        val dnsArgs = dnsServers.map(Seq("--dns",_)).flatten

        val args = Seq(
            "--cap-drop", "NET_RAW",
            "--cap-drop", "NET_ADMIN",
            "--ulimit", "nofile=1024:1024",
            "--pids-limit", "1024",
            "--cpu-shares", cpuShares.toString,
            "--memory", s"${memory.toMB}m",
            "--memory-swap", s"${memory.toMB}m",
            "--network", network) ++
            dnsArgs ++
            environmentArgs ++
            name.map(n => Seq("--name", n)).getOrElse(Seq.empty)

        val pulled = if (userProvidedImage) {
            docker.pull(image).recoverWith {
                case _ => Future.failed(BlackboxStartupError(s"Failed to pull container image '${image}'."))
            }
        } else Future.successful(())

        for {
            _ <- pulled
            id <- docker.run(image, args).recoverWith {
                case _ => Future.failed(WhiskContainerStartupError(s"Failed to run container with image '${image}'."))
            }
            ip <- docker.inspectIPAddress(id, network).recoverWith {
                // remove the container immediately if inspect failed as
                // we cannot recover that case automatically
                case _ =>
                    docker.rm(id)
                    Future.failed(WhiskContainerStartupError(s"Failed to obtain IP address of container '${id.asString}'."))
            }
        } yield new DockerContainer(id, ip)
    }
}

/**
 * Represents a container as run by docker.
 *
 * This class contains OpenWhisk specific behavior and as such does not necessarily
 * use docker commands to achieve the effects needed.
 *
 * @constructor
 * @param id the id of the container
 * @param ip the ip of the container
 */
class DockerContainer(id: ContainerId, ip: ContainerIp)(
    implicit docker: DockerApiWithFileAccess, runc: RuncApi, ec: ExecutionContext, logger: Logging) extends Container with ActionLogDriver {

    /** The last read-position in the log file */
    private var logFileOffset = 0L

    protected val logsRetryCount = 15
    protected val logsRetryWait = 100.millis

    /** HTTP connection to the container, will be lazily established by callContainer */
    private var httpConnection: Option[HttpUtils] = None

    def suspend()(implicit transid: TransactionId): Future[Unit] = runc.pause(id)
    def resume()(implicit transid: TransactionId): Future[Unit] = runc.resume(id)
    def destroy()(implicit transid: TransactionId): Future[Unit] = docker.rm(id)

    def initialize(initializer: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION_INIT, s"sending initialization to $id $ip")

        val body = JsObject("value" -> initializer)
        callContainer("/init", body, timeout, retry = true).andThen { // never fails
            case Success(r: RunResult) =>
                transid.finished(this, start.copy(start = r.interval.start), s"initialization result: ${r.toBriefString}", endTime = r.interval.end)
            case Failure(t) =>
                transid.failed(this, start, s"initializiation failed with $t")
        }.flatMap { result =>
            if (result.ok) {
                Future.successful(result.interval)
            } else if (result.interval.duration >= timeout) {
                Future.failed(InitializationError(result.interval, ActivationResponse.applicationError(Messages.timedoutActivation(timeout, true))))
            } else {
                Future.failed(InitializationError(result.interval, ActivationResponse.processInitResponseContent(result.response, logger)))
            }
        }
    }

    def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        val actionName = environment.fields.get("action_name").map(_.convertTo[String]).getOrElse("")
        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION_RUN, s"sending arguments to $actionName at $id $ip")

        val parameterWrapper = JsObject("value" -> parameters)
        val body = JsObject(parameterWrapper.fields ++ environment.fields)
        callContainer("/run", body, timeout, retry = false).andThen { // never fails
            case Success(r: RunResult) =>
                transid.finished(this, start.copy(start = r.interval.start), s"running result: ${r.toBriefString}", endTime = r.interval.end)
            case Failure(t) =>
                transid.failed(this, start, s"run failed with $t")
        }.map { result =>
            val response = if (result.interval.duration >= timeout) {
                ActivationResponse.applicationError(Messages.timedoutActivation(timeout, false))
            } else {
                ActivationResponse.processRunResponseContent(result.response, logger)
            }

            (result.interval, response)
        }
    }

    /**
     * Obtains the container's stdout and stderr output and converts it to our own JSON format.
     * At the moment, this is done by reading the internal Docker log file for the container.
     * Said file is written by Docker's JSON log driver and has a "well-known" location and name.
     *
     * For warm containers, the container log file already holds output from
     * previous activations that have to be skipped. For this reason, a starting position
     * is kept and updated upon each invocation.
     *
     * If asked, check for sentinel markers - but exclude the identified markers from
     * the result returned from this method.
     *
     * Only parses and returns as much logs as fit in the passed log limit.
     * Even if the log limit is exceeded, advance the starting position for the next invocation
     * behind the bytes most recently read - but don't actively read any more until sentinel
     * markers have been found.
     *
     * @param limit the limit to apply to the log size
     * @param waitForSentinel determines if the processor should wait for a sentinel to appear
     *
     * @return a vector of Strings with log lines in our own JSON format
     */
    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Future[Vector[String]] = {

        def readLogs(retries: Int): Future[Vector[String]] = {
            docker.rawContainerLogs(id, logFileOffset).flatMap { rawLogBytes =>
                val rawLog = new String(rawLogBytes.array, rawLogBytes.arrayOffset, rawLogBytes.position, StandardCharsets.UTF_8)
                val (isComplete, isTruncated, formattedLogs) = processJsonDriverLogContents(rawLog, waitForSentinel, limit)

                if (retries > 0 && !isComplete && !isTruncated) {
                    logger.info(this, s"log cursor advanced but missing sentinel, trying $retries more times")
                    Thread.sleep(logsRetryWait.toMillis)
                    readLogs(retries - 1)
                } else {
                    logFileOffset += rawLogBytes.position - rawLogBytes.arrayOffset
                    Future.successful(formattedLogs)
                }
            }.andThen {
                case Failure(e) =>
                    logger.error(this, s"Failed to obtain logs of ${id.asString}: ${e.getClass} - ${e.getMessage}")
            }
        }

        readLogs(logsRetryCount)
    }

    /**
     * Makes an HTTP request to the container.
     *
     * Note that `http.post` will not throw an exception, hence the generated Future cannot fail.
     *
     * @param path relative path to use in the http request
     * @param body body to send
     * @param timeout timeout of the request
     * @param retry whether or not to retry the request
     */
    protected def callContainer(path: String, body: JsObject, timeout: FiniteDuration, retry: Boolean = false): Future[RunResult] = {
        val started = Instant.now()
        val http = httpConnection.getOrElse {
            val conn = new HttpUtils(s"${ip.asString}:8080", timeout, 1.MB)
            httpConnection = Some(conn)
            conn
        }
        Future {
            http.post(path, body, retry)
        }.map { response =>
            val finished = Instant.now()
            RunResult(Interval(started, finished), response)
        }
    }
}
