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

package org.apache.openwhisk.core.monitoring.metrics

import java.io.StringWriter
import java.util
import java.util.concurrent.TimeUnit

import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.model.{HttpEntity, MessageEntity}
import akka.stream.scaladsl.{Concat, Source}
import akka.util.ByteString
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import kamon.prometheus.PrometheusReporter
import org.apache.openwhisk.core.connector.{Activation, Metric}
import org.apache.openwhisk.core.entity.{ActivationEntityLimit, ActivationResponse}
import org.apache.openwhisk.core.monitoring.metrics.OpenWhiskEvents.MetricConfig

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration

trait PrometheusMetricNames extends MetricNames {
  val activationMetric = "openwhisk_action_activations_total"
  val coldStartMetric = "openwhisk_action_coldStarts_total"
  val waitTimeMetric = "openwhisk_action_waitTime_seconds"
  val initTimeMetric = "openwhisk_action_initTime_seconds"
  val durationMetric = "openwhisk_action_duration_seconds"
  val responseSizeMetric = "openwhisk_action_response_size_bytes"
  val statusMetric = "openwhisk_action_status"
  val memoryMetric = "openwhisk_action_memory"
  val userDefinedStatusCodeMetric = "openwhisk_action_status_code"

  val concurrentLimitMetric = "openwhisk_action_limit_concurrent_total"
  val timedLimitMetric = "openwhisk_action_limit_timed_total"
}

