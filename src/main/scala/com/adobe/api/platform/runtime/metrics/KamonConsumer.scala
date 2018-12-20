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

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffsetBatch
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import kamon.Kamon
import kamon.metric.MeasurementUnit

import scala.concurrent.Future

case class KamonConsumer(settings: ConsumerSettings[String, String])(implicit system: ActorSystem,
                                                                     materializer: ActorMaterializer) {
  import KamonConsumer._

  def shutdown(): Future[Done] = {
    control.drainAndShutdown()(system.dispatcher)
  }

  def isRunning: Boolean = !control.isShutdown.isCompleted

  //TODO Use RestartSource
  private val control: DrainingControl[Done] = Consumer
    .committableSource(settings, Subscriptions.topics(userEventTopic))
    .map { msg =>
      processEvent(msg.record.value())
      msg.committableOffset
    }
    .batch(max = 20, CommittableOffsetBatch(_))(_.updated(_))
    .mapAsync(3)(_.commitScaladsl())
    .toMat(Sink.ignore)(Keep.both)
    .mapMaterializedValue(DrainingControl.apply)
    .run()

}

object KamonConsumer {
  val userEventTopic = "events"

  private[metrics] def processEvent(value: String): Unit = {
    EventMessage
      .parse(value)
      .collect { case e if e.eventType == Activation.typeName => e } //Look for only Activations
      .foreach { e =>
        val a = e.body.asInstanceOf[Activation]
        val (namespace, action) = getNamespaceAction(a.name)

        val tags = Map(
          "namespace" -> namespace,
          "action" -> action,
          "kind" -> a.kind,
          "memory" -> a.memory.toString,
          "status" -> a.statusCode.toString)

        Kamon.counter("openwhisk.counter.container.activations").refine(tags).increment()
        Kamon
          .counter("openwhisk.counter.container.coldStarts")
          .refine(tags)
          .increment(if (a.waitTime > 0) 1L else 0L)

        Kamon
          .histogram("openwhisk.histogram.container.waitTime", MeasurementUnit.time.milliseconds)
          .refine(tags)
          .record(a.waitTime)
        Kamon
          .histogram("openwhisk.histogram.container.initTime", MeasurementUnit.time.milliseconds)
          .refine(tags)
          .record(a.initTime)
        Kamon
          .histogram("openwhisk.histogram.container.duration", MeasurementUnit.time.milliseconds)
          .refine(tags)
          .record(a.duration)
      }
  }

  /**
   * Extract namespace and action from name
   * ex. whisk.system/apimgmt/createApi -> (whisk.system, apimgmt/createApi)
   *
   * @param name
   * @return namespace, action
   */
  private def getNamespaceAction(name: String): (String, String) = {
    val nameArr = name.split("/", 2)
    return (nameArr(0), nameArr(1))
  }

}
