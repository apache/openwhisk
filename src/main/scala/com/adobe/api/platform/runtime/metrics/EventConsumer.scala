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

import java.lang.management.ManagementFactory

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import javax.management.ObjectName
import org.apache.kafka.clients.consumer.ConsumerConfig
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait MetricRecorder {
  def processEvent(activation: Activation, initiatorNamespace: String): Unit
}

case class EventConsumer(settings: ConsumerSettings[String, String], recorders: Seq[MetricRecorder])(
  implicit system: ActorSystem,
  materializer: ActorMaterializer) {
  import EventConsumer._

  private implicit val ec: ExecutionContext = system.dispatcher

  //Record the rate of events received
  private val activationCounter = Kamon.counter("openwhisk.userevents.global.activations")
  private val metricCounter = Kamon.counter("openwhisk.userevents.global.metric")

  private val statusCounter = Kamon.counter("openwhisk.userevents.global.status")
  private val coldStartCounter = Kamon.counter("openwhisk.userevents.global.coldStarts")

  private val statusSuccess = statusCounter.refine("status" -> Activation.statusSuccess)
  private val statusFailure = statusCounter.refine("status" -> "failure")
  private val statusApplicationError = statusCounter.refine("status" -> Activation.statusApplicationError)
  private val statusDeveloperError = statusCounter.refine("status" -> Activation.statusDeveloperError)
  private val statusInternalError = statusCounter.refine("status" -> Activation.statusInternalError)

  private val waitTime = Kamon.histogram("openwhisk.userevents.global.waitTime", MeasurementUnit.time.milliseconds)
  private val initTime = Kamon.histogram("openwhisk.userevents.global.initTime", MeasurementUnit.time.milliseconds)
  private val duration = Kamon.histogram("openwhisk.userevents.global.duration", MeasurementUnit.time.milliseconds)

  private val lagGauge = Kamon.gauge("openwhisk.userevents.consumer.lag")

  def shutdown(): Future[Done] = {
    lagRecorder.cancel()
    control.drainAndShutdown()(system.dispatcher)
  }

  def isRunning: Boolean = !control.isShutdown.isCompleted

  //TODO Use RestartSource
  private val control: DrainingControl[Done] = Consumer
    .committableSource(updatedSettings, Subscriptions.topics(userEventTopic))
    .map { msg =>
      processEvent(msg.record.value())
      msg.committableOffset
    }
    .batch(max = 20, CommittableOffsetBatch(_))(_.updated(_))
    .mapAsync(3)(_.commitScaladsl())
    .toMat(Sink.ignore)(Keep.both)
    .mapMaterializedValue(DrainingControl.apply)
    .run()

  private val lagRecorder =
    system.scheduler.schedule(10.seconds, 10.seconds)(lagGauge.set(consumerLag))

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
      .collect { case e if e.eventType == Activation.typeName => e } //Look for only Activations
      .foreach { e =>
        val a = e.body.asInstanceOf[Activation]
        recorders.foreach(_.processEvent(a, e.namespace))
        updateGlobalMetrics(a)
      }
  }

  private def updateGlobalMetrics(a: Activation): Unit = {
    a.status match {
      case Activation.statusSuccess          => statusSuccess.increment()
      case Activation.statusApplicationError => statusApplicationError.increment()
      case Activation.statusDeveloperError   => statusDeveloperError.increment()
      case Activation.statusInternalError    => statusInternalError.increment()
      case _                                 => //Ignore for now
    }

    if (a.status != Activation.statusSuccess) statusFailure.increment()
    if (a.isColdStart) {
      coldStartCounter.increment()
      initTime.record(a.initTime.toMillis)
    }

    waitTime.record(a.waitTime.toMillis)
    duration.record(a.duration.toMillis)
  }

  private def updatedSettings = settings.withProperty(ConsumerConfig.CLIENT_ID_CONFIG, id)
}

object EventConsumer {
  val userEventTopic = "events"
  val id = "event-consumer"

  private val server = ManagementFactory.getPlatformMBeanServer
  private val name = new ObjectName(s"kafka.consumer:type=consumer-fetch-manager-metrics,client-id=$id")

  def consumerLag: Long = server.getAttribute(name, "records-lag-max").asInstanceOf[Double].toLong.max(0)
}
