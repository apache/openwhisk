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

import java.net.ServerSocket

import akka.actor.ActorSystem
import com.typesafe.config.Config
import org.apache.openwhisk.core.monitoring.metrics.OpenWhiskEvents.MetricConfig
import pureconfig._
import pureconfig.generic.auto._

trait EventsTestHelper {

  protected def createConsumer(kport: Int, globalConfig: Config, recorder: MetricRecorder)(
    implicit system: ActorSystem) = {
    val settings = OpenWhiskEvents
      .eventConsumerSettings(OpenWhiskEvents.defaultConsumerConfig(globalConfig))
      .withBootstrapServers(s"localhost:$kport")
    val metricConfig = loadConfigOrThrow[MetricConfig](globalConfig, "user-events")
    EventConsumer(settings, Seq(recorder), metricConfig)
  }

  protected def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally if (socket != null) socket.close()
  }
}
