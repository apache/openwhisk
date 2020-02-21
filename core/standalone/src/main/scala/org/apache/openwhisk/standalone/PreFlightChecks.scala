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

import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.lang3.StringUtils
import org.apache.openwhisk.standalone.StandaloneDockerSupport.isPortFree
import pureconfig._
import pureconfig.generic.auto._

import scala.io.AnsiColor
import scala.sys.process._
import scala.util.Try

case class PreFlightChecks(conf: Conf) extends AnsiColor {
  import ColorOutput.clr
  private val noopLogger = ProcessLogger(_ => ())
  private val clrEnabled = conf.colorEnabled
  private val separator = "=" * 80
  private val pass = st("OK")
  private val failed = st("FAILURE")
  private val warn = st("WARN")
  private val cliDownloadUrl = "https://s.apache.org/openwhisk-cli-download"
  private val dockerUrl = "https://docs.docker.com/install/"

  //Support for host.docker.internal is from 18.03
  private val supportedDockerVersion = DockerVersion("18.03")

  def run(): Unit = {
    println(separator)
    println("Running pre flight checks ...")
    println()
    println(s"Local Host Name: ${StandaloneDockerSupport.getLocalHostName()}")
    println(s"Local Internal Name: ${StandaloneDockerSupport.getLocalHostInternalName()}")
    println()
    checkForDocker()
    checkForWsk()
    checkForPorts()
    println()
    println(separator)
  }

  def checkForDocker() = {
    val dockerExistsResult = Try("docker --version".!(noopLogger)).getOrElse(-1)
    if (dockerExistsResult != 0) {
      println(s"$failed 'docker' cli not found.")
      println(s"\t Install docker from $dockerUrl")
    } else {
      val versionCmdOutput = dockerVersion
      println(s"$pass 'docker' cli found. $versionCmdOutput")

      //Wrap in try to discard any issue related to version parsing
      Try(checkDockerVersion(versionCmdOutput)).failed.foreach(t =>
        println(s"Error occurred while parsing version - ${t.getMessage}"))

      checkDockerIsRunning()
      //Other things we can possibly check for
      //1. add check for minimal supported docker version
      //2. should we also run `docker run hello-world` to see if we can execute docker run command
      //This command takes 2-4 secs. So running it by default for every run should be avoided
    }
  }

  private def checkDockerVersion(versionCmdOutput: String)(implicit ordering: Ordering[DockerVersion]): Unit = {
    val dv = DockerVersion.fromVersionCommand(versionCmdOutput)
    if (dv < supportedDockerVersion) {
      println(s"$failed 'docker' version $dv older than minimum supported $supportedDockerVersion")
    } else {
      println(s"$pass 'docker' version $dv is newer than minimum supported $supportedDockerVersion")
    }
  }

  private def dockerVersion = version("docker --version '{{.Client.Version}}'")

  private def version(cmd: String) = Try(cmd !! (noopLogger)).map(v => s"(${v.trim})").getOrElse("")

  private def checkDockerIsRunning(): Unit = {
    val dockerInfoResult = Try("docker info".!(noopLogger)).getOrElse(-1)
    if (dockerInfoResult != 0) {
      println(s"$failed 'docker' not found to be running. Failed to run 'docker info'")
    } else {
      println(s"$pass 'docker' is running.")
    }
  }

  def checkForWsk(): Unit = {
    val wskExistsResult = Try("wsk property get --cliversion".!(noopLogger)).getOrElse(-1)
    if (wskExistsResult != 0) {
      println(s"$failed 'wsk' cli not found.")
      println(s"\tDownload the cli from $cliDownloadUrl")
    } else {
      println(s"$pass 'wsk' cli found. $wskCliVersion")
      checkWskProps()
    }
  }

  def checkWskProps(): Unit = {
    val users = loadConfigOrThrow[Map[String, String]](loadConfig(), StandaloneConfigKeys.usersConfigKey)

    val configuredAuth = "wsk property get --auth".!!.trim
    val apihost = "wsk property get --apihost".!!.trim

    val requiredHostValue = s"http://${StandaloneDockerSupport.getLocalHostName()}:${conf.port()}"
    val externalHostValue = s"http://${StandaloneDockerSupport.getExternalHostName()}:${conf.port()}"

    //We can use -o option to get raw value. However as its a recent addition
    //using a lazy approach where we check if output ends with one of the configured auth keys or
    val matchedAuth = users.find { case (_, auth) => configuredAuth.endsWith(auth) }
    val hostMatched = apihost.endsWith(requiredHostValue)

    if (matchedAuth.isDefined && hostMatched) {
      println(s"$pass 'wsk' configured for namespace [${matchedAuth.get._1}].")
      println(s"$pass 'wsk' configured to connect to $requiredHostValue.")
    } else {
      val guestUser = users.find { case (ns, _) => ns == "guest" }
      //Only if guest user is found suggest wsk command to use that. Otherwise user is using a non default setup
      //which may not be used for wsk based access like for tests
      guestUser match {
        case Some((ns, guestAuth)) =>
          println(s"$warn Configure wsk via below command to connect to this server as [$ns]")
          println()
          println(clr(s"wsk property set --apihost '$externalHostValue' --auth '$guestAuth'", MAGENTA, clrEnabled))
        case None =>
      }
    }
  }

  def checkForPorts(): Unit = {
    if (isPortFree(conf.port())) {
      println(s"$pass Server port [${conf.port()}] is free")
    } else {
      println(s"$failed Server port [${conf.port()}] is not free. Standalone server cannot start")
    }

    if (conf.apiGw()) {
      val port = conf.apiGwPort()
      if (isPortFree(conf.apiGwPort())) {
        println(s"$pass Api gateway port [$port] is free")
      } else {
        println(s"$warn Api gateway port [$port] is not free. Api gateway cannot start")
      }
    }
  }

  private def wskCliVersion = version("wsk property get --cliversion -o raw")

  private def loadConfig(): Config = {
    conf.configFile.toOption match {
      case Some(f) =>
        require(f.exists(), s"Config file $f does not exist")
        ConfigFactory.parseFile(f)
      case None =>
        ConfigFactory.parseResources("standalone.conf")
    }
  }

  private def st(level: String) = {
    val maxLength = "FAILURE".length
    val (msg, code) = level match {
      case "OK"   => (StringUtils.center("OK", maxLength), GREEN)
      case "WARN" => (StringUtils.center("WARN", maxLength), MAGENTA)
      case _      => ("FAILURE", RED)
    }
    s"[${clr(msg, code, clrEnabled)}]"
  }
}
