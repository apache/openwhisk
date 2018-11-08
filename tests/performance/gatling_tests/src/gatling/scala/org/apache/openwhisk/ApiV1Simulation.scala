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

import org.apache.openwhisk.extension.whisk.Predef._
import io.gatling.core.Predef._

import scala.concurrent.duration._

class ApiV1Simulation extends Simulation {

  // Specify parameters for the run
  val host = sys.env("OPENWHISK_HOST")
  val connections = sys.env("CONNECTIONS").toInt
  val seconds = sys.env.getOrElse("SECONDS", "10").toInt.seconds

  // Specify thresholds
  val requestsPerSec = sys.env("REQUESTS_PER_SEC").toInt
  val minimalRequestsPerSec = sys.env.getOrElse("MIN_REQUESTS_PER_SEC", requestsPerSec.toString).toInt
  val maxErrorsAllowed: Int = sys.env.getOrElse("MAX_ERRORS_ALLOWED", "0").toInt
  val maxErrorsAllowedPercentage: Double = sys.env.getOrElse("MAX_ERRORS_ALLOWED_PERCENTAGE", "0.1").toDouble

  // Generate the OpenWhiskProtocol
  val openWhiskProtocol = openWhisk.apiHost(host)

  // Define scenario
  val test = scenario("api/v1 endpoint")
    .during(seconds) {
      exec(openWhisk("Call api/v1 endpoint").info())
    }

  setUp(test.inject(atOnceUsers(connections)))
    .protocols(openWhiskProtocol)
    // One failure will make the build yellow
    .assertions(details("Call api/v1 endpoint").requestsPerSec.gt(minimalRequestsPerSec))
    .assertions(details("Call api/v1 endpoint").requestsPerSec.gt(requestsPerSec))
    // Mark the build yellow, if there are failed requests. And red if both conditions fail.
    .assertions(details("Call api/v1 endpoint").failedRequests.count.lte(maxErrorsAllowed))
    .assertions(details("Call api/v1 endpoint").failedRequests.percent.lte(maxErrorsAllowedPercentage))
}
