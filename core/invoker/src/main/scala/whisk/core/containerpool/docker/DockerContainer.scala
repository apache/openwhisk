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

package whisk.core.containerpool.docker

import scala.concurrent.Future
import spray.json.JsObject
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import whisk.core.container.HttpUtils
import whisk.core.entity.size._
import scala.util.Success
import scala.util.Failure
import java.time.Instant
import whisk.core.container.RunResult
import whisk.core.container.Interval
import java.nio.file.Paths
import java.io.File
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.util.Try
import whisk.common.TransactionId
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.core.entity.ByteSize
import java.nio.charset.StandardCharsets
import scala.collection.mutable.Buffer
import whisk.http.Messages
import whisk.core.entity.ActivationResponse
import whisk.core.containerpool.BlackboxStartupError
import whisk.core.containerpool.WhiskContainerStartupError
import whisk.core.containerpool.InitializationError
import whisk.core.containerpool.Container

object DockerContainer {
    /**
     * Creates a container running on a docker daemon.
     *
     * @param transid transaction creating the container
     * @param image image to create the container from
     * @param userProvidedImage whether the image is provided by the user
     * or an OpenWhisk provided image
     * @param memory memorylimit of the container
     * @param cpuShares sharefactor for the container
     * @param environment environment variables to set on the container
     * @param network network to launch the container in
     * @param name optional name for the container
     * @return a container
     */
    def create(transid: TransactionId,
               image: String,
               userProvidedImage: Boolean = false,
               memory: ByteSize = 256.MB,
               cpuShares: Int = 0,
               environment: Map[String, String] = Map(),
               network: String = "bridge",
               name: Option[String] = None)(
                   implicit docker: DockerApi, runc: RuncApi, ec: ExecutionContext, log: Logging) = {

        val environmentArgs = (environment + ("SERVICE_IGNORE" -> true.toString)).map {
            case (key, value) => Seq("-e", s"$key=$value")
        }.flatten

        val args = Seq(
            "--cap-drop", "NET_RAW",
            "--cap-drop", "NET_ADMIN",
            "--ulimit", "nofile=1024:1024",
            "--pids-limit", "64",
            "--cpu-shares", cpuShares.toString,
            "--memory", s"${memory.toMB}m",
            "--memory-swap", s"${memory.toMB}m",
            "--network", network) ++
            environmentArgs ++
            name.map(n => Seq("--name", n)).getOrElse(Seq.empty)

        val pulled = if (userProvidedImage) {
            docker.pull(image)(transid).recoverWith {
                case _ => Future.failed(BlackboxStartupError(s"Failed to pull container image '$image'."))
            }
        } else Future.successful(())

        val container = for {
            _ <- pulled
            id <- docker.run(image, args)(transid)
            ip <- DockerContainer.ipFromFile(id, network).recoverWith {
                case _ => docker.inspectIPAddress(id)(transid)
            }.andThen {
                case Failure(_) => docker.rm(id)(transid)
            }
        } yield new DockerContainer(id, ip)

        container.recoverWith {
            case t => if (userProvidedImage) {
                Future.failed(BlackboxStartupError(t.getMessage))
            } else {
                Future.failed(WhiskContainerStartupError(t.getMessage))
            }
        }
    }

    /** Directory to look for containers */
    val containersDirectory = Paths.get("containers").toFile

