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

import com.adobe.api.platform.runtime.metrics.Activation.getNamespaceAndActionName
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.collection.concurrent.TrieMap

trait KamonMetricNames extends MetricNames {
  val activationMetric = "openwhisk.action.activations"
  val coldStartMetric = "openwhisk.action.coldStarts"
  val waitTimeMetric = "openwhisk.action.waitTime"
  val initTimeMetric = "openwhisk.action.initTime"
  val durationMetric = "openwhisk.action.duration"
  val statusMetric = "openwhisk.action.status"
}

object KamonRecorder extends MetricRecorder with KamonMetricNames {
  private val metrics = new TrieMap[String, KamonMetrics]

  def processEvent(activation: Activation, initiatorNamespace: String): Unit = {
    lookup(activation, initiatorNamespace).record(activation)
  }

  def lookup(activation: Activation, initiatorNamespace: String): KamonMetrics = {
    val name = activation.name
    val kind = activation.kind
    val memory = activation.memory.toString
    metrics.getOrElseUpdate(name, {
      val (namespace, action) = getNamespaceAndActionName(name)
      KamonMetrics(namespace, action, kind, memory, initiatorNamespace)
    })
  }

  case class KamonMetrics(namespace: String, action: String, kind: String, memory: String, initiator: String) {
    private val activationTags =
      Map(
        `actionNamespace` -> namespace,
        `initiatorNamespace` -> initiator,
        `actionName` -> action,
        `actionKind` -> kind,
        `actionMemory` -> memory)
    private val tags = Map(`actionNamespace` -> namespace, `initiatorNamespace` -> initiator, `actionName` -> action)

    private val activations = Kamon.counter(activationMetric).refine(activationTags)
    private val coldStarts = Kamon.counter(coldStartMetric).refine(tags)
    private val waitTime = Kamon.histogram(waitTimeMetric, MeasurementUnit.time.milliseconds).refine(tags)
    private val initTime = Kamon.histogram(initTimeMetric, MeasurementUnit.time.milliseconds).refine(tags)
    private val duration = Kamon.histogram(durationMetric, MeasurementUnit.time.milliseconds).refine(tags)

    def record(a: Activation): Unit = {
      activations.increment()

      if (a.isColdStart) {
        coldStarts.increment()
        initTime.record(a.initTime)
      }

      //waitTime may be zero for activations which are part of sequence
      waitTime.record(a.waitTime)
      duration.record(a.duration)

      Kamon.counter(statusMetric).refine(tags + ("status" -> a.status)).increment()
    }
  }
}
