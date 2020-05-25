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

package org.apache.openwhisk.common

import java.time.{Clock, Duration, Instant}

import akka.event.Logging.{DebugLevel, InfoLevel, LogLevel, WarningLevel}
import akka.http.scaladsl.model.headers.RawHeader
import spray.json._
import org.apache.openwhisk.core.ConfigKeys
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.common.WhiskInstants._

import scala.util.Try

/**
 * A transaction id for tracking operations in the system that are specific to a request.
 * An instance of TransactionId is implicitly received by all logging methods. The actual
 * metadata is stored indirectly in the referenced meta object.
 */
case class TransactionId private (meta: TransactionMetadata) extends AnyVal {
  def root = findRoot(meta)
  def id = meta.id
  override def toString = meta.toString

  def toHeader = RawHeader(TransactionId.generatorConfig.header, meta.id)

  /**
   * Method to count events.
   *
   * @param from Reference, where the method was called from.
   * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   */
  def mark(from: AnyRef, marker: LogMarkerToken, message: => String = "", logLevel: LogLevel = DebugLevel)(
    implicit logging: Logging) = {

    if (TransactionId.metricsLog) {
      // marker received with a debug level will be emitted on info level
      logging.emit(InfoLevel, this, from, createMessageWithMarker(message, LogMarker(marker, deltaToStart)))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitCounterMetric(marker)

  }

  /**
   * Method to start taking time of an action in the code. It returns a <code>StartMarker</code> which has to be
   * passed into the <code>finished</code>-method.
   *
   * @param from Reference, where the method was called from.
   * @param marker A LogMarkerToken. They are defined in <code>LoggingMarkers</code>.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   *
   * @return startMarker that has to be passed to the finished or failed method to calculate the time difference.
   */
  def started(from: AnyRef, marker: LogMarkerToken, message: => String = "", logLevel: LogLevel = DebugLevel)(
    implicit logging: Logging): StartMarker = {

    if (TransactionId.metricsLog) {
      // marker received with a debug level will be emitted on info level
      logging.emit(InfoLevel, this, from, createMessageWithMarker(message, LogMarker(marker, deltaToStart)))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitCounterMetric(marker)

    //tracing support
    WhiskTracerProvider.tracer.startSpan(marker, this)
    StartMarker(Instant.now.inMills, marker)
  }

  /**
   * Method to stop taking time of an action in the code. The time the method used will be written into a log message.
   *
   * @param from Reference, where the method was called from.
   * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The Loglevel, the message should have. Default is <code>InfoLevel</code>.
   * @param endTime Manually set the timestamp of the end. By default it is NOW.
   */
  def finished(from: AnyRef,
               startMarker: StartMarker,
               message: => String = "",
               logLevel: LogLevel = DebugLevel,
               endTime: Instant = Instant.now(Clock.systemUTC))(implicit logging: Logging) = {

    val endMarker = startMarker.startMarker.asFinish
    val deltaToEnd = deltaToMarker(startMarker, endTime)

    if (TransactionId.metricsLog) {
      logging.emit(
        InfoLevel,
        this,
        from,
        createMessageWithMarker(
          if (logLevel <= InfoLevel) message else "",
          LogMarker(endMarker, deltaToStart, Some(deltaToEnd))))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitHistogramMetric(endMarker, deltaToEnd)

    //tracing support
    WhiskTracerProvider.tracer.finishSpan(this)
  }

  /**
   * Method to stop taking time of an action in the code that failed. The time the method used will be written into a log message.
   *
   * @param from Reference, where the method was called from.
   * @param startMarker <code>StartMarker</code> returned by a <code>starting</code> method.
   * @param message An additional message to be written into the log, together with the other information.
   * @param logLevel The <code>LogLevel</code> the message should have. Default is <code>WarningLevel</code>.
   */
  def failed(from: AnyRef, startMarker: StartMarker, message: => String = "", logLevel: LogLevel = WarningLevel)(
    implicit logging: Logging) = {

    val endMarker = startMarker.startMarker.asError
    val deltaToEnd = deltaToMarker(startMarker)

    if (TransactionId.metricsLog) {
      logging.emit(
        logLevel,
        this,
        from,
        createMessageWithMarker(message, LogMarker(endMarker, deltaToStart, Some(deltaToEnd))))
    } else {
      logging.emit(logLevel, this, from, message)
    }

    MetricEmitter.emitHistogramMetric(endMarker, deltaToEnd)
    MetricEmitter.emitCounterMetric(endMarker)

    //tracing support
    WhiskTracerProvider.tracer.error(this, message)
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
  def deltaToMarker(startMarker: StartMarker, endTime: Instant = Instant.now(Clock.systemUTC)) =
    Duration.between(startMarker.start, endTime).toMillis

  def hasParent = meta.parent.isDefined

  /**
   * Formats log message to include marker.
   *
   * @param message: The log message without the marker
   * @param marker: The marker to add to the message
   */
  private def createMessageWithMarker(message: String, marker: LogMarker): String = s"$message $marker"

  /**
   * Find root transaction metadata
   */
  private def findRoot(meta: TransactionMetadata): TransactionMetadata =
    meta.parent match {
      case Some(parent) => findRoot(parent)
      case _            => meta
    }
}

/**
 * The StartMarker which includes the <code>LogMarkerToken</code> and the start-time.
 *
 * @param start the time when the startMarker was set
 * @param startMarker the LogMarkerToken which defines the start event
 */
case class StartMarker(start: Instant, startMarker: LogMarkerToken)

/**
 * The transaction metadata encapsulates important properties about a transaction.
 *
 * @param id the transaction identifier; it is positive for client requests,
 *           negative for system operation and zero when originator is not known
 * @param start the timestamp when the request processing commenced
 * @param extraLogging enables logging, if set to true
 */
protected case class TransactionMetadata(id: String,
                                         start: Instant,
                                         extraLogging: Boolean = false,
                                         parent: Option[TransactionMetadata] = None) {
  override def toString = s"#tid_$id"
}

case class MetricConfig(prometheusEnabled: Boolean,
                        kamonEnabled: Boolean,
                        kamonTagsEnabled: Boolean,
                        logsEnabled: Boolean)
object TransactionId {
  val metricConfig = loadConfigOrThrow[MetricConfig](ConfigKeys.metrics)

  // get the metric parameters directly from the environment since WhiskConfig can not be instantiated here
  val metricsKamon: Boolean = metricConfig.kamonEnabled
  val metricsKamonTags: Boolean = metricConfig.kamonTagsEnabled
  val metricsLog: Boolean = metricConfig.logsEnabled

  val generatorConfig = loadConfigOrThrow[TransactionGeneratorConfig](ConfigKeys.transactions)

  val systemPrefix = "sid_"

  val unknown = TransactionId(systemPrefix + "unknown")
  val testing = TransactionId(systemPrefix + "testing") // Common id for for unit testing
  val invoker = TransactionId(systemPrefix + "invoker") // Invoker startup/shutdown or GC activity
  val invokerWarmup = TransactionId(systemPrefix + "invokerWarmup") // Invoker warmup thread that makes stem-cell containers
  val invokerNanny = TransactionId(systemPrefix + "invokerNanny") // Invoker nanny thread
  val dispatcher = TransactionId(systemPrefix + "dispatcher") // Kafka message dispatcher
  val loadbalancer = TransactionId(systemPrefix + "loadbalancer") // Loadbalancer thread
  val invokerHealth = TransactionId(systemPrefix + "invokerHealth") // Invoker supervision
  val controller = TransactionId(systemPrefix + "controller") // Controller startup
  val dbBatcher = TransactionId(systemPrefix + "dbBatcher") // Database batcher
  val actionHealthPing = TransactionId(systemPrefix + "actionHealth")

  private val dict = ('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9')

  def apply(tid: String, extraLogging: Boolean = false): TransactionId = {
    val now = Instant.now(Clock.systemUTC()).inMills
    TransactionId(TransactionMetadata(tid, now, extraLogging))
  }

  def childOf(parentTid: TransactionId): TransactionId = {
    val now = Instant.now(Clock.systemUTC()).inMills
    val tid = generateTid()
    TransactionId(TransactionMetadata(tid, now, parentTid.meta.extraLogging, Some(parentTid.meta)))
  }

  def generateTid(): String = {
    val sb = new StringBuilder
    for (_ <- 1 to 32) sb.append(dict(util.Random.nextInt(dict.length)))
    sb.toString
  }

  implicit val serdes = new RootJsonFormat[TransactionId] {

    private def writeMetadata(meta: TransactionMetadata): JsArray = {
      val base = Vector(JsString(meta.id), JsNumber(meta.start.toEpochMilli))
      val extraLogging = if (meta.extraLogging) Vector(JsBoolean(meta.extraLogging)) else Vector.empty
      val parent = meta.parent match {
        case Some(p) => Vector(writeMetadata(p))
        case _       => Vector.empty
      }
      JsArray(base ++ extraLogging ++ parent)
    }

    private def readMetadata(value: JsValue): Option[TransactionMetadata] = {
      Try {
        value match {
          case JsArray(Vector(JsString(id), JsNumber(start))) =>
            Some(TransactionMetadata(id, Instant.ofEpochMilli(start.longValue), false))
          case JsArray(Vector(JsString(id), JsNumber(start), JsBoolean(extraLogging))) =>
            Some(TransactionMetadata(id, Instant.ofEpochMilli(start.longValue), extraLogging))
          case JsArray(Vector(JsString(id), JsNumber(start), JsBoolean(extraLogging), parent)) =>
            Some(TransactionMetadata(id, Instant.ofEpochMilli(start.longValue), extraLogging, readMetadata(parent)))
          case JsArray(Vector(JsString(id), JsNumber(start), parent)) =>
            Some(TransactionMetadata(id, Instant.ofEpochMilli(start.longValue), false, readMetadata(parent)))
        }
      } getOrElse Option.empty
    }

    def write(t: TransactionId): JsArray = writeMetadata(t.meta)
    def read(value: JsValue): TransactionId = readMetadata(value).map(meta => TransactionId(meta)).getOrElse(unknown)
  }
}

case class TransactionGeneratorConfig(header: String) {
  val lowerCaseHeader = header.toLowerCase //to cache the lowercase version of the header name
}
