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

package org.apache.openwhisk.standalone

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

import com.google.common.base.Stopwatch
import common.WhiskProperties.WHISK_SERVER
import common.{FreePortFinder, StreamLogging, WhiskProperties}
import io.restassured.RestAssured
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.utils.retry
import org.scalatest.{BeforeAndAfterAll, Pending, Suite, TestSuite}

import scala.concurrent.duration._
import scala.sys.process._

trait StandaloneServerFixture extends TestSuite with BeforeAndAfterAll with StreamLogging {
  self: Suite =>

  private val jarPathProp = "whisk.server.jar"
  private var manifestFile: Option[File] = None
  private var serverProcess: Process = _
  protected val serverPort: Int = FreePortFinder.freePort()
  protected var serverUrl: String = System.getProperty(WHISK_SERVER, s"http://localhost:$serverPort/")
  private val disablePullConfig = "whisk.docker.standalone.container-factory.pull-standard-images"
  private var serverStartedForTest = false

  //Following tests always fail on Mac but pass when standalone server is running on Linux
  //It looks related to how networking works on Mac for Docker container
  //For now ignoring there failure
  private val ignoredTestsOnMac = Set(
    "Wsk Action REST should create, and invoke an action that utilizes a docker container",
    "Wsk Action REST should create, and invoke an action that utilizes dockerskeleton with native zip",
    "Wsk Action REST should create and invoke a blocking action resulting in an application error response",
    "Wsk Action REST should create an action, and invoke an action that returns an empty JSON object")

  override def beforeAll(): Unit = {
    val serverUrlViaSysProp = Option(System.getProperty(WHISK_SERVER))
    serverUrlViaSysProp match {
      case Some(u) =>
        serverUrl = u
        println(s"Connecting to existing server at $serverUrl")
      case None =>
        System.setProperty(WHISK_SERVER, serverUrl)
        //TODO avoid starting the server if url whisk.server property is predefined
        super.beforeAll()
        println(s"Running standalone server from ${standaloneServerJar.getAbsolutePath}")
        manifestFile = getRuntimeManifest()
        val args = Seq(
          Seq(
            "java",
            s"-D$disablePullConfig=false",
            "-jar",
            standaloneServerJar.getAbsolutePath,
            "--disable-color-logging"),
          Seq("-p", serverPort.toString),
          manifestFile.map(f => Seq("-m", f.getAbsolutePath)).getOrElse(Seq.empty)).flatten

        serverProcess = args.run(ProcessLogger(s => printstream.println(s)))
        val w = waitForServerToStart()
        serverStartedForTest = true
        println(s"Started test server at $serverUrl in [$w]")
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    if (serverStartedForTest) {
      System.clearProperty(WHISK_SERVER)
      manifestFile.foreach(FileUtils.deleteQuietly)
      serverProcess.destroy()
    }
  }

  override def withFixture(test: NoArgTest) = {
    val outcome = super.withFixture(test)
    if (outcome.isFailed) {
      println(logLines.mkString("\n"))
    }
    stream.reset()
    val result = if (outcome.isFailed && SystemUtils.IS_OS_MAC && ignoredTestsOnMac.contains(test.name)) {
      println(s"Ignoring known failed test for Mac [${test.name}]")
      Pending
    } else outcome
    result
  }

  def waitForServerToStart(): Stopwatch = {
    val w = Stopwatch.createStarted()
    retry({
      println(s"Waiting for OpenWhisk server to start since $w")
      val response = RestAssured.get(new URI(serverUrl))
      require(response.statusCode() == 200)
    }, 10, Some(1.second))
    w
  }

  private def getRuntimeManifest(): Option[File] = {
    Option(WhiskProperties.getProperty(WhiskConfig.runtimesManifest)).map { json =>
      val f = newFile()
      FileUtils.write(f, json, UTF_8)
      f
    }
  }

  private def newFile(): File = File.createTempFile("whisktest", null, null)

  private def standaloneServerJar: File = {
    Option(System.getProperty(jarPathProp)) match {
      case Some(p) =>
        val jarFile = new File(p)
        assert(
          jarFile.canRead,
          s"OpenWhisk standalone server jar file [$p] specified via system property [$jarPathProp] not found")
        jarFile
      case None =>
        fail(s"No jar file specified via system property [$jarPathProp]")
    }
  }
}
