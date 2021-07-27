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

import java.io.PrintStream
import java.time.{Clock, Instant, ZoneId}
import java.time.format.DateTimeFormatter

import akka.event.Logging._
import akka.event.LoggingAdapter
import kamon.Kamon
import kamon.metric.{MeasurementUnit, Counter => KCounter, Gauge => KGauge, Histogram => KHistogram}
import kamon.statsd.{MetricKeyGenerator, SimpleMetricKeyGenerator}
import kamon.tag.TagSet
import org.apache.openwhisk.core.entity.ControllerInstanceId

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
  protected[common] def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: => String): Unit
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
        loggingAdapter.log(loglevel, format(id, name.toString, logmsg))
      }
    }
  }

  protected def format(id: TransactionId, name: String, logmsg: String) = {
    val currentId = if (id.hasParent) s"[$id] " else ""
    s"[${id.root}] $currentId[$name] $logmsg"
  }
}

/**
 * Implementation of Logging, that uses the output stream.
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
      case LogLevel(_)  => "UNKNOWN"
    }

    val logMessage = Seq(message).collect {
      case msg if msg.nonEmpty =>
        msg.split('\n').map(_.trim).mkString(" ")
    }
    val currentId = if (id.hasParent) id else ""

    val parts = Seq(s"[$time]", s"[$level]", s"[${id.root}]", s"[$currentId]") ++ Seq(s"[$name]") ++ logMessage
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
case class LogMarkerToken(
  component: String,
  action: String,
  state: String,
  subAction: Option[String] = None,
  tags: Map[String, String] = Map.empty)(measurementUnit: MeasurementUnit = MeasurementUnit.none) {
  private var finishToken: LogMarkerToken = _
  private var errorToken: LogMarkerToken = _

  // Using var is safe wrt thread-safety because Kamon makes sure the instances
  // (given the same key) are always the same, so a missed update is not harmful
  private var _counter: KCounter = _
  private var _histogram: KHistogram = _
  private var _gauge: KGauge = _

  override val toString = component + "_" + action + "_" + state
  val toStringWithSubAction: String =
    subAction.map(sa => component + "_" + action + "." + sa + "_" + state).getOrElse(toString)

  def asFinish: LogMarkerToken = {
    if (finishToken == null) {
      finishToken = copy(state = LoggingMarkers.finish)(measurementUnit)
    }
    finishToken
  }

  def asError: LogMarkerToken = {
    if (errorToken == null) {
      errorToken = copy(state = LoggingMarkers.error)(measurementUnit)
    }
    errorToken
  }

  def counter: KCounter = {
    if (_counter == null) {
      _counter = createCounter()
    }
    _counter
  }

  def histogram: KHistogram = {
    if (_histogram == null) {
      _histogram = createHistogram()
    }
    _histogram
  }

  def gauge: KGauge = {
    if (_gauge == null) {
      _gauge = createGauge()
    }
    _gauge
  }

  private def createCounter() = {
    if (TransactionId.metricsKamonTags) {
      Kamon.counter(createName(toString, "counter")).withTags(TagSet.from(tags))
    } else {
      Kamon.counter(createName(toStringWithSubAction, "counter")).withoutTags()
    }
  }

  private def createHistogram() = {
    if (TransactionId.metricsKamonTags) {
      Kamon.histogram(createName(toString, "histogram"), measurementUnit).withTags(TagSet.from(tags))
    } else {
      Kamon.histogram(createName(toStringWithSubAction, "histogram"), measurementUnit).withoutTags()
    }
  }

  private def createGauge() = {
    if (TransactionId.metricsKamonTags) {
      Kamon.gauge(createName(toString, "gauge"), measurementUnit).withTags(TagSet.from(tags))
    } else {
      Kamon.gauge(createName(toStringWithSubAction, "gauge"), measurementUnit).withoutTags()
    }
  }

  /**
   * Kamon 1.0 onwards does not include the metric type in the metric name which cause issue
   * for us as we use same metric name for counter and histogram. So to be backward compatible we
   * need to prefix the name with type
   */
  private def createName(name: String, metricType: String) = {
    s"$metricType.$name"
  }
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

    LogMarkerToken(component, generalAction, state, subAction)(MeasurementUnit.none)
  }

}

