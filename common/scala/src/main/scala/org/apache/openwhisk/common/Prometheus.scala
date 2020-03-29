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

package org.apache.openwhisk.common
import java.nio.charset.StandardCharsets.UTF_8

import akka.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import kamon.Kamon
import kamon.prometheus.PrometheusReporter

class KamonPrometheus extends AutoCloseable {
  private val reporter = new PrometheusReporter
  private val v4: ContentType = ContentType.apply(
    MediaType.textWithFixedCharset("plain", HttpCharsets.`UTF-8`).withParams(Map("version" -> "0.0.4")))
  Kamon.registerModule("prometheus", reporter)

  def route: Route = path("metrics") {
    get {
      encodeResponse {
        complete(getReport())
      }
    }
  }

  private def getReport() = HttpEntity(v4, reporter.scrapeData().getBytes(UTF_8))

  override def close(): Unit = reporter.stop()
}

object MetricsRoute {
  private val impl =
    if (TransactionId.metricsKamon && TransactionId.metricConfig.prometheusEnabled) Some(new KamonPrometheus)
    else None

  def apply(): Route = impl.map(_.route).getOrElse(reject)
}