    /**
     * Extracts the ip of the container from the local config file of the docker daemon.
     *
     * @param id id of the container to get the ip from
     * @param network the network the container is running in
     * @return the ip address of the container
     */
    def ipFromFile(id: ContainerId, network: String)(implicit ec: ExecutionContext) = Future {
        val containerDirectory = new File(DockerContainer.containersDirectory, id.asString)
        val configFile = new File(containerDirectory, "config.v2.json")
        val source = scala.io.Source.fromFile(configFile)
        val contents = source.mkString
        source.close()

        val networks = contents.parseJson.asJsObject.fields("NetworkSettings").asJsObject.fields("Networks").asJsObject
        val userland = networks.fields(network).asJsObject
        val ipAddr = userland.fields("IPAddress")
        ContainerIp(ipAddr.convertTo[String])
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
    implicit docker: DockerApi, runc: RuncApi, ec: ExecutionContext, log: Logging) extends Container with DockerFileLogs {
    val containerDirectory = new File(DockerContainer.containersDirectory, id.asString)
    val logFile = new File(containerDirectory, s"${id.asString}-json.log")
    var logPointer = 0L

    var httpConnection: Option[HttpUtils] = None

    def halt()(implicit transid: TransactionId): Future[Unit] = runc.pause(id)
    def resume()(implicit transid: TransactionId): Future[Unit] = runc.resume(id)
    def destroy()(implicit transid: TransactionId): Future[Unit] = docker.rm(id)

    def initialize(initializer: Option[JsObject], timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION_INIT, s"sending initialization to $id $ip")

        val body = initializer.map(init => JsObject("value" -> init)).getOrElse(JsObject())
        callContainer("/init", body, timeout, retry = true).andThen {
            case Success(r: RunResult) =>
                transid.finished(this, start.copy(start = r.interval.start), s"initialization result: ${r.toBriefString}", endTime = r.interval.end)
            case Failure(t) =>
                transid.failed(this, start, s"initializiation failed with $t")
        }.recoverWith {
            case t =>
                Future.failed(InitializationError(ActivationResponse.whiskError("action failed to initialize"), Interval.zero))
        }.flatMap { result =>
            if (result.ok) {
                Future.successful(result.interval)
            } else if (result.interval.duration >= timeout) {
                Future.failed(InitializationError(ActivationResponse.applicationError(Messages.timedoutActivation(timeout, true)), result.interval))
            } else {
                Future.failed(InitializationError(ActivationResponse.processInitResponseContent(result.response, log), result.interval))
            }
        }
    }

    def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
        val actionName = environment.fields.get("action_name").map(_.convertTo[String]).getOrElse("")
        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION_RUN, s"sending arguments to $actionName at $id $ip")

        val parameterWrapper = JsObject("value" -> parameters)
        val body = JsObject(parameterWrapper.fields ++ environment.fields)
        callContainer("/run", body, timeout, retry = false).andThen {
            case Success(r: RunResult) =>
                transid.finished(this, start.copy(start = r.interval.start), s"running result: ${r.toBriefString}", endTime = r.interval.end)
            case Failure(t) =>
                transid.failed(this, start, s"initializiation failed with $t")
        }.map { result =>
            val response = if (result.interval.duration >= timeout) {
                ActivationResponse.applicationError(Messages.timedoutActivation(timeout, false))
            } else {
                ActivationResponse.processRunResponseContent(result.response, log)
            }

            (result.interval, response)
        }
    }

    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Future[List[String]] = {
        val from = logPointer
        def readLogs(retries: Int): List[String] = {
            val to = logFile.length

            val lines = linesFromFile(logFile, from, to)
            val (isComplete, isTruncated, logs) = processJsonDriverLogContents(lines, waitForSentinel, limit)

            if (retries > 0 && !isComplete && !isTruncated) {
                log.info(this, s"log cursor advanced but missing sentinel, trying $retries more times")
                Thread.sleep(100)
                readLogs(retries - 1)
            } else {
                logPointer = to

                val formattedLogs = logs.map(_.toFormattedString)
                val finishedLogs = if (isTruncated) {
                    formattedLogs :+ Messages.truncateLogs(limit)
                } else formattedLogs

                finishedLogs.toList
            }
        }

        Future(readLogs(15))
    }

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

case class LogLine(time: String, stream: String, log: String) {
    def toFormattedString = f"$time%-30s $stream: ${log.trim}"
    def dropRight(maxBytes: ByteSize) = {
        val bytes = log.getBytes(StandardCharsets.UTF_8).dropRight(maxBytes.toBytes.toInt)
        LogLine(time, stream, new String(bytes, StandardCharsets.UTF_8))
    }
}

