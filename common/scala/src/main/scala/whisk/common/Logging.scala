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

package whisk.common

import java.io.PrintStream
import java.time.{Clock, Instant, ZoneId}
import java.time.format.DateTimeFormatter
import akka.event.Logging._
import akka.event.LoggingAdapter
import kamon.Kamon
import whisk.core.entity.ControllerInstanceId

trait Logging {

  /**
   * Prints a message on DEBUG level
   *
   * @param from Reference, where the method was called from.
   * @param message Message to write to the log if not empty
   */
  def debug(from: AnyRef, message: => String)(implicit id: TransactionId = TransactionId.unknown) = {
    if (id.meta.extraLogging) {
      emit(InfoLevel, id, from, message)
    } else {
      emit(DebugLevel, id, from, message)
    }
  }

  /**
   * Prints a message on INFO level
   *
   * @param from Reference, where the method was called from.
   * @param message Message to write to the log if not empty
   */
  def info(from: AnyRef, message: => String)(implicit id: TransactionId = TransactionId.unknown) = {
    emit(InfoLevel, id, from, message)
  }

  /**
   * Prints a message on WARN level
   *
   * @param from Reference, where the method was called from.
   * @param message Message to write to the log if not empty
   */
  def warn(from: AnyRef, message: => String)(implicit id: TransactionId = TransactionId.unknown) = {
    emit(WarningLevel, id, from, message)
  }

  /**
   * Prints a message on ERROR level
   *
   * @param from Reference, where the method was called from.
   * @param message Message to write to the log if not empty
   */
  def error(from: AnyRef, message: => String)(implicit id: TransactionId = TransactionId.unknown) = {
    emit(ErrorLevel, id, from, message)
  }

  /**
   * Prints a message to the output.
   *
   * @param loglevel The level to log on
   * @param id <code>TransactionId</code> to include in the log
   * @param from Reference, where the method was called from.
   * @param message Message to write to the log if not empty
   */
  protected[common] def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: => String)
}

/**
 * Implementation of Logging, that uses Akka logging.
 */
class AkkaLogging(loggingAdapter: LoggingAdapter) extends Logging {
  def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: => String) = {
    if (loggingAdapter.isEnabled(loglevel)) {
      val logmsg: String = message // generates the message
      if (logmsg.nonEmpty) { // log it only if its not empty
        val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)
        loggingAdapter.log(loglevel, s"[$id] [$name] $logmsg")
      }
    }
  }
}

/**
 * Implementaion of Logging, that uses the output stream.
 */
class PrintStreamLogging(outputStream: PrintStream = Console.out) extends Logging {
  override def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: => String) = {
    val now = Instant.now(Clock.systemUTC)
    val time = Emitter.timeFormat.format(now)
    val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)

    val level = loglevel match {
      case DebugLevel   => "DEBUG"
      case InfoLevel    => "INFO"
      case WarningLevel => "WARN"
      case ErrorLevel   => "ERROR"
    }

    val logMessage = Seq(message).collect {
      case msg if msg.nonEmpty =>
        msg.split('\n').map(_.trim).mkString(" ")
    }

    val parts = Seq(s"[$time]", s"[$level]", s"[$id]") ++ Seq(s"[$name]") ++ logMessage
    outputStream.println(parts.mkString(" "))
  }
}

/**
 * A triple representing the timestamp relative to which the elapsed time was computed,
 * typically for a TransactionId, the elapsed time in milliseconds and a string containing
 * the given marker token.
 *
 * @param token the LogMarkerToken that should be defined in LoggingMarkers
 * @param deltaToTransactionStart the time difference between now and the start of the Transaction
 * @param deltaToMarkerStart if this is an end marker, this is the time difference to the start marker
 */
case class LogMarker(token: LogMarkerToken, deltaToTransactionStart: Long, deltaToMarkerStart: Option[Long] = None) {
  override def toString() = {
    val parts = Seq(LogMarker.keyword, token.toStringWithSubAction, deltaToTransactionStart) ++ deltaToMarkerStart
    "[" + parts.mkString(":") + "]"
  }
}

object LogMarker {

  val keyword = "marker"

  /** Convenience method for parsing log markers in unit tests. */
  def parse(s: String) = {
    val logmarker = raw"\[${keyword}:([^\s:]+):(\d+)(?::(\d+))?\]".r.unanchored
    val logmarker(token, deltaToTransactionStart, deltaToMarkerStart) = s
    LogMarker(LogMarkerToken.parse(token), deltaToTransactionStart.toLong, Option(deltaToMarkerStart).map(_.toLong))
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
}

private object Emitter {
  val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.of("UTC"))
}

/**
 * Used to record log message and make a metric name.
 *
 * @param component Component like invoker, controller, and docker. It is defined in LoggingMarkers.
 * @param action Action of the component.
 * @param state State of the action.
 * @param subAction more specific identifier for "action", like `runc.resume`
 * @param tags tags can be used for whatever granularity you might need.
 */
case class LogMarkerToken(component: String,
                          action: String,
                          state: String,
                          subAction: Option[String] = None,
                          tags: Map[String, String] = Map.empty) {

  override def toString = component + "_" + action + "_" + state
  def toStringWithSubAction =
    subAction.map(sa => component + "_" + action + "." + sa + "_" + state).getOrElse(toString)

  def asFinish = copy(state = LoggingMarkers.finish)
  def asError = copy(state = LoggingMarkers.error)
}

object LogMarkerToken {

