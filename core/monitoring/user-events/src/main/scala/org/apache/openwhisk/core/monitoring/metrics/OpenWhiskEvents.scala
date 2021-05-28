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

import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.Http
import akka.kafka.ConsumerSettings
import com.typesafe.config.Config
import kamon.Kamon
import kamon.prometheus.PrometheusReporter
import org.apache.kafka.common.serialization.StringDeserializer
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object OpenWhiskEvents extends SLF4JLogging {

  case class MetricConfig(port: Int,
                          enableKamon: Boolean,
                          ignoredNamespaces: Set[String],
                          renameTags: Map[String, String],
                          retry: RetryConfig)

  case class RetryConfig(minBackoff: FiniteDuration, maxBackoff: FiniteDuration, randomFactor: Double, maxRestarts: Int)

  def start(config: Config)(implicit system: ActorSystem): Future[Http.ServerBinding] = {
    implicit val ec: ExecutionContext = system.dispatcher

    val prometheusReporter = new PrometheusReporter()
    Kamon.registerModule("prometheus", prometheusReporter)
    Kamon.init(config)

    val metricConfig = loadConfigOrThrow[MetricConfig](config, "whisk.user-events")

    val prometheusRecorder = PrometheusRecorder(prometheusReporter, metricConfig)
    val recorders = if (metricConfig.enableKamon) Seq(prometheusRecorder, KamonRecorder) else Seq(prometheusRecorder)
    val eventConsumer = EventConsumer(eventConsumerSettings(defaultConsumerConfig(config)), recorders, metricConfig)

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "shutdownConsumer") { () =>
      eventConsumer.shutdown()
    }
    val port = metricConfig.port
    val api = new PrometheusEventsApi(eventConsumer, prometheusRecorder)
    val httpBinding = Http().newServerAt("0.0.0.0", port).bindFlow(api.routes)
    httpBinding.foreach(_ => log.info(s"Started the http server on http://localhost:$port"))(system.dispatcher)
    httpBinding
  }

  def eventConsumerSettings(config: Config): ConsumerSettings[String, String] =
    ConsumerSettings(config, new StringDeserializer, new StringDeserializer)

  def defaultConsumerConfig(globalConfig: Config): Config = globalConfig.getConfig("akka.kafka.consumer")
}
