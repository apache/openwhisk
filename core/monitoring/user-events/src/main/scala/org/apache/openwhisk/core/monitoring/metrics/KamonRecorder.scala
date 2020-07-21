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

import akka.event.slf4j.SLF4JLogging
import org.apache.openwhisk.core.connector.{Activation, Metric}
import kamon.Kamon
import kamon.metric.MeasurementUnit
import kamon.tag.TagSet

import scala.collection.concurrent.TrieMap

trait KamonMetricNames extends MetricNames {
  val namespaceActivationMetric = "openwhisk.namespace.activations"
  val activationMetric = "openwhisk.action.activations"
  val coldStartMetric = "openwhisk.action.coldStarts"
  val waitTimeMetric = "openwhisk.action.waitTime"
  val initTimeMetric = "openwhisk.action.initTime"
  val durationMetric = "openwhisk.action.duration"
  val responseSizeMetric = "openwhisk.action.responseSize"
  val statusMetric = "openwhisk.action.status"
  val userDefinedStatusCodeMetric = "openwhisk.action.statusCode"

  val concurrentLimitMetric = "openwhisk.action.limit.concurrent"
  val timedLimitMetric = "openwhisk.action.limit.timed"
}

object KamonRecorder extends MetricRecorder with KamonMetricNames with SLF4JLogging {
  private val activationMetrics = new TrieMap[String, ActivationKamonMetrics]
  private val limitMetrics = new TrieMap[String, LimitKamonMetrics]

  override def processActivation(activation: Activation, initiator: String): Unit = {
    lookup(activation, initiator).record(activation)
  }

  override def processMetric(metric: Metric, initiator: String): Unit = {
    val limitMetric = limitMetrics.getOrElseUpdate(initiator, LimitKamonMetrics(initiator))
    limitMetric.record(metric)
  }

  def lookup(activation: Activation, initiator: String): ActivationKamonMetrics = {
    val name = activation.name
    val kind = activation.kind
    val memory = activation.memory.toString
    val namespace = activation.namespace
    val action = activation.action
    activationMetrics.getOrElseUpdate(name, {
      ActivationKamonMetrics(namespace, action, kind, memory, initiator)
    })
  }

  case class LimitKamonMetrics(namespace: String) {
    private val concurrentLimit = Kamon.counter(concurrentLimitMetric).withTag(`actionNamespace`, namespace)
    private val timedLimit = Kamon.counter(timedLimitMetric).withTag(`actionNamespace`, namespace)

    def record(m: Metric): Unit = {
      m.metricName match {
        case "ConcurrentRateLimit"   => concurrentLimit.increment()
        case "TimedRateLimit"        => timedLimit.increment()
        case "ConcurrentInvocations" => //TODO Handle ConcurrentInvocations
        case x                       => log.warn(s"Unknown limit $x")
      }
    }
  }

  case class ActivationKamonMetrics(namespace: String,
                                    action: String,
                                    kind: String,
                                    memory: String,
                                    initiator: String) {
    private val activationTags =
      TagSet.from(
        Map(
          `actionNamespace` -> namespace,
          `initiatorNamespace` -> initiator,
          `actionName` -> action,
          `actionKind` -> kind,
          `actionMemory` -> memory))
    private val namespaceActivationsTags =
      TagSet.from(Map(`actionNamespace` -> namespace, `initiatorNamespace` -> initiator))
    private val tags =
      TagSet.from(Map(`actionNamespace` -> namespace, `initiatorNamespace` -> initiator, `actionName` -> action))

    private val activations = Kamon.counter(activationMetric).withTags(activationTags)
    private val coldStarts = Kamon.counter(coldStartMetric).withTags(tags)
    private val waitTime = Kamon.histogram(waitTimeMetric, MeasurementUnit.time.milliseconds).withTags(tags)
    private val initTime = Kamon.histogram(initTimeMetric, MeasurementUnit.time.milliseconds).withTags(tags)
    private val duration = Kamon.histogram(durationMetric, MeasurementUnit.time.milliseconds).withTags(tags)
    private val responseSize = Kamon.histogram(responseSizeMetric, MeasurementUnit.information.bytes).withTags(tags)
    private val userDefinedStatusCode = Kamon.counter(userDefinedStatusCodeMetric).withTags(tags)

    def record(a: Activation): Unit = {
      recordActivation(a)
    }

    def recordActivation(a: Activation): Unit = {
      activations.increment()

      if (a.isColdStart) {
        coldStarts.increment()
        initTime.record(a.initTime.toMillis)
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.record(a.waitTime.toMillis)
      duration.record(a.duration.toMillis)

      Kamon.counter(statusMetric).withTags(tags.withTag("status", a.status)).increment()

      a.size.foreach(responseSize.record(_))
      a.userDefinedStatusCode.foreach(
        value =>
          Kamon
            .counter(userDefinedStatusCodeMetric)
            .withTags(tags.withTag("userDefinedStatusCode", value.toString))
            .increment())
    }
  }
}
