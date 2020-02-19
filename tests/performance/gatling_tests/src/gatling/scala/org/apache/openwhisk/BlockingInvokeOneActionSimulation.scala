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

package org.apache.openwhisk

import java.nio.charset.StandardCharsets

import org.apache.openwhisk.extension.whisk.OpenWhiskProtocolBuilder
import org.apache.openwhisk.extension.whisk.Predef._
import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.core.util.ClasspathPackagedResource

import scala.concurrent.duration._

class BlockingInvokeOneActionSimulation extends Simulation {
  // Specify parameters for the run
  val host = sys.env("OPENWHISK_HOST")

  // Specify authentication
  val Array(uuid, key) = sys.env("API_KEY").split(":")

  val connections: Int = sys.env("CONNECTIONS").toInt
  val seconds: FiniteDuration = sys.env.getOrElse("SECONDS", "10").toInt.seconds

  // Specify thresholds
  val requestsPerSec: Int = sys.env("REQUESTS_PER_SEC").toInt
  val minimalRequestsPerSec: Int = sys.env.getOrElse("MIN_REQUESTS_PER_SEC", requestsPerSec.toString).toInt
  val maxErrorsAllowed: Int = sys.env.getOrElse("MAX_ERRORS_ALLOWED", "0").toInt
  val maxErrorsAllowedPercentage: Double = sys.env.getOrElse("MAX_ERRORS_ALLOWED_PERCENTAGE", "0.1").toDouble

  // Generate the OpenWhiskProtocol
  val openWhiskProtocol: OpenWhiskProtocolBuilder = openWhisk.apiHost(host)

  // Specify async
  val async = sys.env.getOrElse("ASYNC", "false").toBoolean

  val actionName = "testActionForBlockingInvokeOneAction"
  val actionfile = if (async) "/data/nodeJSAsyncAction.js" else "/data/nodeJSAction.js"

  // Define scenario
  val test: ScenarioBuilder = scenario(s"Invoke one ${if (async) "async" else "sync"} action blocking")
    .doIf(_.userId == 1) {
      exec(openWhisk("Create action")
        .authenticate(uuid, key)
        .action(actionName)
        .create(ClasspathPackagedResource(actionfile, getClass.getResource(actionfile)).string(StandardCharsets.UTF_8)))
    }
    .rendezVous(connections)
    .during(5.seconds) {
      exec(openWhisk("Warm containers up").authenticate(uuid, key).action(actionName).invoke())
    }
    .rendezVous(connections)
    .during(seconds) {
      exec(openWhisk("Invoke action").authenticate(uuid, key).action(actionName).invoke())
    }
    .rendezVous(connections)
    .doIf(_.userId == 1) {
      exec(openWhisk("Delete action").authenticate(uuid, key).action(actionName).delete())
    }

  setUp(test.inject(atOnceUsers(connections)))
    .protocols(openWhiskProtocol)
    // One failure will make the build yellow
    .assertions(details("Invoke action").requestsPerSec.gt(minimalRequestsPerSec))
    .assertions(details("Invoke action").requestsPerSec.gt(requestsPerSec))
    // Mark the build yellow, if there are failed requests. And red if both conditions fail.
    .assertions(details("Invoke action").failedRequests.count.lte(maxErrorsAllowed))
    .assertions(details("Invoke action").failedRequests.percent.lte(maxErrorsAllowedPercentage))
}