object MetricEmitter {
  def emitCounterMetric(token: LogMarkerToken, times: Long = 1): Unit = {
    if (TransactionId.metricsKamon) {
      token.counter.increment(times)
    }
  }

  def emitHistogramMetric(token: LogMarkerToken, value: Long): Unit = {
    if (TransactionId.metricsKamon) {
      token.histogram.record(value)
    }
  }

  def emitGaugeMetric(token: LogMarkerToken, value: Long): Unit = {
    if (TransactionId.metricsKamon) {
      token.gauge.update(value)
    }
  }
}

/**
 * Name generator to make names compatible to pre Kamon 1.0 logic. Statsd reporter "normalizes"
 * the key name by replacing all `.` with `_`. Pre 1.0 the metric category was added by Statsd
 * reporter itself. However now we pass it explicitly. So to retain the pre 1.0 name we need to replace
 * normalized name with one having category followed by `.` instead of `_`
 */
class WhiskStatsDMetricKeyGenerator(config: com.typesafe.config.Config) extends MetricKeyGenerator {
  val simpleGen = new SimpleMetricKeyGenerator(config)
  override def generateKey(name: String, tags: TagSet): String = {
    val key = simpleGen.generateKey(name, tags)
    if (key.contains(".counter_")) key.replace(".counter_", ".counter.")
    else if (key.contains(".histogram_")) key.replace(".histogram_", ".histogram.")
    else key
  }
}

object LoggingMarkers {

  val start = "start"
  val finish = "finish"
  val error = "error"
  val counter = "counter"
  val timeout = "timeout"

  private val controller = "controller"
  private val scheduler = "scheduler"
  private val invoker = "invoker"
  private val database = "database"
  private val activation = "activation"
  private val kafka = "kafka"
  private val loadbalancer = "loadbalancer"
  private val containerClient = "containerClient"
  private val containerPool = "containerPool"

  /*
   * The following markers are used to emit log messages as well as metrics. Add all LogMarkerTokens below to
   * have a reference list of all metrics. The list below contains LogMarkerToken singletons (val) as well as
   * LogMarkerToken creation functions (def). The LogMarkerToken creation functions allow to include variable
   * information in metrics, such as the controller / invoker id or commands executed by a container factory.
   *
   * When using LogMarkerTokens for emitting metrics, you should use the convenience functions only once to
   * create LogMarkerToken singletons instead of creating LogMarkerToken instances over and over again for each
   * metric emit.
   *
   * Example:
   * val MY_COUNTER_GREEN = LoggingMarkers.MY_COUNTER(GreenCounter)
   * ...
   * MetricEmitter.emitCounterMetric(MY_COUNTER_GREEN)
   *
   * instead of
   *
   * MetricEmitter.emitCounterMetric(LoggingMarkers.MY_COUNTER(GreenCounter))
   */

  /*
   * Controller related markers
   */
  def CONTROLLER_STARTUP(id: String) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(controller, s"startup", counter, None, Map("controller_id" -> id))(MeasurementUnit.none)
    else LogMarkerToken(controller, s"startup$id", counter)(MeasurementUnit.none)

  // Time of the activation in controller until it is delivered to Kafka
  val CONTROLLER_ACTIVATION =
    LogMarkerToken(controller, activation, start)(MeasurementUnit.time.milliseconds)
  val CONTROLLER_ACTIVATION_BLOCKING =
    LogMarkerToken(controller, "blockingActivation", start)(MeasurementUnit.time.milliseconds)
  val CONTROLLER_ACTIVATION_BLOCKING_DATABASE_RETRIEVAL =
    LogMarkerToken(controller, "blockingActivationDatabaseRetrieval", counter)(MeasurementUnit.none)

  // Time that is needed to load balance the activation
  val CONTROLLER_LOADBALANCER = LogMarkerToken(controller, loadbalancer, start)(MeasurementUnit.none)

  // Time that is needed to produce message in kafka
  val CONTROLLER_KAFKA = LogMarkerToken(controller, kafka, start)(MeasurementUnit.time.milliseconds)
  def INVOKER_SHAREDPACKAGE(path: String) =
    LogMarkerToken(invoker, "sharedPackage", counter, None, Map("path" -> path))(MeasurementUnit.none)
  def INVOKER_CONTAINERPOOL_MEMORY(state: String) =
    LogMarkerToken(invoker, "containerPoolMemory", counter, Some(state), Map("state" -> state))(MeasurementUnit.none)

