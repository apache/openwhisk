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

    def debug(from: AnyRef, message: String, marker: String = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level == Verbosity.Debug || mark.isDefined)
            emit("DEBUG", id, from, message, mark)
    }

    def info(from: AnyRef, message: String, marker: String = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level != Verbosity.Quiet || mark.isDefined)
            emit("INFO", id, from, message, mark)
    }

    def warn(from: AnyRef, message: String, marker: String = null)(implicit id: TransactionId = TransactionId.unknown) = {
        val mark = id.mark(marker)
        if (level != Verbosity.Quiet || mark.isDefined)
            emit("WARN", id, from, message, mark)
    }

    def error(from: AnyRef, message: String, marker: String = null)(implicit id: TransactionId = TransactionId.unknown) = {
        emit("ERROR", id, from, message, id.mark(marker))
    }

    def emit(category: AnyRef, id: TransactionId, from: AnyRef, message: String, mark: Option[LogMarker] = None) = {
        val now = mark map { _.now } getOrElse Instant.now(Clock.systemUTC)
        val time = Logging.timeFormat.format(now)
        val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)
        val msg = mark map { m => s"[marker:${m.token}:${m.delta}] $message" } getOrElse message

        if (componentName != "") {
            outputStream.println(s"[$time] [$category] [$id] [$componentName] [$name] $msg")
        } else {
            outputStream.println(s"[$time] [$category] [$id] [$name] $msg")
        }
    }

    private var level = Verbosity.Quiet
    private var componentName = "";
    private var sequence = new AtomicInteger()
}

/**
 * A triple representing the timestamp relative to which the elapsed time was computed,
 * typically for a TransactionId, the elapsed time in milliseconds and a string containing
 * the given marker token.
 */
protected case class LogMarker(now: Instant, delta: Long, token: String)

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

object LoggingMarkers {
    val CONTROLLER_CREATE_ACTIVATION = "controller_create_activation"
    val LOADBALANCER_POST_KAFKA = "loadbalancer_post_kafka"
    val CONTROLLER_ACTIVATION_END = "controller_activation_end"
    val CONTROLLER_BLOCKING_ACTIVATION_END = "controller_blocking_activation_end"
    val CONTROLLER_BLOCK_FOR_RESULT = "controller_block_for_result"
    val CONTROLLER_ACTIVATION_REJECTED = "controller_activation_rejected"
    val CONTROLLER_ACTIVATION_FAILED = "controller_activation_failed"
    val INVOKER_FETCH_ACTION_START = "invoker_fetch_action_start"
    val INVOKER_FETCH_ACTION_DONE = "invoker_fetch_action_done"
    val INVOKER_FETCH_ACTION_FAILED = "invoker_fetch_action_failed"
    val INVOKER_FETCH_AUTH_START = "invoker_fetch_auth_start"
    val INVOKER_FETCH_AUTH_DONE = "invoker_fetch_auth_done"
    val INVOKER_FETCH_AUTH_FAILED = "invoker_fetch_auth_failed"
    val INVOKER_GET_CONTAINER_START = "invoker_get_container_start"
    val INVOKER_GET_CONTAINER_DONE = "invoker_get_container_done"
    val INVOKER_CONTAINER_INIT = "invoker_container_init"
    val INVOKER_SEND_ARGS = "invoker_send_args"
    val INVOKER_ACTIVATION_END = "invoker_activation_end"
    val INVOKER_RECORD_ACTIVATION_START = "invoker_record_activation_start"
    val INVOKER_RECORD_ACTIVATION_DONE = "invoker_record_activation_done"
    val INVOKER_FAILED_ACTIVATION = "invoker_failed_activation"
}
