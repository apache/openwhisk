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

package whisk.common

import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import com.timgroup.statsd.NonBlockingStatsDClient
import whisk.core.WhiskConfig

object Verbosity extends Enumeration {
    type Level = Value
    val Quiet, Loud, Debug = Value
}

/**
 * A logging facility in which output is one line and fields are bracketed.
 */
trait Logging {
    var outputStream: PrintStream = Console.out

    def setVerbosity(level: Verbosity.Level) =
        this.level = level

    def getVerbosity() = level

    def setComponentName(comp: String) =
        this.componentName = comp

    def debug(from: AnyRef, message: String, marker: LogMarkerToken = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level == Verbosity.Debug || mark.isDefined)
            emit("DEBUG", id, from, message, mark)
    }

    def info(from: AnyRef, message: String, marker: LogMarkerToken = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level != Verbosity.Quiet || mark.isDefined)
            emit("INFO", id, from, message, mark)
    }

    def warn(from: AnyRef, message: String, marker: LogMarkerToken = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level != Verbosity.Quiet || mark.isDefined)
            emit("WARN", id, from, message, mark)
    }

    def error(from: AnyRef, message: String, marker: LogMarkerToken = null)(implicit id: TransactionId = TransactionId.unknown) = {
        emit("ERROR", id, from, message, id.mark(marker))
    }

    def emit(category: AnyRef, id: TransactionId, from: AnyRef, message: String, mark: Option[LogMarker] = None) = {
        val now = mark map { _.now } getOrElse Instant.now(Clock.systemUTC)
        val time = Logging.timeFormat.format(now)
        val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)
        val msg = mark map { m => s"[marker:${m.token}:${m.delta}] $message" } getOrElse message

        mark foreach { marker =>
            sendToStatsd(marker)
        }

        if (componentName != "") {
            outputStream.println(s"[$time] [$category] [$id] [$componentName] [$name] $msg")
        } else {
            outputStream.println(s"[$time] [$category] [$id] [$name] $msg")
        }
    }

    def sendToStatsd(mark: LogMarker) = {
        mark.deltaBetweenMarkers foreach { delta =>
            statsd.recordExecutionTime(s"${mark.token.component}.${mark.token.operation}.time", delta)
        }

        statsd.incrementCounter(s"${mark.token.component}.${mark.token.operation}.${mark.token.action}.count")
    }

    private var level = Verbosity.Quiet
    private var componentName = "";
    private var sequence = new AtomicInteger()
    private val whiskConfig = new WhiskConfig(Map(WhiskConfig.edgeHostName -> null) ++ WhiskConfig.consulServer)
    private val statsd = new NonBlockingStatsDClient("openwhisk", whiskConfig.edgeHostName, 8125);
}

/**
 * A triple representing the timestamp relative to which the elapsed time was computed,
 * typically for a TransactionId, the elapsed time in milliseconds and a string containing
 * the given marker token.
 */
protected case class LogMarker(now: Instant, delta: Long, token: LogMarkerToken, deltaBetweenMarkers: Option[Long])

private object Logging {
    /**
     * Given a class object, return its simple name less the trailing dollar sign.
     */
    def getCleanSimpleClassName(clz: Class[_]) = {
        val simpleName = clz.getSimpleName
        if (simpleName.endsWith("$")) simpleName.dropRight(1)
        else simpleName
    }

    private val timeFormat = DateTimeFormatter.
        ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").
        withZone(ZoneId.of("UTC"))
}

protected case class LogMarkerToken(component: String, operation: String, action: String)

object LoggingMarkers {
    val LOADBALANCER_POST_KAFKA = LogMarkerToken("loadbalancer", "kafka", "post")
    val CONTROLLER_ACTIVATION_CREATE = LogMarkerToken("controller", "activation", "create")
    val CONTROLLER_BLOCKING_ACTIVATION_END = LogMarkerToken("controller", "blocking_activation", "end")
    val CONTROLLER_BLOCK_FOR_RESULT_START = LogMarkerToken("controller", "block_for_result", "start")
    val CONTROLLER_ACTIVATION_END = LogMarkerToken("controller", "activation", "end")
    val CONTROLLER_ACTIVATION_REJECTED = LogMarkerToken("controller", "activation", "rejected")
    val CONTROLLER_ACTIVATION_FAILED = LogMarkerToken("controller", "activation", "failed")
    val INVOKER_FETCH_ACTION_START = LogMarkerToken("invoker", "fetch_action", "start")
    val INVOKER_FETCH_ACTION_DONE = LogMarkerToken("invoker", "fetch_action", "done")
    val INVOKER_FETCH_ACTION_FAILED = LogMarkerToken("invoker", "fetch_action", "failed")
    val INVOKER_FETCH_AUTH_START = LogMarkerToken("invoker", "fetch_auth", "start")
    val INVOKER_FETCH_AUTH_DONE = LogMarkerToken("invoker", "fetch_auth", "done")
    val INVOKER_FETCH_AUTH_FAILED = LogMarkerToken("invoker", "fetch_auth", "failed")
    val INVOKER_GET_CONTAINER_START = LogMarkerToken("invoker", "get_container", "start")
    val INVOKER_GET_CONTAINER_DONE = LogMarkerToken("invoker", "get_container", "done")
    val INVOKER_CONTAINER_INIT = LogMarkerToken("invoker", "container", "init")
    val INVOKER_ACTIVATION_RUN_START = LogMarkerToken("invoker", "activation_run", "start")
    val INVOKER_ACTIVATION_RUN_DONE = LogMarkerToken("invoker", "activation_run", "done")
    val INVOKER_ACTIVATION_END = LogMarkerToken("invoker", "activation", "end")
    val INVOKER_ACTIVATION_FAILED = LogMarkerToken("invoker", "activation", "failed")
    val INVOKER_RECORD_ACTIVATION_START = LogMarkerToken("invoker", "record_activation", "start")
    val INVOKER_RECORD_ACTIVATION_DONE = LogMarkerToken("invoker", "record_activation", "done")
}