case class PrometheusRecorder(kamon: PrometheusReporter, config: MetricConfig)
    extends MetricRecorder
    with PrometheusExporter
    with SLF4JLogging {
  private val activationMetrics = new TrieMap[String, ActivationPromMetrics]
  private val limitMetrics = new TrieMap[String, LimitPromMetrics]
  private val promMetrics = PrometheusMetrics()

  override def processActivation(activation: Activation, initiator: String): Unit = {
    lookup(activation, initiator).record(activation, initiator)
  }

  override def processMetric(metric: Metric, initiator: String): Unit = {
    val limitMetric = limitMetrics.getOrElseUpdate(initiator, LimitPromMetrics(initiator))
    limitMetric.record(metric)
  }

  override def getReport(): MessageEntity =
    HttpEntity(PrometheusExporter.textV4, createSource())

  private def lookup(activation: Activation, initiator: String): ActivationPromMetrics = {
    //TODO Unregister unused actions
    val name = activation.name
    val kind = activation.kind
    val memory = activation.memory.toString
    val namespace = activation.namespace
    val action = activation.action
    activationMetrics.getOrElseUpdate(name, {
      ActivationPromMetrics(namespace, action, kind, memory, initiator)
    })
  }

  case class LimitPromMetrics(namespace: String) {
    private val concurrentLimit = promMetrics.concurrentLimitCounter.labels(namespace)
    private val timedLimit = promMetrics.timedLimitCounter.labels(namespace)

    def record(m: Metric): Unit = {
      m.metricName match {
        case "ConcurrentRateLimit"   => concurrentLimit.inc()
        case "TimedRateLimit"        => timedLimit.inc()
        case "ConcurrentInvocations" => //TODO Handle ConcurrentInvocations
        case x                       => log.warn(s"Unknown limit $x")
      }
    }
  }

  case class ActivationPromMetrics(namespace: String,
                                   action: String,
                                   kind: String,
                                   memory: String,
                                   initiatorNamespace: String) {

    private val activations = promMetrics.activationCounter.labels(namespace, initiatorNamespace, action, kind, memory)
    private val coldStarts = promMetrics.coldStartCounter.labels(namespace, initiatorNamespace, action)
    private val waitTime = promMetrics.waitTimeHisto.labels(namespace, initiatorNamespace, action)
    private val initTime = promMetrics.initTimeHisto.labels(namespace, initiatorNamespace, action)
    private val duration = promMetrics.durationHisto.labels(namespace, initiatorNamespace, action)
    private val responseSize = promMetrics.responseSizeHisto.labels(namespace, initiatorNamespace, action)

    private val gauge = promMetrics.memoryGauge.labels(namespace, initiatorNamespace, action)

    private val statusSuccess =
      promMetrics.statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusSuccess)
    private val statusApplicationError =
      promMetrics.statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusApplicationError)
    private val statusDeveloperError =
      promMetrics.statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusDeveloperError)
    private val statusInternalError =
      promMetrics.statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusWhiskError)

    def record(a: Activation, initiator: String): Unit = {
      recordActivation(a, initiator)
    }

    def recordActivation(a: Activation, initiator: String): Unit = {
      gauge.set(a.memory)

      activations.inc()

      if (a.isColdStart) {
        coldStarts.inc()
        initTime.observe(seconds(a.initTime))
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.observe(seconds(a.waitTime))
      duration.observe(seconds(a.duration))

      a.status match {
        case ActivationResponse.statusSuccess          => statusSuccess.inc()
        case ActivationResponse.statusApplicationError => statusApplicationError.inc()
        case ActivationResponse.statusDeveloperError   => statusDeveloperError.inc()
        case ActivationResponse.statusWhiskError       => statusInternalError.inc()
        case x                                         => promMetrics.statusCounter.labels(namespace, initiator, action, x).inc()
      }

      a.size.foreach(responseSize.observe(_))
      a.userDefinedStatusCode.foreach(value =>
        promMetrics.userDefinedStatusCodeCounter.labels(namespace, initiator, action, value.toString).inc())
    }
  }

  case class PrometheusMetrics() extends PrometheusMetricNames {

    private val namespace = config.renameTags.getOrElse(actionNamespace, actionNamespace)
    private val initiator = config.renameTags.getOrElse(initiatorNamespace, initiatorNamespace)
    private val action = config.renameTags.getOrElse(actionName, actionName)
    private val kind = config.renameTags.getOrElse(actionKind, actionKind)
    private val memory = config.renameTags.getOrElse(actionMemory, actionMemory)
    private val status = config.renameTags.getOrElse(actionStatus, actionStatus)
    private val statusCode = config.renameTags.getOrElse(userDefinedStatusCode, userDefinedStatusCode)

    val activationCounter =
      counter(activationMetric, "Activation Count", namespace, initiator, action, kind, memory)

    val coldStartCounter =
      counter(coldStartMetric, "Cold start counts", namespace, initiator, action)

    val statusCounter =
      counter(statusMetric, "Activation failure status type", namespace, initiator, action, status)

    val userDefinedStatusCodeCounter =
      counter(
        userDefinedStatusCodeMetric,
        "status code returned in action result response set by developer",
        namespace,
        initiator,
        action,
        statusCode)

    val waitTimeHisto =
      histogram(waitTimeMetric, "Internal system hold time", namespace, initiator, action)

    val initTimeHisto =
      histogram(initTimeMetric, "Time it took to initialize an action, e.g. docker init", namespace, initiator, action)

    val durationHisto =
      histogram(durationMetric, "Actual time the action code was running", namespace, initiator, action)

    val responseSizeHisto =
      Histogram
        .build()
        .name(responseSizeMetric)
        .help("Activation Response size")
        .labelNames(namespace, initiator, action)
        .linearBuckets(0, ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT.toBytes.toDouble, 10)
        .register()

    val memoryGauge =
      gauge(memoryMetric, "Memory consumption of the action containers", namespace, initiator, action)

    val concurrentLimitCounter =
      counter(concurrentLimitMetric, "a user has exceeded its limit for concurrent invocations", namespace)

    val timedLimitCounter =
      counter(timedLimitMetric, "the user has reached its per minute limit for the number of invocations", namespace)

    private def counter(name: String, help: String, tags: String*) =
      Counter
        .build()
        .name(name)
        .help(help)
        .labelNames(tags: _*)
        .register()

    private def gauge(name: String, help: String, tags: String*) =
      Gauge
        .build()
        .name(name)
        .help(help)
        .labelNames(tags: _*)
        .register()

    private def histogram(name: String, help: String, tags: String*) =
      Histogram
        .build()
        .name(name)
        .help(help)
        .labelNames(tags: _*)
        .register()
  }

  //Returns a floating point number
  private def seconds(time: Duration): Double = time.toUnit(TimeUnit.SECONDS)

  private def createSource() =
    Source.combine(createJavaClientSource(), createKamonSource())(Concat(_)).map(ByteString(_))

  /**
   * Enables streaming the prometheus metric data without building the whole report in memory
   */
  private def createJavaClientSource() =
    Source
      .fromIterator(() => CollectorRegistry.defaultRegistry.metricFamilySamples().asScala)
      .map { sample =>
        //Stream string representation of one sample at a time
        val writer = new StringWriter()
        TextFormat.write004(writer, singletonEnumeration(sample))
        writer.toString
      }

  private def createKamonSource() = Source.single(kamon.scrapeData())

  private def singletonEnumeration[A](value: A) = new util.Enumeration[A] {
    private var done = false
    override def hasMoreElements: Boolean = !done
    override def nextElement(): A = {
      if (done) throw new NoSuchElementException
      done = true
      value
    }
  }
}
