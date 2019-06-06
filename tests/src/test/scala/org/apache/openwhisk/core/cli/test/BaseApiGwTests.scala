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

package org.apache.openwhisk.core.cli.test

import java.io.File
import java.time.Instant

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.math.max

import org.junit.runner.RunWith

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

import common.TestHelpers
import common.TestUtils._
import common.WhiskProperties
import common.WskOperations
import common.WskProps
import common.WskTestHelpers

/**
 * Tests for testing the CLI "api" subcommand.  Most of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
abstract class BaseApiGwTests extends TestHelpers with WskTestHelpers with BeforeAndAfterEach with BeforeAndAfterAll {

  implicit val wskprops = WskProps()
  val wsk: WskOperations

  // This test suite makes enough CLI invocations in 60 seconds to trigger the OpenWhisk
  // throttling restriction.  To avoid CLI failures due to being throttled, track the
  // CLI invocation calls and when at the throttle limit, pause the next CLI invocation
  // with exactly enough time to relax the throttling.
  val maxActionsPerMin = WhiskProperties.getMaxActionInvokesPerMinute()
  val invocationTimes = new ArrayBuffer[Instant]()

  // Custom CLI properties file
  val cliWskPropsFile = File.createTempFile("wskprops", ".tmp")

  /**
   * Expected to be called before each action invocation to
   * settle the throttle when there isn't enough capacity to handle the test.
   */
  def checkThrottle(maxInvocationsBeforeThrottle: Int = maxActionsPerMin, throttlePercent: Int = 50) = {
    val t = Instant.now
    val tminus60 = t.minusSeconds(60)
    val invocationsLast60Seconds = invocationTimes.filter(_.isAfter(tminus60)).sorted
    val invocationCount = invocationsLast60Seconds.length
    println(s"Action invokes within last minute: ${invocationCount}")

    if (invocationCount >= maxInvocationsBeforeThrottle && throttlePercent >= 1) {
      val numInvocationsToClear = max(invocationCount / (100 / throttlePercent), 1)
      val invocationToClear = invocationsLast60Seconds(numInvocationsToClear - 1)
      println(
        s"throttling ${throttlePercent}% of action invocations within last minute = ($numInvocationsToClear) invocations")
      val throttleTime = 60.seconds.toMillis - (t.toEpochMilli - invocationToClear.toEpochMilli)

      println(s"Waiting ${throttleTime} milliseconds to settle the throttle")
      Thread.sleep(throttleTime)
    }

    invocationTimes += Instant.now
  }

  override def beforeEach() = {
    //checkThrottle()
  }

  /*
   * Create a CLI properties file for use by the tests
   */
  override def beforeAll() = {
    //Wait a while to settle the throttle so that the status of throttle algorithm implemented in checkThrottle
    //is consistent with the reality
    //Without this waiting time API Tests do fail very consistently on faster build machines
    //In the longer run the APIGW tests should be moved to a separate namespace
    Thread.sleep(60.seconds.toMillis)
    cliWskPropsFile.deleteOnExit()
    val wskprops = WskProps(token = "SOME TOKEN")
    wskprops.writeFile(cliWskPropsFile)
    println(s"wsk temporary props file created here: ${cliWskPropsFile.getCanonicalPath()}")
  }

  /*
   * Forcibly clear the throttle so that downstream tests are not affected by
   * this test suite
   */
  override def afterAll() = {
    // Check and settle the throttle so that this test won't cause issues with any follow on tests
    checkThrottle(maxInvocationsBeforeThrottle = 1, throttlePercent = 100)
  }

  def apiCreate(basepath: Option[String] = None,
                relpath: Option[String] = None,
                operation: Option[String] = None,
                action: Option[String] = None,
                apiname: Option[String] = None,
                swagger: Option[String] = None,
                responsetype: Option[String] = None,
                expectedExitCode: Int = SUCCESS_EXIT,
                cliCfgFile: Option[String] = Some(cliWskPropsFile.getCanonicalPath()))(
    implicit wskpropsOverride: WskProps): RunResult = {

    checkThrottle()
    wsk.api.create(basepath, relpath, operation, action, apiname, swagger, responsetype, expectedExitCode, cliCfgFile)(
      wskpropsOverride)
  }

  def apiList(basepathOrApiName: Option[String] = None,
              relpath: Option[String] = None,
              operation: Option[String] = None,
              limit: Option[Int] = None,
              since: Option[Instant] = None,
              full: Option[Boolean] = None,
              nameSort: Option[Boolean] = None,
              expectedExitCode: Int = SUCCESS_EXIT,
              cliCfgFile: Option[String] = Some(cliWskPropsFile.getCanonicalPath())): RunResult = {

    checkThrottle()
    wsk.api.list(basepathOrApiName, relpath, operation, limit, since, full, nameSort, expectedExitCode, cliCfgFile)
  }

  def apiGet(basepathOrApiName: Option[String] = None,
             full: Option[Boolean] = None,
             expectedExitCode: Int = SUCCESS_EXIT,
             cliCfgFile: Option[String] = Some(cliWskPropsFile.getCanonicalPath()),
             format: Option[String] = None): RunResult = {

    checkThrottle()
    wsk.api.get(basepathOrApiName, full, expectedExitCode, cliCfgFile, format)
  }

  def apiDelete(basepathOrApiName: String,
                relpath: Option[String] = None,
                operation: Option[String] = None,
                expectedExitCode: Int = SUCCESS_EXIT,
                cliCfgFile: Option[String] = Some(cliWskPropsFile.getCanonicalPath())): RunResult = {

    checkThrottle()
    wsk.api.delete(basepathOrApiName, relpath, operation, expectedExitCode, cliCfgFile)
  }
}