  // System overload and random invoker assignment
  val MANAGED_SYSTEM_OVERLOAD =
    LogMarkerToken(controller, "managedInvokerSystemOverload", counter)(MeasurementUnit.none)
  val BLACKBOX_SYSTEM_OVERLOAD =
    LogMarkerToken(controller, "blackBoxInvokerSystemOverload", counter)(MeasurementUnit.none)
  /*
   * Invoker related markers
   */
  def INVOKER_STARTUP(i: Int) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(invoker, s"startup", counter, None, Map("invoker_id" -> i.toString))(MeasurementUnit.none)
    else LogMarkerToken(invoker, s"startup$i", counter)(MeasurementUnit.none)

  // Check invoker healthy state from loadbalancer
  def LOADBALANCER_INVOKER_STATUS_CHANGE(state: String) =
    LogMarkerToken(loadbalancer, "invokerState", counter, Some(state), Map("state" -> state))(MeasurementUnit.none)
  val LOADBALANCER_ACTIVATION_START = LogMarkerToken(loadbalancer, "activations", counter)(MeasurementUnit.none)

  def LOADBALANCER_ACTIVATIONS_INFLIGHT(controllerInstance: ControllerInstanceId) = {
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(
        loadbalancer,
        "activationsInflight",
        counter,
        None,
        Map("controller_id" -> controllerInstance.asString))(MeasurementUnit.none)
    else
      LogMarkerToken(loadbalancer + controllerInstance.asString, "activationsInflight", counter)(MeasurementUnit.none)
  }
  def LOADBALANCER_MEMORY_INFLIGHT(controllerInstance: ControllerInstanceId, actionType: String) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(
        loadbalancer,
        s"memory${actionType}Inflight",
        counter,
        None,
        Map("controller_id" -> controllerInstance.asString))(MeasurementUnit.none)
    else
      LogMarkerToken(loadbalancer + controllerInstance.asString, s"memory${actionType}Inflight", counter)(
        MeasurementUnit.none)

  // Counter metrics for completion acks in load balancer
  sealed abstract class CompletionAckType(val name: String) { def asString: String = name }
  case object RegularCompletionAck extends CompletionAckType("regular")
  case object ForcedCompletionAck extends CompletionAckType("forced")
  case object HealthcheckCompletionAck extends CompletionAckType("healthcheck")
  case object RegularAfterForcedCompletionAck extends CompletionAckType("regularAfterForced")
  case object ForcedAfterRegularCompletionAck extends CompletionAckType("forcedAfterRegular")

  // Convenience function to create log marker tokens used for emitting counter metrics related to completion acks.
  def LOADBALANCER_COMPLETION_ACK(controllerInstance: ControllerInstanceId, completionAckType: CompletionAckType) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(
        loadbalancer,
        "completionAck",
        counter,
        None,
        Map("controller_id" -> controllerInstance.asString, "type" -> completionAckType.asString))(MeasurementUnit.none)
    else
      LogMarkerToken(
        loadbalancer + controllerInstance.asString,
        "completionAck_" + completionAckType.asString,
        counter)(MeasurementUnit.none)

  // Time that is needed to execute the action
  val INVOKER_ACTIVATION_RUN =
    LogMarkerToken(invoker, "activationRun", start)(MeasurementUnit.time.milliseconds)

  // Time that is needed to init the action
  val INVOKER_ACTIVATION_INIT =
    LogMarkerToken(invoker, "activationInit", start)(MeasurementUnit.time.milliseconds)

  // Time needed to collect the logs
  val INVOKER_COLLECT_LOGS =
    LogMarkerToken(invoker, "collectLogs", start)(MeasurementUnit.time.milliseconds)

