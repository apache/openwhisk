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
import common.{FreePortFinder, StreamLogging, WhiskProperties, Wsk}
import io.restassured.RestAssured
import org.apache.commons.io.{FileUtils, FilenameUtils}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.utils.retry
import org.scalatest.{BeforeAndAfterAll, Suite, TestSuite}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.sys.process._
import scala.util.control.NonFatal

trait StandaloneServerFixture extends TestSuite with BeforeAndAfterAll with StreamLogging {
  self: Suite =>

  private val jarPathProp = "whisk.server.jar"
  private var serverProcess: Process = _
  protected val serverPort: Int = FreePortFinder.freePort()
  protected var serverUrl: String = System.getProperty(WHISK_SERVER, s"http://localhost:$serverPort/")
  private val disablePullConfig = "whisk.docker.standalone.container-factory.pull-standard-images"
  private var serverStartedForTest = false
  private val tempFiles = ListBuffer[File]()

  private val whiskServerPreDefined = System.getProperty(WHISK_SERVER) != null

  protected def extraArgs: Seq[String] = Seq.empty
  protected def extraVMArgs: Seq[String] = Seq.empty
  protected def customConfig: Option[String] = None

  protected def waitForOtherThings(): Unit = {}

  protected def dumpLogsAlways: Boolean = false

  protected def dumpStartupLogs: Boolean = false

  protected def disablePlayGround: Boolean = true

  protected val dataDirPath: String = FilenameUtils.concat(FileUtils.getTempDirectoryPath, "standalone")

  override def beforeAll(): Unit = {
    val serverUrlViaSysProp = Option(System.getProperty(WHISK_SERVER))
    serverUrlViaSysProp match {
      case Some(u) =>
        serverUrl = u
        println(s"Connecting to existing server at $serverUrl")
      case None =>
        System.setProperty(WHISK_SERVER, serverUrl)
        super.beforeAll()
        println(s"Running standalone server from ${standaloneServerJar.getAbsolutePath}")
        val pgArgs = if (disablePlayGround) Seq("--no-ui") else Seq.empty
        val args = Seq(
          Seq(
            "java",
            //For tests let it bound on all ip to make it work on travis which uses linux
            "-Dwhisk.controller.interface=0.0.0.0",
            s"-Dwhisk.standalone.wsk=${Wsk.defaultCliPath}",
            s"-D$disablePullConfig=false")
            ++ extraVMArgs
            ++ Seq("-jar", standaloneServerJar.getAbsolutePath, "--disable-color-logging", "--data-dir", dataDirPath)
            ++ configFileOpts
            ++ manifestFileOpts
            ++ pgArgs
            ++ extraArgs,
          Seq("-p", serverPort.toString)).flatten

        serverProcess = args.run(ProcessLogger(s => printstream.println(s)))
        val w = waitForServerToStart()
        serverStartedForTest = true
        println(s"Started test server at $serverUrl in [$w]")
        waitForOtherThings()
        if (dumpStartupLogs) {
          println(logLines.mkString("\n"))
        }
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    if (!whiskServerPreDefined) {
      System.clearProperty(WHISK_SERVER)
    }
    if (serverStartedForTest) {
      serverProcess.destroy()
      FileUtils.forceDelete(new File(dataDirPath))
      tempFiles.foreach(FileUtils.deleteQuietly)
    }
  }

  override def withFixture(test: NoArgTest) = {
    val outcome = super.withFixture(test)
    if (outcome.isFailed || (outcome.isSucceeded && dumpLogsAlways)) {
      println(logLines.mkString("\n"))
    }
    stream.reset()
    outcome
  }

  def waitForServerToStart(): Stopwatch = {
    val w = Stopwatch.createStarted()
    try {
      retry({
        println(s"Waiting for OpenWhisk server to start since $w")
        val response = RestAssured.get(new URI(serverUrl))
        require(response.statusCode() == 200)
      }, 60, Some(1.second))
    } catch {
      case NonFatal(e) =>
        println(logLines.mkString("\n"))
        throw e
    }
    w
  }

  private def configFileOpts: Seq[String] = {
    customConfig
      .map(fileOpt("-c", _))
      .getOrElse(Seq.empty)
  }

  private def manifestFileOpts: Seq[String] = {
    Option(WhiskProperties.getProperty(WhiskConfig.runtimesManifest))
      .map(fileOpt("-m", _))
      .getOrElse(Seq.empty)
  }

  private def fileOpt(optName: String, content: String): Seq[String] = {
    val f = File.createTempFile("whisktest", null, null)
    tempFiles += f
    FileUtils.write(f, content, UTF_8)
    Seq(optName, f.getAbsolutePath)
  }

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
