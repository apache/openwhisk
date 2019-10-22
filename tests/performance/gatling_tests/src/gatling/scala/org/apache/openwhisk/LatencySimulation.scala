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
import java.util.Base64

import org.apache.openwhisk.extension.whisk.Predef._
import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.core.util.ClasspathPackagedResource

import scala.concurrent.duration._

class LatencySimulation extends Simulation {
  // Specify parameters for the run
  val host = sys.env("OPENWHISK_HOST")

  // Specify authentication
  val Array(uuid, key) = sys.env("API_KEY").split(":")

  val pauseBetweenInvokes: Int = sys.env.getOrElse("PAUSE_BETWEEN_INVOKES", "0").toInt

  val MEAN_RESPONSE_TIME = "MEAN_RESPONSE_TIME"
  val MAX_MEAN_RESPONSE_TIME = "MAX_MEAN_RESPONSE_TIME"
  val MAX_ERRORS_ALLOWED = "MAX_ERRORS_ALLOWED"
  val MAX_ERRORS_ALLOWED_PERCENTAGE = "MAX_ERRORS_ALLOWED_PERCENTAGE"

  // Specify thresholds
  val meanResponseTime: Int = sys.env(MEAN_RESPONSE_TIME).toInt
  val maximalMeanResponseTime: Int = sys.env.getOrElse(MAX_MEAN_RESPONSE_TIME, meanResponseTime.toString).toInt

  val maxErrorsAllowed: Int = sys.env.getOrElse(MAX_ERRORS_ALLOWED, "0").toInt
  val maxErrorsAllowedPercentage: Double = sys.env.getOrElse(MAX_ERRORS_ALLOWED_PERCENTAGE, "0.1").toDouble

  def toKindSpecificKey(kind: String, suffix: String) = kind.split(':').head.toUpperCase + "_" + suffix

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
    "nodejs:default" -> (ClasspathPackagedResource("nodeJSAction.js", getClass.getResource("/data/nodeJSAction.js"))
      .string(StandardCharsets.UTF_8), "latencyTest_node", ""),
    "python:default" -> (ClasspathPackagedResource("pythonAction.py", getClass.getResource("/data/pythonAction.py"))
      .string(StandardCharsets.UTF_8), "latencyTest_python", ""),
    "swift:default" -> (ClasspathPackagedResource("swiftAction.swift", getClass.getResource("/data/swiftAction.swift"))
      .string(StandardCharsets.UTF_8), "latencyTest_swift", ""),
    "java:default" -> (Base64.getEncoder.encodeToString(ClasspathPackagedResource(
      "javaAction.jar",
      getClass.getResource("/data/javaAction.jar")).bytes), "latencyTest_java", "JavaAction"))
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
          // Add a pause of 100 milliseconds. Reason for this pause is, that collecting of logs runs asynchronously in
          // invoker. If this is not finished before the next request arrives, a new cold-start has to be done.
          pause(pauseBetweenInvokes.milliseconds)
            .exec(openWhisk("Warm ${action._1} invocation").authenticate(uuid, key).action("${action._3}").invoke())
        }
        .exec(openWhisk("Delete ${action._1} action").authenticate(uuid, key).action("${action._3}").delete())
    }

  val testSetup = setUp(test.inject(atOnceUsers(1)))
    .protocols(openWhiskProtocol)

  actions
    .map { case (kind, _, _, _) => kind }
    .foldLeft(testSetup) { (agg, kind) =>
      val cur = s"Warm $kind invocation"
      // One failure will make the build yellow
      val specificMeanResponseTime: Int =
        sys.env.getOrElse(toKindSpecificKey(kind, MEAN_RESPONSE_TIME), meanResponseTime.toString).toInt
      val specificMaxMeanResponseTime =
        sys.env.getOrElse(toKindSpecificKey(kind, MAX_MEAN_RESPONSE_TIME), maximalMeanResponseTime.toString).toInt
      val specificMaxErrorsAllowed =
        sys.env.getOrElse(toKindSpecificKey(kind, MAX_ERRORS_ALLOWED), maxErrorsAllowed.toString).toInt
      val specificMaxErrorsAllowedPercentage = sys.env
        .getOrElse(toKindSpecificKey(kind, MAX_ERRORS_ALLOWED_PERCENTAGE), maxErrorsAllowedPercentage.toString)
        .toDouble

      agg
        .assertions(details(cur).responseTime.mean.lte(specificMeanResponseTime))
        .assertions(details(cur).responseTime.mean.lt(specificMaxMeanResponseTime))
        // Mark the build yellow, if there are failed requests. And red if both conditions fail.
        .assertions(details(cur).failedRequests.count.lte(specificMaxErrorsAllowed))
        .assertions(details(cur).failedRequests.percent.lte(specificMaxErrorsAllowedPercentage))
    }
}
