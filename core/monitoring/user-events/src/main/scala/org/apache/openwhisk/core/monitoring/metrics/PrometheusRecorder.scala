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
import org.apache.openwhisk.core.connector.{Activation, Metric}
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import kamon.prometheus.PrometheusReporter
import org.apache.openwhisk.core.entity.ActivationResponse

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration

trait PrometheusMetricNames extends MetricNames {
  val activationMetric = "openwhisk_action_activations_total"
  val coldStartMetric = "openwhisk_action_coldStarts_total"
  val waitTimeMetric = "openwhisk_action_waitTime_seconds"
  val initTimeMetric = "openwhisk_action_initTime_seconds"
  val durationMetric = "openwhisk_action_duration_seconds"
  val statusMetric = "openwhisk_action_status"
  val memoryMetric = "openwhisk_action_memory"

  val concurrentLimitMetric = "openwhisk_action_limit_concurrent_total"
  val timedLimitMetric = "openwhisk_action_limit_timed_total"
}

case class PrometheusRecorder(kamon: PrometheusReporter)
    extends MetricRecorder
    with PrometheusExporter
    with SLF4JLogging {
  import PrometheusRecorder._
  private val activationMetrics = new TrieMap[String, ActivationPromMetrics]
  private val limitMetrics = new TrieMap[String, LimitPromMetrics]

  override def processActivation(activation: Activation, initiatorNamespace: String): Unit = {
    lookup(activation, initiatorNamespace).record(activation)
  }

  override def processMetric(metric: Metric, initiatorNamespace: String): Unit = {
    val limitMetric = limitMetrics.getOrElseUpdate(initiatorNamespace, LimitPromMetrics(initiatorNamespace))
    limitMetric.record(metric)
  }

  override def getReport(): MessageEntity =
    HttpEntity(PrometheusExporter.textV4, createSource())

  private def lookup(activation: Activation, initiatorNamespace: String): ActivationPromMetrics = {
    //TODO Unregister unused actions
    val name = activation.name
    val kind = activation.kind
    val memory = activation.memory.toString
    val namespace = activation.namespace
    val action = activation.action
    activationMetrics.getOrElseUpdate(name, {
      ActivationPromMetrics(namespace, action, kind, memory, initiatorNamespace)
    })
  }

  case class LimitPromMetrics(namespace: String) {
    private val concurrentLimit = concurrentLimitCounter.labels(namespace)
    private val timedLimit = timedLimitCounter.labels(namespace)

    def record(m: Metric): Unit = {
      m.metricName match {
        case "ConcurrentRateLimit" => concurrentLimit.inc()
        case "TimedRateLimit"      => timedLimit.inc()
        case x                     => log.warn(s"Unknown limit $x")
      }
    }
  }

  case class ActivationPromMetrics(namespace: String,
                                   action: String,
                                   kind: String,
                                   memory: String,
                                   initiatorNamespace: String) {
    private val activations = activationCounter.labels(namespace, initiatorNamespace, action, kind, memory)
    private val coldStarts = coldStartCounter.labels(namespace, initiatorNamespace, action)
    private val waitTime = waitTimeHisto.labels(namespace, initiatorNamespace, action)
    private val initTime = initTimeHisto.labels(namespace, initiatorNamespace, action)
    private val duration = durationHisto.labels(namespace, initiatorNamespace, action)

    private val gauge = memoryGauge.labels(namespace, initiatorNamespace, action)

    private val statusSuccess =
      statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusSuccess)
    private val statusApplicationError =
      statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusApplicationError)
    private val statusDeveloperError =
      statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusDeveloperError)
    private val statusInternalError =
      statusCounter.labels(namespace, initiatorNamespace, action, ActivationResponse.statusWhiskError)

    def record(a: Activation): Unit = {
      gauge.observe(a.memory)

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
        case x                                         => statusCounter.labels(namespace, initiatorNamespace, action, x).inc()
      }
    }
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

object PrometheusRecorder extends PrometheusMetricNames {
  private val activationCounter =
    counter(
      activationMetric,
      "Activation Count",
      actionNamespace,
      initiatorNamespace,
      actionName,
      actionKind,
      actionMemory)
  private val coldStartCounter =
    counter(coldStartMetric, "Cold start counts", actionNamespace, initiatorNamespace, actionName)
  private val statusCounter =
    counter(
      statusMetric,
      "Activation failure status type",
      actionNamespace,
      initiatorNamespace,
      actionName,
      actionStatus)
  private val waitTimeHisto =
    histogram(waitTimeMetric, "Internal system hold time", actionNamespace, initiatorNamespace, actionName)
  private val initTimeHisto =
    histogram(
      initTimeMetric,
      "Time it took to initialize an action, e.g. docker init",
      actionNamespace,
      initiatorNamespace,
      actionName)
  private val durationHisto =
    histogram(
      durationMetric,
      "Actual time the action code was running",
      actionNamespace,
      initiatorNamespace,
      actionName)
  private val memoryGauge =
    histogram(
      memoryMetric,
      "Memory consumption of the action containers",
      actionNamespace,
      initiatorNamespace,
      actionName)

  private val concurrentLimitCounter =
    counter(concurrentLimitMetric, "a user has exceeded its limit for concurrent invocations", actionNamespace)

  private val timedLimitCounter =
    counter(
      timedLimitMetric,
      "the user has reached its per minute limit for the number of invocations",
      actionNamespace)

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
