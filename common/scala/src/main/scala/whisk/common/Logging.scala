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
    /**
     * The output stream, defaults to Console.out.
     * This is mutable to allow unit tests to capture the stream.
     */
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

case class LogMarkerToken(component: String, action: String, state: String) {
    override def toString() = component + "_" + action + "_" + state
}

object LoggingMarkers {
    private val start = "start"
    private val finish = "finish"
    private val error = "error"

    private val controller = "controller"
    private val loadbalancer = "loadbalancer"
    private val invoker = "invoker"
    private val database = "database"

    private val activation = "activation"
    val CONTROLLER_ACTIVATION_START = LogMarkerToken(controller, activation, start)
    val CONTROLLER_ACTIVATION_DONE = LogMarkerToken(controller, activation, finish)
    val CONTROLLER_ACTIVATION_REJECTED = LogMarkerToken(controller, activation, "reject")
    val CONTROLLER_ACTIVATION_FAILED = LogMarkerToken(controller, activation, error)

    private val blockingActivation = "blockingActivation"
    val CONTROLLER_BLOCKING_ACTIVATION_START = LogMarkerToken(controller, blockingActivation, start)
    val CONTROLLER_BLOCKING_ACTIVATION_DONE = LogMarkerToken(controller, blockingActivation, finish)

    private val listEntities = "listEntities"
    val CONTROLLER_LIST_START = LogMarkerToken(controller, listEntities, start)
    val CONTROLLER_LIST_DONE = LogMarkerToken(controller, listEntities, finish)
    val CONTROLLER_LIST_ERROR = LogMarkerToken(controller, listEntities, error)

    private val getEntities = "getEntities"
    val CONTROLLER_GET_START = LogMarkerToken(controller, getEntities, start)
    val CONTROLLER_GET_DONE = LogMarkerToken(controller, getEntities, finish)
    val CONTROLLER_GET_ERROR = LogMarkerToken(controller, getEntities, error)

    private val putEntity = "putEntity"
    val CONTROLLER_PUT_START = LogMarkerToken(controller, putEntity, start)
    val CONTROLLER_PUT_DONE = LogMarkerToken(controller, putEntity, finish)
    val CONTROLLER_PUT_ERROR = LogMarkerToken(controller, putEntity, error)

    private val deleteEntity = "deleteEntity"
    val CONTROLLER_DELETE_START = LogMarkerToken(controller, deleteEntity, start)
    val CONTROLLER_DELETE_DONE = LogMarkerToken(controller, deleteEntity, finish)
    val CONTROLLER_DELETE_ERROR = LogMarkerToken(controller, deleteEntity, error)

    private val checkEntitlement = "checkEntitlement"
    val CONTROLLER_CHECK_ENTITLEMENT_START = LogMarkerToken(controller, checkEntitlement, start)
    val CONTROLLER_CHECK_ENTITLEMENT_DONE = LogMarkerToken(controller, checkEntitlement, finish)
    val CONTROLLER_CHECK_ENTITLEMENT_ERROR = LogMarkerToken(controller, checkEntitlement, error)

    private val fireTrigger = "fireTrigger"
    val CONTROLLER_FIRE_TRIGGER_START = LogMarkerToken(controller, fireTrigger, start)
    val CONTROLLER_FIRE_TRIGGER_DONE = LogMarkerToken(controller, fireTrigger, finish)
    val CONTROLLER_FIRE_TRIGGER_ERROR = LogMarkerToken(controller, fireTrigger, error)

    val LOADBALANCER_POST_KAFKA = LogMarkerToken(loadbalancer, "postToKafka", start)

    private val fetchAction = "fetchAction"
    val INVOKER_FETCH_ACTION_START = LogMarkerToken(invoker, fetchAction, start)
    val INVOKER_FETCH_ACTION_DONE = LogMarkerToken(invoker, fetchAction, finish)
    val INVOKER_FETCH_ACTION_FAILED = LogMarkerToken(invoker, fetchAction, error)

    private val fetchAuth = "fetchAuth"
    val INVOKER_FETCH_AUTH_START = LogMarkerToken(invoker, fetchAuth, start)
    val INVOKER_FETCH_AUTH_DONE = LogMarkerToken(invoker, fetchAuth, finish)
    val INVOKER_FETCH_AUTH_FAILED = LogMarkerToken(invoker, fetchAuth, error)

    private val getContainer = "getContainer"
    val INVOKER_GET_CONTAINER_START = LogMarkerToken(invoker, getContainer, start)
    val INVOKER_GET_CONTAINER_DONE = LogMarkerToken(invoker, getContainer, finish)

    val INVOKER_CONTAINER_INIT = LogMarkerToken(invoker, "initContainer", start)

    private val activationRun = "activationRun"
    val INVOKER_ACTIVATION_RUN_START = LogMarkerToken(invoker, activationRun, start)
    val INVOKER_ACTIVATION_RUN_DONE = LogMarkerToken(invoker, activationRun, finish)

    val INVOKER_ACTIVATION_START = LogMarkerToken(invoker, activation, start)
    val INVOKER_ACTIVATION_DONE = LogMarkerToken(invoker, activation, finish)
    val INVOKER_ACTIVATION_ERROR = LogMarkerToken(invoker, activation, error)

    private val recordActivation = "recordActivation"
    val INVOKER_RECORD_ACTIVATION_START = LogMarkerToken(invoker, recordActivation, start)
    val INVOKER_RECORD_ACTIVATION_DONE = LogMarkerToken(invoker, recordActivation, finish)

    val DATABASE_CACHE_HIT = LogMarkerToken(database, "cacheHit", start)
    val DATABASE_CACHE_MISS = LogMarkerToken(database, "cacheMiss", start)

    private val saveDocument = "saveDocument"
    val DATABASE_SAVE_START = LogMarkerToken(database, saveDocument, start)
    val DATABASE_SAVE_DONE = LogMarkerToken(database, saveDocument, finish)
    val DATABASE_SAVE_ERROR = LogMarkerToken(database, saveDocument, error)

    private val deleteDocument = "deleteDocument"
    val DATABASE_DELETE_START = LogMarkerToken(database, deleteDocument, start)
    val DATABASE_DELETE_DONE = LogMarkerToken(database, deleteDocument, finish)
    val DATABASE_DELETE_ERROR = LogMarkerToken(database, deleteDocument, error)

    private val getDocument = "getDocument"
    val DATABASE_GET_START = LogMarkerToken(database, getDocument, start)
    val DATABASE_GET_DONE = LogMarkerToken(database, getDocument, finish)
    val DATABASE_GET_ERROR = LogMarkerToken(database, getDocument, error)

    private val queryView = "queryView"
    val DATABASE_QUERY_START = LogMarkerToken(database, queryView, start)
    val DATABASE_QUERY_DONE = LogMarkerToken(database, queryView, finish)
    val DATABASE_QUERY_ERROR = LogMarkerToken(database, queryView, error)
}
