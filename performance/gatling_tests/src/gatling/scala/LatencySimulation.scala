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

import java.nio.charset.StandardCharsets
import java.util.Base64

import extension.whisk.Predef._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.util.Resource
import org.apache.commons.io.FileUtils

class LatencySimulation extends Simulation {
  // Specify parameters for the run
  val host = sys.env("OPENWHISK_HOST")

  // Specify authentication
  val Array(uuid, key) = sys.env("API_KEY").split(":")

  // Specify thresholds
  val meanResponseTime: Int = sys.env("MEAN_RESPONSE_TIME").toInt
  val maximalMeanResponseTime: Int = sys.env.getOrElse("MAX_MEAN_RESPONSE_TIME", meanResponseTime.toString).toInt

  // Exclude runtimes
  val excludedKinds: Seq[String] = sys.env.getOrElse("EXCLUDED_KINDS", "").split(",")

  // Generate the OpenWhiskProtocol
  val openWhiskProtocol = openWhisk.apiHost(host)

  /**
   * Generate a list of actions to execute. The list is a tuple of (kind, code, actionName, main)
   * `kind` is needed to create the action
   * `code` is loaded form the files located in `resources/data`
   * `actionName` is the name of the action in OpenWhisk
   * `main` is only needed for java. This is the name of the class where the main method is located.
   */
  val actions: Seq[(String, String, String, String)] = Map(
    "nodejs:default" -> (FileUtils
      .readFileToString(Resource.body("nodeJSAction.js").get.file, StandardCharsets.UTF_8), "latencyTest_node", ""),
    "python:default" -> (FileUtils
      .readFileToString(Resource.body("pythonAction.py").get.file, StandardCharsets.UTF_8), "latencyTest_python", ""),
    "swift:default" -> (FileUtils
      .readFileToString(Resource.body("swiftAction.swift").get.file, StandardCharsets.UTF_8), "latencyTest_swift", ""),
    "java:default" -> (Base64.getEncoder.encodeToString(
      FileUtils.readFileToByteArray(Resource.body("javaAction.jar").get.file)), "latencyTest_java", "JavaAction"))
    .filterNot(e => excludedKinds.contains(e._1))
    .map {
      case (kind, (code, name, main)) =>
        (kind, code, name, main)
    }
    .toSeq

  // Define scenario
  val test = scenario("Invoke one action after each other to test latency")
    .foreach(actions, "action") {
      val code: Expression[String] = "${action._2}"
      exec(
        openWhisk("Create ${action._1} action")
          .authenticate(uuid, key)
          .action("${action._3}")
          .create(code, "${action._1}", "${action._4}"))
        .exec(openWhisk("Cold ${action._1} invocation").authenticate(uuid, key).action("${action._3}").invoke())
        .repeat(100) {
          exec(openWhisk("Warm ${action._1} invocation").authenticate(uuid, key).action("${action._3}").invoke())
        }
        .exec(openWhisk("Delete ${action._1} action").authenticate(uuid, key).action("${action._3}").delete())
    }

  val testSetup = setUp(test.inject(atOnceUsers(1)))
    .protocols(openWhiskProtocol)

  actions
    .map { case (kind, _, _, _) => s"Warm $kind invocation" }
    .foldLeft(testSetup) { (agg, cur) =>
      // One failure will make the build yellow
      agg
        .assertions(details(cur).responseTime.mean.lte(meanResponseTime))
        .assertions(details(cur).responseTime.mean.lt(maximalMeanResponseTime))
        // Mark the build yellow, if there are failed requests. And red if both conditions fail.
        .assertions(details(cur).failedRequests.count.is(0))
        .assertions(details(cur).failedRequests.percent.lte(0.1))
    }
}
