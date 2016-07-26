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
import akka.event.Logging.LogLevel
import akka.event.Logging.{ DebugLevel, InfoLevel, WarningLevel, ErrorLevel }

/**
 * A logging facility in which output is one line and fields are bracketed.
 */
trait Logging {
    /**
     * The output stream, defaults to Console.out.
     * This is mutable to allow unit tests to capture the stream.
     */
    var outputStream: PrintStream = Console.out

    def setVerbosity(level: LogLevel) =
        this.level = level

    def getVerbosity() = level

    def setComponentName(comp: String) =
        this.componentName = comp

    def debug(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.unknown) = {
        if (level >= DebugLevel) {
            emit(DebugLevel, id, from, message)
        }
    }

    def info(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.unknown) = {
        if (level >= InfoLevel) {
            emit(InfoLevel, id, from, message)
        }
    }

    def warn(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.unknown) = {
        if (level >= WarningLevel) {
            emit(WarningLevel, id, from, message)
        }

    }

    def error(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.unknown) = {
        if (level >= ErrorLevel) {
            emit(ErrorLevel, id, from, message)
        }
    }

    def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: String, marker: Option[LogMarker] = None) = {
        val now = Instant.now(Clock.systemUTC)
        val time = Logging.timeFormat.format(now)
        val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)

        val component = Option(componentName).filter(_.trim.nonEmpty).map("[" + _ + "]")

        val level = loglevel match {
            case DebugLevel   => "DEBUG"
            case InfoLevel    => "INFO"
            case WarningLevel => "WARN"
            case ErrorLevel   => "ERROR"
        }

        val parts = Seq(s"[$time]", s"[$level]", s"[$id]") ++ component ++ Seq(s"[$name]") ++ marker ++ Seq(message)
        outputStream.println(parts.mkString(" "))
    }

    private var level = akka.event.Logging.InfoLevel
    private var componentName = "";
    private var sequence = new AtomicInteger()
}

/**
 * A triple representing the timestamp relative to which the elapsed time was computed,
 * typically for a TransactionId, the elapsed time in milliseconds and a string containing
 * the given marker token.
 */
protected case class LogMarker(token: LogMarkerToken, deltaToStart: Long, deltaForAction: Option[Long] = None) {
    override def toString() = {
        val parts = Seq("marker", token.toString, deltaToStart) ++ deltaForAction
        "[" + parts.mkString(":") + "]"
    }
}

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
    val start = "start"
    val finish = "finish"
    val error = "error"
    val count = "count"

    private val controller = "controller"
    private val loadbalancer = "loadbalancer"
    private val invoker = "invoker"
    private val database = "database"
    private val activation = "activation"

    // Time of the activation in controller until it is delivered to Kafka
    val CONTROLLER_ACTIVATION = LogMarkerToken(controller, activation, start)
    val CONTROLLER_BLOCKING_ACTIVATION = LogMarkerToken(controller, "blockingActivation", start)

    // Time that is needed to execute the action
    val INVOKER_ACTIVATION_RUN = LogMarkerToken(invoker, "activationRun", start)

    // Time in invoker
    val INVOKER_ACTIVATION = LogMarkerToken(invoker, activation, start)
    def INVOKER_DOCKER_CMD(cmd: String) = LogMarkerToken(invoker, s"docker.$cmd", start)

    val DATABASE_CACHE_HIT = LogMarkerToken(database, "cacheHit", count)
    val DATABASE_CACHE_MISS = LogMarkerToken(database, "cacheMiss", count)
    val DATABASE_SAVE = LogMarkerToken(database, "saveDocument", start)
    val DATABASE_DELETE = LogMarkerToken(database, "deleteDocument", start)
    val DATABASE_GET = LogMarkerToken(database, "getDocument", start)
    val DATABASE_QUERY = LogMarkerToken(database, "queryView", start)
}
