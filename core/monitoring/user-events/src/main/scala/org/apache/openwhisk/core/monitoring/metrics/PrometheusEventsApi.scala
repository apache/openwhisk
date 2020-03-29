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

import akka.http.scaladsl.model.StatusCodes.ServiceUnavailable
import akka.http.scaladsl.model.{ContentType, HttpCharsets, MediaType, MessageEntity}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.apache.openwhisk.connector.kafka.KafkaMetricRoute

import scala.concurrent.ExecutionContext

trait PrometheusExporter {
  def getReport(): MessageEntity
}

object PrometheusExporter {
  val textV4: ContentType = ContentType.apply(
    MediaType.textWithFixedCharset("plain", HttpCharsets.`UTF-8`).withParams(Map("version" -> "0.0.4")))
}

class PrometheusEventsApi(consumer: EventConsumer, prometheus: PrometheusExporter)(implicit ec: ExecutionContext) {
  val routes: Route = {
    get {
      path("ping") {
        if (consumer.isRunning) {
          complete("pong")
        } else {
          complete(ServiceUnavailable -> "Consumer not running")
        }
      } ~ path("metrics") {
        encodeResponse {
          complete(prometheus.getReport())
        }
      } ~ KafkaMetricRoute(consumer)
    }
  }
}
