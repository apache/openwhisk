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

import akka.http.scaladsl.server.{Directives, Route}
import org.apache.kafka.common.{Metric, MetricName => JMetricName}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

trait KafkaMetricsProvider {
  def metrics(): Future[Map[JMetricName, Metric]]
}

/**
 * Utility to convert a map of Kafka metrics to JSON
 */
object KafkaMetrics {
  private case class MetricName(name: String, group: String, description: String, tags: Map[String, String])

  private object MetricName extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat4(MetricName.apply)
    def apply(m: JMetricName): MetricName = new MetricName(m.name(), m.group(), m.description(), m.tags().asScala.toMap)
  }

  def toJson(metrics: Map[JMetricName, Metric]): JsValue = {
    val result = metrics.values.flatMap { m =>
      getValue(m).map { v =>
        val json = MetricName(m.metricName()).toJson.asJsObject
        JsObject(json.fields + ("value" -> v))
      }
    }.toSeq
    result.toJson
  }

  private def getValue(m: Metric): Option[JsValue] = {
    Try(m.metricValue()) match {
      case Success(v: java.lang.Double) => Some(JsNumber(v.toDouble))
      case Success(v: String)           => Some(JsString(v))
      case _                            => None
    }
  }
}

/**
 * Exposes the Kafka metrics as a json endpoint `/metrics/kafka`. This can be used
 * to expose metrics of a specific consumer or producer like in User Event service
 */
object KafkaMetricRoute extends Directives {
  def apply(provider: KafkaMetricsProvider)(implicit ec: ExecutionContext): Route = {
    path("metrics" / "kafka") {
      val metrics = provider.metrics().map(m => KafkaMetrics.toJson(m))
      complete(metrics)
    }
  }
}
