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

package org.apache.openwhisk.connector.kafka

import java.util
import java.util.concurrent.{ScheduledFuture, TimeUnit}

import kamon.Kamon
import kamon.metric.{Counter, Gauge, Metric}
import kamon.tag.TagSet
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.metrics.stats.Total
import org.apache.kafka.common.metrics.{KafkaMetric, MetricsReporter}
import org.apache.openwhisk.core.ConfigKeys
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}
import scala.collection.JavaConverters._

class KamonMetricsReporter extends MetricsReporter {
  import KamonMetricsReporter._
  private val metrics = new TrieMap[MetricName, MetricBridge]()
  private val metricConfig = loadConfigOrThrow[KafkaMetricConfig](s"${ConfigKeys.kafka}.metrics")
  @volatile
  private var updater: Option[ScheduledFuture[_]] = None

  override def init(metrics: util.List[KafkaMetric]): Unit = metrics.forEach(add)

  override def metricChange(metric: KafkaMetric): Unit = {
    remove(metric)
    add(metric)
  }

  override def metricRemoval(metric: KafkaMetric): Unit = remove(metric)

  override def close(): Unit = updater.foreach(_.cancel(false))

  override def configure(configs: util.Map[String, _]): Unit = {
    val interval = metricConfig.reportInterval.toSeconds
    val f = Kamon.scheduler().scheduleAtFixedRate(() => updateAll(), interval, interval, TimeUnit.SECONDS)
    updater = Some(f)
  }

  private def add(metric: KafkaMetric): Unit = {
    val mn = metric.metricName()
    if (metricConfig.names.contains(mn.name()) && shouldIncludeMetric(mn)) {
      val tags = kafkaTagsToTagSet(mn.tags())
      val metricName = kamonName(mn)
      val bridge = if (isCounterMetric(metric)) {
        val counter = Kamon.counter(metricName)
        new CounterBridge(metric, counter, counter.withTags(tags))
      } else {
        val gauge = Kamon.gauge(metricName)
        new GaugeBridge(metric, gauge, gauge.withTags(tags))
      }
      metrics.putIfAbsent(mn, bridge)
    }
  }

  private def remove(metric: KafkaMetric) = metrics.remove(metric.metricName()).foreach(_.remove())

  private def updateAll(): Unit = metrics.values.foreach(_.update())
}

object KamonMetricsReporter {
  val name = classOf[KamonMetricsReporter].getName
  private val excludedTopicAttributes = Set("records-lag-max", "records-consumed-total", "bytes-consumed-total")

  case class KafkaMetricConfig(names: Set[String], reportInterval: FiniteDuration)

  abstract class MetricBridge(val kafkaMetric: KafkaMetric, kamonMetric: Metric[_, _]) {
    def remove(): Unit = kamonMetric.remove(kafkaTagsToTagSet(kafkaMetric.metricName().tags()))
    def update(): Unit

    def metricValue: Long =
      Try(kafkaMetric.metricValue())
        .map {
          case d: java.lang.Double => d.toLong
          case _                   => 0L
        }
        .getOrElse(0L)
  }

  class GaugeBridge(kafkaMetric: KafkaMetric, kamonMetric: Metric.Gauge, gauge: Gauge)
      extends MetricBridge(kafkaMetric, kamonMetric) {
    override def update(): Unit = gauge.update(metricValue)
  }

  class CounterBridge(kafkaMetric: KafkaMetric, kamonMetric: Metric.Counter, counter: Counter)
      extends MetricBridge(kafkaMetric, kamonMetric) {
    @volatile
    private var lastValue: Long = 0
    override def update(): Unit = {
      val newValue = metricValue
      counter.increment(newValue - lastValue)
      lastValue = newValue
    }
  }

  def kamonName(mn: MetricName): String = {
    //Drop the `-total` suffix as it results in prometheus metrics ending with total twice
    val name = if (mn.name().endsWith("-total")) mn.name().dropRight(6) else mn.name()
    s"${mn.group()}_$name"
  }

  def isCounterMetric(metric: KafkaMetric): Boolean = Try(metric.measurable()) match {
    case Success(_: Total) => true
    case _                 => false
  }

  def shouldIncludeMetric(m: MetricName): Boolean = {
    //Avoid duplicate metrics for specific cases which are recorded at multiple level
    //For example `bytes-consumed-total` is recorded at consumer and topic level. As we use a 1-1 consumer per topic
    //We can drop the lag recording at topic level
    if (excludedTopicAttributes.contains(m.name())) !m.tags().containsKey("topic")
    else true
  }

  private def kafkaTagsToTagSet(kafkaTags: util.Map[String, String]): TagSet =
    kafkaTags.asScala.foldLeft(TagSet.Empty) {
      case (set, (k, v)) => set.withTag(k, v)
    }
}
