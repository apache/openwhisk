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

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{RestartSource, Sink}
import javax.management.ObjectName
import org.apache.kafka.clients.consumer.ConsumerConfig
import kamon.Kamon
import kamon.metric.MeasurementUnit
import kamon.tag.TagSet
import org.apache.kafka.common
import org.apache.kafka.common.MetricName
import org.apache.openwhisk.connector.kafka.{KafkaMetricsProvider, KamonMetricsReporter}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.apache.openwhisk.core.connector.{Activation, EventMessage, Metric}
import org.apache.openwhisk.core.entity.ActivationResponse
import org.apache.openwhisk.core.monitoring.metrics.OpenWhiskEvents.MetricConfig

trait MetricRecorder {
  def processActivation(activation: Activation, initiatorNamespace: String): Unit
  def processMetric(metric: Metric, initiatorNamespace: String): Unit
}

case class EventConsumer(settings: ConsumerSettings[String, String],
                         recorders: Seq[MetricRecorder],
                         metricConfig: MetricConfig)(implicit system: ActorSystem, materializer: ActorMaterializer)
    extends KafkaMetricsProvider {
  import EventConsumer._

  private implicit val ec: ExecutionContext = system.dispatcher

  //Record the rate of events received
  private val activationNamespaceCounter = Kamon.counter("openwhisk.namespace.activations")
  private val activationCounter = Kamon.counter("openwhisk.userevents.global.activations").withoutTags()
  private val metricCounter = Kamon.counter("openwhisk.userevents.global.metric").withoutTags()

  private val statusCounter = Kamon.counter("openwhisk.userevents.global.status")
  private val coldStartCounter = Kamon.counter("openwhisk.userevents.global.coldStarts").withoutTags()

  private val statusSuccess = statusCounter.withTag("status", ActivationResponse.statusSuccess)
  private val statusFailure = statusCounter.withTag("status", "failure")
  private val statusApplicationError = statusCounter.withTag("status", ActivationResponse.statusApplicationError)
  private val statusDeveloperError = statusCounter.withTag("status", ActivationResponse.statusDeveloperError)
  private val statusInternalError = statusCounter.withTag("status", ActivationResponse.statusWhiskError)

  private val waitTime =
    Kamon.histogram("openwhisk.userevents.global.waitTime", MeasurementUnit.time.milliseconds).withoutTags()
  private val initTime =
    Kamon.histogram("openwhisk.userevents.global.initTime", MeasurementUnit.time.milliseconds).withoutTags()
  private val duration =
    Kamon.histogram("openwhisk.userevents.global.duration", MeasurementUnit.time.milliseconds).withoutTags()

  private val lagGauge = Kamon.gauge("openwhisk.userevents.consumer.lag").withoutTags()

  def shutdown(): Future[Done] = {
    lagRecorder.cancel()
    control.get().drainAndShutdown(result)(system.dispatcher)
  }

  def isRunning: Boolean = !control.get().isShutdown.isCompleted

  override def metrics(): Future[Map[MetricName, common.Metric]] = control.get().metrics

  private val committerSettings = CommitterSettings(system)
  private val control = new AtomicReference[Consumer.Control](Consumer.NoopControl)

  private val result = RestartSource
    .onFailuresWithBackoff(
      minBackoff = metricConfig.retry.minBackoff,
      maxBackoff = metricConfig.retry.maxBackoff,
      randomFactor = metricConfig.retry.randomFactor,
      maxRestarts = metricConfig.retry.maxRestarts) { () =>
      Consumer
        .committableSource(updatedSettings, Subscriptions.topics(userEventTopic))
        // this is to access to the Consumer.Control
        // instances of the latest Kafka Consumer source
        .mapMaterializedValue(c => control.set(c))
        .map { msg =>
          processEvent(msg.record.value())
          msg.committableOffset
        }
        .via(Committer.flow(committerSettings))
    }
    .runWith(Sink.ignore)

  private val lagRecorder =
    system.scheduler.schedule(10.seconds, 10.seconds)(lagGauge.update(consumerLag))

  private def processEvent(value: String): Unit = {
    EventMessage
      .parse(value)
      .map { e =>
        e.eventType match {
          case Activation.typeName => activationCounter.increment()
          case Metric.typeName     => metricCounter.increment()
        }
        e
      }
      .foreach { e =>
        e.body match {
          case a: Activation =>
            // only record activation if not executed in an ignored namespace
            if (!metricConfig.ignoredNamespaces.contains(e.namespace)) {
              recorders.foreach(_.processActivation(a, e.namespace))
            }
            updateGlobalMetrics(a, e.namespace)
          case m: Metric =>
            recorders.foreach(_.processMetric(m, e.namespace))
        }
      }
  }

  private def updateGlobalMetrics(a: Activation, e: String): Unit = {
    val namespaceTag: String = metricConfig.renameTags.getOrElse("namespace", "namespace")
    val initiatorTag: String = metricConfig.renameTags.getOrElse("initiator", "initiator")
    val tagSet = TagSet.from(Map(initiatorTag -> e, namespaceTag -> e))
    activationNamespaceCounter.withTags(tagSet).increment()
    a.status match {
      case ActivationResponse.statusSuccess          => statusSuccess.increment()
      case ActivationResponse.statusApplicationError => statusApplicationError.increment()
      case ActivationResponse.statusDeveloperError   => statusDeveloperError.increment()
      case ActivationResponse.statusWhiskError       => statusInternalError.increment()
      case _                                         => //Ignore for now
    }

    if (a.status != ActivationResponse.statusSuccess) statusFailure.increment()
    if (a.isColdStart) {
      coldStartCounter.increment()
      initTime.record(a.initTime.toMillis)
    }

    waitTime.record(a.waitTime.toMillis)
    duration.record(a.duration.toMillis)
  }

  private def updatedSettings =
    settings
      .withProperty(ConsumerConfig.CLIENT_ID_CONFIG, id)
      .withProperty(ConsumerConfig.METRIC_REPORTER_CLASSES_CONFIG, KamonMetricsReporter.name)
      .withStopTimeout(Duration.Zero) // https://doc.akka.io/docs/alpakka-kafka/current/consumer.html#draining-control
}

object EventConsumer {
  val userEventTopic = "events"
  val id = "event-consumer"

  private val server = ManagementFactory.getPlatformMBeanServer
  private val name = new ObjectName(s"kafka.consumer:type=consumer-fetch-manager-metrics,client-id=$id")

  def consumerLag: Long = server.getAttribute(name, "records-lag-max").asInstanceOf[Double].toLong.max(0)
}