  // Time in invoker
  val INVOKER_ACTIVATION = LogMarkerToken(invoker, activation, start)(MeasurementUnit.none)
  def INVOKER_DOCKER_CMD(cmd: String) =
    LogMarkerToken(invoker, "docker", start, Some(cmd), Map("cmd" -> cmd))(MeasurementUnit.time.milliseconds)
  def INVOKER_DOCKER_CMD_TIMEOUT(cmd: String) =
    LogMarkerToken(invoker, "docker", timeout, Some(cmd), Map("cmd" -> cmd))(MeasurementUnit.none)
  def INVOKER_RUNC_CMD(cmd: String) =
    LogMarkerToken(invoker, "runc", start, Some(cmd), Map("cmd" -> cmd))(MeasurementUnit.time.milliseconds)
  def INVOKER_KUBEAPI_CMD(cmd: String) =
    LogMarkerToken(invoker, "kubeapi", start, Some(cmd), Map("cmd" -> cmd))(MeasurementUnit.none)
  def INVOKER_CONTAINER_START(containerState: String, invocationNamespace: String, namespace: String, action: String) =
    LogMarkerToken(
      invoker,
      "containerStart",
      counter,
      Some(containerState),
      Map(
        "containerState" -> containerState,
        "initiator" -> invocationNamespace,
        "namespace" -> namespace,
        "action" -> action))(MeasurementUnit.none)
  val INVOKER_CONTAINER_HEALTH = LogMarkerToken(invoker, "containerHealth", start)(MeasurementUnit.time.milliseconds)
  val INVOKER_CONTAINER_HEALTH_FAILED_WARM =
    LogMarkerToken(invoker, "containerHealthFailed", counter, Some("warm"), Map("containerState" -> "warm"))(
      MeasurementUnit.none)
  val INVOKER_CONTAINER_HEALTH_FAILED_PREWARM =
    LogMarkerToken(invoker, "containerHealthFailed", counter, Some("prewarm"), Map("containerState" -> "prewarm"))(
      MeasurementUnit.none)
  val CONTAINER_CLIENT_RETRIES =
    LogMarkerToken(containerClient, "retries", counter)(MeasurementUnit.none)

  val CONTAINER_POOL_RESCHEDULED_ACTIVATION =
    LogMarkerToken(containerPool, "rescheduledActivation", counter)(MeasurementUnit.none)
  val CONTAINER_POOL_RUNBUFFER_COUNT =
    LogMarkerToken(containerPool, "runBufferCount", counter)(MeasurementUnit.none)
  val CONTAINER_POOL_RUNBUFFER_SIZE =
    LogMarkerToken(containerPool, "runBufferSize", counter)(MeasurementUnit.information.megabytes)
  val CONTAINER_POOL_ACTIVE_COUNT =
    LogMarkerToken(containerPool, "activeCount", counter)(MeasurementUnit.none)
  val CONTAINER_POOL_ACTIVE_SIZE =
    LogMarkerToken(containerPool, "activeSize", counter)(MeasurementUnit.information.megabytes)
  val CONTAINER_POOL_PREWARM_COUNT =
    LogMarkerToken(containerPool, "prewarmCount", counter)(MeasurementUnit.none)
  val CONTAINER_POOL_PREWARM_SIZE =
    LogMarkerToken(containerPool, "prewarmSize", counter)(MeasurementUnit.information.megabytes)
  val CONTAINER_POOL_IDLES_COUNT =
    LogMarkerToken(containerPool, "idlesCount", counter)(MeasurementUnit.none)
  def CONTAINER_POOL_PREWARM_COLDSTART(memory: String, kind: String) =
    LogMarkerToken(containerPool, "prewarmColdstart", counter, None, Map("memory" -> memory, "kind" -> kind))(
      MeasurementUnit.none)
  def CONTAINER_POOL_PREWARM_EXPIRED(memory: String, kind: String) =
    LogMarkerToken(containerPool, "prewarmExpired", counter, None, Map("memory" -> memory, "kind" -> kind))(
      MeasurementUnit.none)
  val CONTAINER_POOL_IDLES_SIZE =
    LogMarkerToken(containerPool, "idlesSize", counter)(MeasurementUnit.information.megabytes)

  val INVOKER_TOTALMEM_BLACKBOX = LogMarkerToken(loadbalancer, "totalCapacityBlackBox", counter)(MeasurementUnit.none)
  val INVOKER_TOTALMEM_MANAGED = LogMarkerToken(loadbalancer, "totalCapacityManaged", counter)(MeasurementUnit.none)

