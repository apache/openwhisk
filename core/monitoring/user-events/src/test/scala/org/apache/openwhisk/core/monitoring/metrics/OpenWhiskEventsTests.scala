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

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import kamon.Kamon
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import scala.concurrent.duration._

import scala.util.Try

@RunWith(classOf[JUnitRunner])
class OpenWhiskEventsTests extends KafkaSpecBase {
  behavior of "Server"

  it should "start working http server" in {
    val kconfig = EmbeddedKafkaConfig(kafkaPort = 0, zooKeeperPort = 0)
    withRunningKafkaOnFoundPort(kconfig) { implicit actualConfig =>
      val kafkaPort = actualConfig.kafkaPort
      val httpPort = freePort()
      val globalConfig = system.settings.config
      val config = ConfigFactory.parseString(s"""
           | akka.kafka.consumer.kafka-clients {
           |  bootstrap.servers = "localhost:$kafkaPort"
           | }
           | kamon {
           |  metric {
           |    tick-interval = 50 ms
           |    optimistic-tick-alignment = no
           |  }
           | }
           | user-events {
           |  port = $httpPort
           | }
         """.stripMargin).withFallback(globalConfig)

      val binding = OpenWhiskEvents.start(config).futureValue
      val res = get("localhost", httpPort, "/ping")
      res shouldBe Some(StatusCodes.OK, "pong")

      //Check if metrics using Kamon API gets included in consolidated Prometheus
      Kamon.counter("fooTest").increment(42)
      sleep(1.second)
      val metricRes = get("localhost", httpPort, "/metrics")
      metricRes.get._2 should include("fooTest")

      binding.unbind().futureValue
    }
  }

  def get(host: String, port: Int, path: String = "/") = {
    val response = Try {
      Http()
        .singleRequest(HttpRequest(uri = s"http://$host:$port$path"))
        .futureValue
    }.toOption

    response.map { res =>
      (res.status, Unmarshal(res).to[String].futureValue)
    }
  }
}
