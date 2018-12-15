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
        Kamon.histogram("waitTime", MeasurementUnit.time.milliseconds).refine("name" -> a.name).record(a.waitTime)
        Kamon.counter("activations").refine("name" -> a.name).increment()
        println(a.name)
      }
  }
}