  def parse(string: String) = {
    // Per convention the components are guaranteed to not contain '_'
    // thus it's safe to split at '_' to get the components
    val Array(component, action, state) = string.split('_')

    val (generalAction, subAction) = action.split('.').toList match {
      case Nil         => throw new IllegalArgumentException("LogMarkerToken malformed")
      case a :: Nil    => (a, None)
      case a :: s :: _ => (a, Some(s))
    }

    LogMarkerToken(component, generalAction, state, subAction)
  }

}

object MetricEmitter {

  val metrics = Kamon.metrics

  def emitCounterMetric(token: LogMarkerToken): Unit = {
    if (TransactionId.metricsKamon) {
      if (TransactionId.metricsKamonTags) {
        metrics
          .counter(token.toString, token.tags)
          .increment(1)
      } else {
        metrics.counter(token.toStringWithSubAction).increment(1)
      }
    }
  }

  def emitHistogramMetric(token: LogMarkerToken, value: Long): Unit = {
    if (TransactionId.metricsKamon) {
      if (TransactionId.metricsKamonTags) {
        metrics
          .histogram(token.toString, token.tags)
          .record(value)
      } else {
        metrics.histogram(token.toStringWithSubAction).record(value)
      }
    }
  }
}

object LoggingMarkers {

  val start = "start"
  val finish = "finish"
  val error = "error"
  val count = "count"

  private val controller = "controller"
  private val invoker = "invoker"
  private val database = "database"
  private val activation = "activation"
  private val kafka = "kafka"
  private val loadbalancer = "loadbalancer"
  private val containerClient = "containerClient"

  /*
   * Controller related markers
   */
  def CONTROLLER_STARTUP(id: String) = LogMarkerToken(controller, s"startup$id", count)

  // Time of the activation in controller until it is delivered to Kafka
  val CONTROLLER_ACTIVATION = LogMarkerToken(controller, activation, start)
  val CONTROLLER_ACTIVATION_BLOCKING = LogMarkerToken(controller, "blockingActivation", start)

  // Time that is needed load balance the activation
  val CONTROLLER_LOADBALANCER = LogMarkerToken(controller, loadbalancer, start)

  // Time that is needed to produce message in kafka
  val CONTROLLER_KAFKA = LogMarkerToken(controller, kafka, start)

  /*
   * Invoker related markers
   */
  def INVOKER_STARTUP(i: Int) = LogMarkerToken(invoker, s"startup$i", count)

  // Check invoker healthy state from loadbalancer
  def LOADBALANCER_INVOKER_STATUS_CHANGE(state: String) =
    LogMarkerToken(loadbalancer, "invokerState", count, Some(state))
  val LOADBALANCER_ACTIVATION_START = LogMarkerToken(loadbalancer, "activations", count)

  def LOADBALANCER_ACTIVATIONS_INFLIGHT(controllerInstance: ControllerInstanceId) =
    LogMarkerToken(loadbalancer + controllerInstance.asString, "activationsInflight", count)
  def LOADBALANCER_MEMORY_INFLIGHT(controllerInstance: ControllerInstanceId) =
    LogMarkerToken(loadbalancer + controllerInstance.asString, "memoryInflight", count)

  // Time that is needed to execute the action
  val INVOKER_ACTIVATION_RUN = LogMarkerToken(invoker, "activationRun", start)

  // Time that is needed to init the action
  val INVOKER_ACTIVATION_INIT = LogMarkerToken(invoker, "activationInit", start)

  // Time needed to collect the logs
  val INVOKER_COLLECT_LOGS = LogMarkerToken(invoker, "collectLogs", start)

  // Time in invoker
  val INVOKER_ACTIVATION = LogMarkerToken(invoker, activation, start)
  def INVOKER_DOCKER_CMD(cmd: String) = LogMarkerToken(invoker, "docker", start, Some(cmd), Map("cmd" -> cmd))
  def INVOKER_RUNC_CMD(cmd: String) = LogMarkerToken(invoker, "runc", start, Some(cmd), Map("cmd" -> cmd))
  def INVOKER_KUBECTL_CMD(cmd: String) = LogMarkerToken(invoker, "kubectl", start, Some(cmd), Map("cmd" -> cmd))
  def INVOKER_CONTAINER_START(containerState: String) =
    LogMarkerToken(invoker, "containerStart", count, Some(containerState), Map("containerState" -> containerState))
  val CONTAINER_CLIENT_RETRIES =
    LogMarkerToken(containerClient, "retries", count)

  // Kafka related markers
  def KAFKA_QUEUE(topic: String) = LogMarkerToken(kafka, topic, count)
  def KAFKA_MESSAGE_DELAY(topic: String) = LogMarkerToken(kafka, topic, start, Some("delay"))

  /*
   * General markers
   */
  val DATABASE_CACHE_HIT = LogMarkerToken(database, "cacheHit", count)
  val DATABASE_CACHE_MISS = LogMarkerToken(database, "cacheMiss", count)
  val DATABASE_SAVE = LogMarkerToken(database, "saveDocument", start)
  val DATABASE_BULK_SAVE = LogMarkerToken(database, "saveDocumentBulk", start)
  val DATABASE_DELETE = LogMarkerToken(database, "deleteDocument", start)
  val DATABASE_GET = LogMarkerToken(database, "getDocument", start)
  val DATABASE_QUERY = LogMarkerToken(database, "queryView", start)
  val DATABASE_ATT_GET = LogMarkerToken(database, "getDocumentAttachment", start)
  val DATABASE_ATT_SAVE = LogMarkerToken(database, "saveDocumentAttachment", start)
  val DATABASE_ATT_DELETE = LogMarkerToken(database, "deleteDocumentAttachment", start)
  val DATABASE_ATTS_DELETE = LogMarkerToken(database, "deleteDocumentAttachments", start)
  val DATABASE_BATCH_SIZE = LogMarkerToken(database, "batchSize", count)
}
