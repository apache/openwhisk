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

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import scala.math.BigDecimal.int2bigDecimal
import scala.util.Try

import spray.json.JsArray
import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat
import java.time.Duration
import akka.event.Logging.LogLevel
import akka.event.Logging.{ InfoLevel, WarningLevel }

/**
 * A transaction id for tracking operations in the system that are specific to a request.
 * An instance of TransactionId is implicitly received by all logging methods. The actual
 * metadata is stored indirectly in the referenced meta object.
 */
case class TransactionId private (meta: TransactionMetadata) extends AnyVal {
    def apply() = meta.id
    override def toString = if (meta.id > 0) s"#tid_${meta.id}" else (if (meta.id < 0) s"#sid_${-meta.id}" else "??")

    /**
     * Method to count events.
     *
     * @param from Reference, where the method was called from.
     * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
     * @param message An additional message that is written into the log, together with the other information.
     * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
     */
    def mark(from: AnyRef, marker: LogMarkerToken, message: String = "", logLevel: LogLevel = InfoLevel)(implicit emitter: PrintStreamEmitter) = {
        emitter.emit(logLevel, this, from, message, Some(LogMarker(marker, deltaToStart)))
    }

    /**
     * Method to start taking time of an action in the code. It returns a <code>StartMarker</code> which has to be
     * passed into the <code>finished</code>-method.
     *
     * @param from Reference, where the method was called from.
     * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
     * @param message An additional message that is written into the log, together with the other information.
     * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
     *
     * @return startMarker that has to be passed to the finished or failed method to calculate the time difference.
     */
    def started(from: AnyRef, marker: LogMarkerToken, message: String = "", logLevel: LogLevel = InfoLevel)(implicit emitter: PrintStreamEmitter): StartMarker = {
        emitter.emit(logLevel, this, from, message, Some(LogMarker(marker, deltaToStart)))
        StartMarker(Instant.now, marker)
    }

    /**
     * Method to stop taking time of an action in the code. The time the method used will be written into a log message.
     *
     * @param from Reference, where the method was called from.
     * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
     * @param message An additional message that is written into the log, together with the other information.
     * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
     * @param endTime Manually set the timestamp of the end. By default it is NOW.
     */
    def finished(from: AnyRef, startMarker: StartMarker, message: String = "", logLevel: LogLevel = InfoLevel, endTime: Instant = Instant.now(Clock.systemUTC))(implicit emitter: PrintStreamEmitter) = {
        val endMarker = LogMarkerToken(startMarker.startMarker.component, startMarker.startMarker.action, LoggingMarkers.finish)
        emitter.emit(logLevel, this, from, message, Some(LogMarker(endMarker, deltaToStart, Some(deltaToMarker(startMarker, endTime)))))
    }

    /**
     * Method to stop taking time of an action in the code that failed. The time the method used will be written into a log message.
     *
     * @param from Reference, where the method was called from.
     * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
     * @param message An additional message that is written into the log, together with the other information.
     * @param logLevel The <code>LogLevel</code> the message should have. Default is <code>WarningLevel</code>.
     */
    def failed(from: AnyRef, startMarker: StartMarker, message: String = "", logLevel: LogLevel = WarningLevel)(implicit emitter: PrintStreamEmitter) = {
        val endMarker = LogMarkerToken(startMarker.startMarker.component, startMarker.startMarker.action, LoggingMarkers.error)
        emitter.emit(logLevel, this, from, message, Some(LogMarker(endMarker, deltaToStart, Some(deltaToMarker(startMarker)))))
    }

    /**
     * Calculates the time between now and the beginning of the transaction.
     */
    def deltaToStart = Duration.between(meta.start, Instant.now(Clock.systemUTC)).toMillis

    /**
     * Calculates the time between now and the startMarker that was returned by <code>starting</code>.
     *
     * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
     * @param endTime Manually set the endtime. By default it is NOW.
     */
    def deltaToMarker(startMarker: StartMarker, endTime: Instant = Instant.now(Clock.systemUTC)) = Duration.between(startMarker.start, endTime).toMillis
}

/**
 * The StartMarker which includes the <code>LogMarkerToken</code> and the start-time.
 *
 * @param start the time when the startMarker was set
 * @param startMarker the LogMarkerToken which defines the start event
 */
case class StartMarker(val start: Instant, startMarker: LogMarkerToken)

/**
 * The transaction metadata encapsulates important properties about a transaction.
 *
 * @param id the transaction identifier; it is positive for client requests,
 *           negative for system operation and zero when originator is not known
 * @param start the timestamp when the request processing commenced
 */
protected case class TransactionMetadata(val id: Long, val start: Instant)

object TransactionId {
    val unknown = TransactionId(0)
    val testing = TransactionId(-1)         // Common id for for unit testing
    val invoker = TransactionId(-100)       // Invoker startup/shutdown or GC activity
    val invokerWarmup = TransactionId(-101) // Invoker warmup thread that makes stem-cell containers
    val invokerNanny = TransactionId(-102)  // Invoker nanny thread
    val dispatcher = TransactionId(-110)    // Kafka message dispatcher
    val loadbalancer = TransactionId(-120)  // Loadbalancer thread
    val controller = TransactionId(-130)    // Controller startup

    def apply(tid: BigDecimal): TransactionId = {
        Try {
            val now = Instant.now(Clock.systemUTC())
            TransactionId(TransactionMetadata(tid.toLong, now))
        } getOrElse unknown
    }

    implicit val serdes = new RootJsonFormat[TransactionId] {
        def write(t: TransactionId) = JsArray(JsNumber(t.meta.id), JsNumber(t.meta.start.toEpochMilli))

        def read(value: JsValue) = Try {
            value match {
                case JsArray(Vector(JsNumber(id), JsNumber(start))) =>
                    TransactionId(TransactionMetadata(id.longValue, Instant.ofEpochMilli(start.longValue)))
            }
        } getOrElse unknown
    }
}

/**
 * A thread-safe transaction counter.
 */
trait TransactionCounter {
    def transid(): TransactionId = {
        TransactionId(cnt.incrementAndGet())
    }

    private val cnt = new AtomicInteger(1)
}
