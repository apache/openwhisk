/*
Copyright 2018 Adobe. All rights reserved.
This file is licensed to you under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under
the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
OF ANY KIND, either express or implied. See the License for the specific language
governing permissions and limitations under the License.
 */

package com.adobe.api.platform.runtime.metrics
import java.io.StringWriter
import java.util
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.{HttpEntity, MessageEntity}
import akka.stream.scaladsl.{Concat, Source}
import akka.util.ByteString
import com.adobe.api.platform.runtime.metrics.Activation.getNamespaceAndActionName
import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import kamon.prometheus.PrometheusReporter

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

trait PrometheusMetricNames extends MetricNames {
  val activationMetric = "openwhisk_action_activations_total"
  val coldStartMetric = "openwhisk_action_coldStarts_total"
  val waitTimeMetric = "openwhisk_action_waitTime_seconds"
  val initTimeMetric = "openwhisk_action_initTime_seconds"
  val durationMetric = "openwhisk_action_duration_seconds"
  val statusMetric = "openwhisk_action_status"
  val memoryMetric = "openwhisk_action_memory"
}

case class PrometheusRecorder(kamon: PrometheusReporter) extends MetricRecorder with PrometheusExporter {
  import PrometheusRecorder._
  private val metrics = new TrieMap[String, PrometheusMetrics]

  def processEvent(activation: Activation): Unit = {
    lookup(activation).record(activation)
  }

  override def getReport(): MessageEntity =
    HttpEntity(PrometheusExporter.textV4, createSource())

  private def lookup(activation: Activation): PrometheusMetrics = {
    //TODO Unregister unused actions
    val name = activation.name
    val kind = activation.kind
    val memory = activation.memory.toString
    metrics.getOrElseUpdate(name, {
      val (namespace, action) = getNamespaceAndActionName(name)
      PrometheusMetrics(namespace, action, kind, memory)
    })
  }

  case class PrometheusMetrics(namespace: String, action: String, kind: String, memory: String) {
    private val activations = activationCounter.labels(namespace, action, kind, memory)
    private val coldStarts = coldStartCounter.labels(namespace, action)
    private val waitTime = waitTimeHisto.labels(namespace, action)
    private val initTime = initTimeHisto.labels(namespace, action)
    private val duration = durationHisto.labels(namespace, action)

    private val gauge = memoryGauge.labels(namespace, action)

    private val statusSuccess = statusCounter.labels(namespace, action, Activation.statusSuccess)
    private val statusApplicationError = statusCounter.labels(namespace, action, Activation.statusApplicationError)
    private val statusDeveloperError = statusCounter.labels(namespace, action, Activation.statusDeveloperError)
    private val statusInternalError = statusCounter.labels(namespace, action, Activation.statusInternalError)

    def record(a: Activation): Unit = {
      gauge.observe(a.memory)

      activations.inc()

      if (a.initTime > 0) {
        coldStarts.inc()
        initTime.observe(seconds(a.initTime))
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.observe(seconds(a.waitTime))
      duration.observe(seconds(a.duration))

      a.status match {
        case Activation.statusSuccess          => statusSuccess.inc()
        case Activation.statusApplicationError => statusApplicationError.inc()
        case Activation.statusDeveloperError   => statusDeveloperError.inc()
        case Activation.statusInternalError    => statusInternalError.inc()
        case x                                 => statusCounter.labels(namespace, action, x).inc()
      }
    }
  }

  private def seconds(timeInMillis: Long) = TimeUnit.MILLISECONDS.toSeconds(timeInMillis)

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
    counter(activationMetric, "Activation Count", actionNamespace, actionName, actionKind, actionMemory)
  private val coldStartCounter = counter(coldStartMetric, "Cold start counts", actionNamespace, actionName)
  private val statusCounter =
    counter(statusMetric, "Activation failure status type", actionNamespace, actionName, actionStatus)
  private val waitTimeHisto = histogram(waitTimeMetric, "Internal system hold time", actionNamespace, actionName)
  private val initTimeHisto =
    histogram(initTimeMetric, "Time it took to initialize an action, e.g. docker init", actionNamespace, actionName)
  private val durationHisto =
    histogram(durationMetric, "Actual time the action code was running", actionNamespace, actionName)
  private val memoryGauge =
    histogram(memoryMetric, "Memory consumption of the action containers", actionNamespace, actionName)

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