object LogLine extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(LogLine.apply)
}

trait DockerFileLogs {
    // The action proxies inserts this line in the logs at the end of each activation for stdout/stderr
    protected val LOG_ACTIVATION_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"

    /**
     * Given the JSON driver's raw output of a docker container, convert it into our own
     * JSON format. If asked, check for sentinel markers (which are not included in the output).
     *
     * Only parses and returns so much logs to fit into the LogLimit passed.
     *
     * @param logMsgs raw String read from a JSON log-driver written file
     * @param requireSentinel determines if the processor should wait for a sentinel to appear
     * @param limit the limit to apply to the log size
     *
     * @return Tuple containing (isComplete, isTruncated, logs)
     */
    def processJsonDriverLogContents(lines: Iterator[String], requireSentinel: Boolean, limit: ByteSize)(
        implicit transid: TransactionId, logging: Logging): (Boolean, Boolean, Vector[LogLine]) = {

        var hasOut = false
        var hasErr = false
        var truncated = false
        var bytesSoFar = 0.B
        val logLines = Buffer[LogLine]()

        // read whiles bytesSoFar <= limit when requireSentinel to try and grab sentinel if they exist to indicate completion
        while (lines.hasNext && ((requireSentinel && bytesSoFar <= limit) || bytesSoFar < limit)) {
            // if line does not parse, there's an error in the container log driver
            Try(lines.next().parseJson.convertTo[LogLine]) match {
                case Success(t) =>
                    // if sentinels are expected, do not account for their size, otherwise, all bytes are accounted for
                    if (requireSentinel && t.log.trim != LOG_ACTIVATION_SENTINEL || !requireSentinel) {
                        // ignore empty log lines
                        if (t.log.nonEmpty) {
                            bytesSoFar += t.log.sizeInBytes
                            if (bytesSoFar <= limit) {
                                logLines.append(t)
                            } else {
                                // chop off the right most bytes that overflow
                                val chopped = t.dropRight(bytesSoFar - limit)
                                if (chopped.log.nonEmpty) {
                                    logLines.append(chopped)
                                }
                                truncated = true
                            }
                        }
                    } else if (requireSentinel) {
                        // there may be more than one sentinel in stdout/stderr (as logs may leak across activations
                        // if for example there log limit was exceeded in one activation and the container was reused
                        // to run the same action again (this is considered a feature - otherwise, must drain the logs
                        // or destroy the container as if it errored)
                        if (t.stream == "stdout") {
                            hasOut = true
                        } else if (t.stream == "stderr") {
                            hasErr = true
                        }
                    }

                case Failure(t) =>
                    // Drop lines that did not parse to JSON objects.
                    // However, should not happen since we are using the json log driver.
                    logging.error(this, s"log line skipped/did not parse: $t")
            }
        }

        ((hasOut && hasErr) || !requireSentinel, truncated || lines.hasNext, logLines.toVector)
    }

    /**
     * Reads lines from a given file in a specified byte-range.
     *
     * @param file the file to read from
     * @param from byteoffset to start
     * @param to byteoffset to stop
     * @return individual lines from the file
     */
    def linesFromFile(file: File, from: Long, to: Long): Iterator[String] = {
        var fis: java.io.FileInputStream = null
        try {
            fis = new java.io.FileInputStream(file)
            val channel = fis.getChannel().position(from)
            var remain = (to - from).toInt
            val buffer = java.nio.ByteBuffer.allocate(remain)
            while (remain > 0) {
                val read = channel.read(buffer)
                if (read > 0)
                    remain = read - read.toInt
            }
            new String(buffer.array, StandardCharsets.UTF_8).lines
        } catch {
            case e: Exception =>
                print(e)
                Iterator.empty
        } finally {
            if (fis != null) fis.close()
        }
    }
}
