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

package org.apache.openwhisk.core.database.persister

import akka.actor.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.model.StatusCodes.ServiceUnavailable
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import kamon.Kamon
import org.apache.openwhisk.common.{AkkaLogging, ConfigMXBean, Logging, TransactionId}
import org.apache.openwhisk.connector.kafka.KafkaMetrics
import org.apache.openwhisk.core.database.ActivationStoreProvider
import org.apache.openwhisk.http.{BasicHttpService, BasicRasService}
import org.apache.openwhisk.spi.SpiLoader
import pureconfig.loadConfigOrThrow

import scala.concurrent.ExecutionContext

object Main extends SLF4JLogging {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("persister-actor-system")
    val decider: Supervision.Decider = { e =>
      log.error("Unhandled exception in stream", e)
      Supervision.Stop
    }
    val materializerSettings = ActorMaterializerSettings(system).withSupervisionStrategy(decider)
    implicit val materializer: ActorMaterializer = ActorMaterializer(materializerSettings)
    implicit val logging: Logging = new AkkaLogging(akka.event.Logging.getLogger(system, this))

    ConfigMXBean.register()
    Kamon.loadReportersFromConfig()
    val persisterConfig =
      loadConfigOrThrow[PersisterConfig](system.settings.config.getConfig(ActivationPersisterService.configRoot))
    val port = persisterConfig.port
    val activationStore =
      SpiLoader.get[ActivationStoreProvider].instance(system, materializer, logging)
    val consumer = ActivationPersisterService.start(persisterConfig, activationStore)
    log.info(s"Started the server at http://localhost:$port")
    BasicHttpService.startHttpService(new PersisterService(consumer).route, port, None)
  }

  class PersisterService(consumer: ActivationConsumer)(implicit system: ActorSystem) extends BasicRasService {
    private implicit val ec: ExecutionContext = system.dispatcher

    override val ping: Route = path("ping") {
      if (consumer.isRunning) {
        complete("pong")
      } else {
        complete(ServiceUnavailable -> "Consumer not running")
      }
    }

    override def routes(implicit transid: TransactionId): Route = super.routes ~ kafkaMetrics

    def kafkaMetrics: Route = path("metrics" / "kafka") {
      //Exposes Kafka consumer metrics at /metrics/kafka endpoint
      val metrics = consumer.metrics().map(m => KafkaMetrics.toJson(m))
      complete(metrics)
    }
  }
}
