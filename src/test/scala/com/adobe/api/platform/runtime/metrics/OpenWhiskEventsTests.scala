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
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.ConfigFactory
import net.manub.embeddedkafka.EmbeddedKafkaConfig
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

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
           | 
           | user-events {
           |  port = $httpPort
           | }
         """.stripMargin).withFallback(globalConfig)

      val binding = OpenWhiskEvents.start(config).futureValue
      val res = ping("localhost", httpPort, "/ping")
      res shouldBe Some(StatusCodes.OK, "pong")
      binding.unbind().futureValue
    }
  }

  def ping(host: String, port: Int, path: String = "/") = {
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