  val HEALTHY_INVOKER_MANAGED =
    LogMarkerToken(loadbalancer, "totalHealthyInvokerManaged", counter)(MeasurementUnit.none)
  val UNHEALTHY_INVOKER_MANAGED =
    LogMarkerToken(loadbalancer, "totalUnhealthyInvokerManaged", counter)(MeasurementUnit.none)
  val UNRESPONSIVE_INVOKER_MANAGED =
    LogMarkerToken(loadbalancer, "totalUnresponsiveInvokerManaged", counter)(MeasurementUnit.none)
  val OFFLINE_INVOKER_MANAGED =
    LogMarkerToken(loadbalancer, "totalOfflineInvokerManaged", counter)(MeasurementUnit.none)

  val HEALTHY_INVOKER_BLACKBOX =
    LogMarkerToken(loadbalancer, "totalHealthyInvokerBlackBox", counter)(MeasurementUnit.none)
  val UNHEALTHY_INVOKER_BLACKBOX =
    LogMarkerToken(loadbalancer, "totalUnhealthyInvokerBlackBox", counter)(MeasurementUnit.none)
  val UNRESPONSIVE_INVOKER_BLACKBOX =
    LogMarkerToken(loadbalancer, "totalUnresponsiveInvokerBlackBox", counter)(MeasurementUnit.none)
  val OFFLINE_INVOKER_BLACKBOX =
    LogMarkerToken(loadbalancer, "totalOfflineInvokerBlackBox", counter)(MeasurementUnit.none)

  // Kafka related markers
  def KAFKA_QUEUE(topic: String) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(kafka, "topic", counter, None, Map("topic" -> topic))(MeasurementUnit.none)
    else LogMarkerToken(kafka, topic, counter)(MeasurementUnit.none)
  def KAFKA_MESSAGE_DELAY(topic: String) =
    if (TransactionId.metricsKamonTags)
      LogMarkerToken(kafka, "topic", start, Some("delay"), Map("topic" -> topic))(MeasurementUnit.time.milliseconds)
    else LogMarkerToken(kafka, topic, start, Some("delay"))(MeasurementUnit.time.milliseconds)

  // Time that is needed to produce message in kafka
  val SCHEDULER_KAFKA = LogMarkerToken(scheduler, kafka, start)(MeasurementUnit.time.milliseconds)
  val SCHEDULER_WAIT_TIME =
    LogMarkerToken(scheduler, "waitTime", counter)(MeasurementUnit.none)

  def SCHEDULER_KEEP_ALIVE(leaseId: Long) =
    LogMarkerToken(scheduler, "keepAlive", counter, None, Map("leaseId" -> leaseId.toString))(MeasurementUnit.none)
  def SCHEDULER_QUEUE = LogMarkerToken(scheduler, "queue", counter)(MeasurementUnit.none)
  def SCHEDULER_QUEUE_CREATE = LogMarkerToken(scheduler, "queueCreate", start)(MeasurementUnit.time.milliseconds)
  def SCHEDULER_QUEUE_UPDATE(reason: String) =
    LogMarkerToken(scheduler, "queueUpdate", counter, None, Map("reason" -> reason))(MeasurementUnit.none)
  def SCHEDULER_QUEUE_WAITING_ACTIVATION(action: String) =
    LogMarkerToken(scheduler, "queueActivation", counter, Some(action), Map("action" -> action))(MeasurementUnit.none)
  /*
   * General markers
   */
  val DATABASE_CACHE_HIT = LogMarkerToken(database, "cacheHit", counter)(MeasurementUnit.none)
  val DATABASE_CACHE_MISS = LogMarkerToken(database, "cacheMiss", counter)(MeasurementUnit.none)
  val DATABASE_SAVE =
    LogMarkerToken(database, "saveDocument", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_BULK_SAVE =
    LogMarkerToken(database, "saveDocumentBulk", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_DELETE =
    LogMarkerToken(database, "deleteDocument", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_GET = LogMarkerToken(database, "getDocument", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_QUERY = LogMarkerToken(database, "queryView", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_ATT_GET =
    LogMarkerToken(database, "getDocumentAttachment", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_ATT_SAVE =
    LogMarkerToken(database, "saveDocumentAttachment", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_ATT_DELETE =
    LogMarkerToken(database, "deleteDocumentAttachment", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_ATTS_DELETE =
    LogMarkerToken(database, "deleteDocumentAttachments", start)(MeasurementUnit.time.milliseconds)
  val DATABASE_BATCH_SIZE = LogMarkerToken(database, "batchSize", counter)(MeasurementUnit.none)
}
