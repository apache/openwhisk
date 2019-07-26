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

import java.io.{ByteArrayInputStream, File}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties

import akka.actor.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import org.apache.commons.io.{FileUtils, FilenameUtils, IOUtils}
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.common.TransactionId.systemPrefix
import org.apache.openwhisk.common.{AkkaLogging, Config, Logging, TransactionId}
import org.apache.openwhisk.core.cli.WhiskAdmin
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.standalone.ColorOutput.clr
import org.rogach.scallop.ScallopConf
import pureconfig.loadConfigOrThrow

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.io.AnsiColor
import scala.util.Try

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner(StandaloneOpenWhisk.banner)
  footer("\nOpenWhisk standalone server")
  StandaloneOpenWhisk.gitInfo.foreach(g => version(s"Git Commit - ${g.commitId}"))

  this.printedName = "openwhisk"
  val configFile =
    opt[File](descr = "application.conf which overrides the default standalone.conf", validate = _.canRead)
  val manifest = opt[File](descr = "Manifest json defining the supported runtimes", validate = _.canRead)
  val port = opt[Int](descr = "Server port", default = Some(3233))

  val verbose = tally()
  val disableColorLogging = opt[Boolean](descr = "Disables colored logging", noshort = true)
  val apiGw = opt[Boolean](descr = "Enable API Gateway support")
  val apiGwPort = opt[Int](descr = "Api Gateway Port", default = Some(3234))
  val dataDir = opt[File](descr = "Directory used for storage", default = Some(StandaloneOpenWhisk.defaultWorkDir))

  verify()

  val colorEnabled = !disableColorLogging()

  def serverUrl: Uri = Uri(s"http://localhost:${port()}")
}

case class GitInfo(commitId: String, commitTime: String)

object StandaloneConfigKeys {
  val usersConfigKey = "whisk.users"
  val redisConfigKey = "whisk.standalone.redis"
  val apiGwConfigKey = "whisk.standalone.api-gateway"
}

object StandaloneOpenWhisk extends SLF4JLogging {

  val banner =
    """
      |        ____      ___                   _    _ _     _     _
      |       /\   \    / _ \ _ __   ___ _ __ | |  | | |__ (_)___| | __
      |  /\  /__\   \  | | | | '_ \ / _ \ '_ \| |  | | '_ \| / __| |/ /
      | /  \____ \  /  | |_| | |_) |  __/ | | | |/\| | | | | \__ \   <
      | \   \  /  \/    \___/| .__/ \___|_| |_|__/\__|_| |_|_|___/_|\_\
      |  \___\/ tm           |_|
    """.stripMargin

  val defaultRuntime = """{
     |  "runtimes": {
     |    "nodejs": [
     |      {
     |        "kind": "nodejs:10",
     |        "default": true,
     |        "image": {
     |          "prefix": "openwhisk",
     |          "name": "action-nodejs-v10",
     |          "tag": "latest"
     |        },
     |        "deprecated": false,
     |        "attached": {
     |          "attachmentName": "codefile",
     |          "attachmentType": "text/plain"
     |        },
     |        "stemCells": [
     |          {
     |            "count": 1,
     |            "memory": "256 MB"
     |          }
     |        ]
     |      }
     |    ]
     |  }
     |}
     |""".stripMargin

  val gitInfo: Option[GitInfo] = loadGitInfo()

  val defaultWorkDir = new File(FilenameUtils.concat(FileUtils.getUserDirectoryPath, ".openwhisk/standalone"))

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    printBanner(conf)
    PreFlightChecks(conf).run()

    configureLogging(conf)
    initialize(conf)
    //Create actor system only after initializing the config
    implicit val actorSystem = ActorSystem("standalone-actor-system")
    implicit val materializer = ActorMaterializer.create(actorSystem)
    implicit val logger: Logging = createLogging(actorSystem, conf)

    startServer(conf)
  }

  def initialize(conf: Conf): Unit = {
    configureBuildInfo()
    configureServerPort(conf)
    initConfigLocation(conf)
    configureRuntimeManifest(conf)
    loadWhiskConfig()
  }

  def startServer(
    conf: Conf)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, logging: Logging): Unit = {
    bootstrapUsers()
    startController()

    if (conf.apiGw()) {
      implicit val ec: ExecutionContext = actorSystem.dispatcher
      startApiGateway(conf)
    }
  }

  private def configureServerPort(conf: Conf) = {
    val port = conf.port()
    log.info(s"Starting OpenWhisk standalone on port $port")
    System.setProperty(WhiskConfig.disableWhiskPropsFileRead, "true")
    setConfigProp(WhiskConfig.servicePort, port.toString)
    setConfigProp(WhiskConfig.wskApiPort, port.toString)
    setConfigProp(WhiskConfig.wskApiProtocol, "http")
    setConfigProp(WhiskConfig.wskApiHostname, localHostName)
  }

  private def initConfigLocation(conf: Conf): Unit = {
    conf.configFile.toOption match {
      case Some(f) =>
        require(f.exists(), s"Config file $f does not exist")
        System.setProperty("config.file", f.getAbsolutePath)
      case None =>
        System.setProperty("config.resource", "standalone.conf")
    }
  }

  private def configKey(k: String): String = Config.prefix + k.replace('-', '.')

  private def loadWhiskConfig(): Unit = {
    val config = loadConfigOrThrow[Map[String, String]](ConfigKeys.whiskConfig)
    config.foreach { case (k, v) => setConfigProp(k, v) }
  }

  private def configureRuntimeManifest(conf: Conf): Unit = {
    val manifest = conf.manifest.toOption match {
      case Some(file) =>
        FileUtils.readFileToString(file, UTF_8)
      case None => {
        //Fallback to a default runtime in case resource not found. Say while running from IDE
        Try(IOUtils.resourceToString("/runtimes.json", UTF_8)).getOrElse(defaultRuntime)
      }
    }
    setConfigProp(WhiskConfig.runtimesManifest, manifest)
  }

  private def setConfigProp(key: String, value: String): Unit = {
    setSysProp(configKey(key), value)
  }

  private def startController()(implicit actorSystem: ActorSystem, logger: Logging): Unit = {
    Controller.start(Array("standalone"))
  }

  private def bootstrapUsers()(implicit actorSystem: ActorSystem,
                               materializer: ActorMaterializer,
                               logging: Logging): Unit = {
    implicit val userTid: TransactionId = TransactionId(systemPrefix + "userBootstrap")
    getUsers().foreach {
      case (subject, key) =>
        val conf = new org.apache.openwhisk.core.cli.Conf(Seq("user", "create", "--auth", key, subject))
        val admin = WhiskAdmin(conf)
        Await.ready(admin.executeCommand(), 60.seconds)
        logging.info(this, s"Created user [$subject]")
    }
  }

  private def localHostName = {
    //For connecting back to controller on container host following name needs to be used
    // on Windows and Mac
    // https://docs.docker.com/docker-for-windows/networking/#use-cases-and-workarounds
    if (SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_WINDOWS)
      "host.docker.internal"
    else StandaloneDockerSupport.getHostIpLinux()
  }

  private def loadGitInfo() = {
    val info = loadPropResource("git.properties")
    for {
      commit <- info.get("git.commit.id.abbrev")
      time <- info.get("git.commit.time")
    } yield GitInfo(commit, time)
  }

  private def printBanner(conf: Conf) = {
    val bannerTxt = clr(banner, AnsiColor.CYAN, conf.colorEnabled)
    println(bannerTxt)
    gitInfo.foreach(g => println(s"Git Commit: ${g.commitId}, Build Date: ${g.commitTime}"))
  }

  private def configureBuildInfo(): Unit = {
    gitInfo.foreach { g =>
      setSysProp("whisk.info.build-no", g.commitId)
      setSysProp("whisk.info.date", g.commitTime)
    }
  }

  private def setSysProp(key: String, value: String): Unit = {
    Option(System.getProperty(key)) match {
      case Some(x) if x != value =>
        log.info(s"Founding existing value for system property '$key'- Going to set '$value' , found '$x'")
      case _ =>
        System.setProperty(key, value)
    }
  }

  private def loadPropResource(name: String): Map[String, String] = {
    Try {
      val propString = IOUtils.resourceToString("/" + name, UTF_8)
      val props = new Properties()
      props.load(new ByteArrayInputStream(propString.getBytes(UTF_8)))
      props.asScala.toMap
    }.getOrElse(Map.empty)
  }

  private def configureLogging(conf: Conf): Unit = {
    if (System.getProperty("logback.configurationFile") == null && !conf.disableColorLogging()) {
      LogbackConfigurator.configureLogbackFromResource("logback-standalone.xml")
    }
    LogbackConfigurator.initLogging(conf)
  }

  private def createLogging(actorSystem: ActorSystem, conf: Conf): Logging = {
    val adapter = akka.event.Logging.getLogger(actorSystem, this)
    if (conf.disableColorLogging())
      new AkkaLogging(adapter)
    else
      new ColoredAkkaLogging(adapter)
  }

  private def startApiGateway(conf: Conf)(implicit logging: Logging, as: ActorSystem, ec: ExecutionContext) = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "apiMgmt")

    // api port is the port used by rout management actions to configure the api gw upon wsk api commands
    // mgmt port is the port used by end user while making actual use of api gw
    val apiGwApiPort = StandaloneDockerSupport.checkOrAllocatePort(9000)
    val apiGwMgmtPort = conf.apiGwPort()
    val (dataDir, workDir) = initializeDirs(conf)
    new ServerStartupCheck(conf.serverUrl)
    installRouteMgmt(conf, workDir, apiGwApiPort)

    val dockerClient = new StandaloneDockerClient()
    val dockerSupport = new StandaloneDockerSupport(dockerClient)

    //Remove any existing launched containers
    dockerSupport.cleanup()
    val gw = new ApiGwLauncher(dockerClient, apiGwApiPort, apiGwMgmtPort, localHostName, conf.port())
    val f = gw.run()
    f.foreach(_ => logging.info(this, s"Api Gateway started successfully at http://localhost:$apiGwMgmtPort"))
    f.failed.foreach(t => logging.error(this, "Error starting Api Gateway" + t))
    Await.ready(f, 5.minutes)
  }

  private def installRouteMgmt(conf: Conf, workDir: File, apiGwApiPort: Int)(implicit logging: Logging): Unit = {
    val user = "whisk.system"
    val apiGwHostv2 = s"http://$localHostName:$apiGwApiPort/v2"
    val authKey = getUsers().getOrElse(
      user,
      throw new Exception(s"Did not found auth key for $user which is needed to install the api management package"))
    val installer = InstallRouteMgmt(workDir, authKey, conf.serverUrl, "/" + user, Uri(apiGwHostv2))
    installer.run()
  }

  private def initializeDirs(conf: Conf): (File, File) = {
    val baseDir = conf.dataDir()
    val thisServerDir = s"server-${conf.port()}"
    val dataDir = new File(baseDir, thisServerDir)
    FileUtils.forceMkdir(dataDir)
    log.info(s"Using [${dataDir.getAbsolutePath}] as data directory")

    val workDir = new File(dataDir, "tmp")
    FileUtils.deleteDirectory(workDir)
    FileUtils.forceMkdir(workDir)
    (dataDir, workDir)
  }

  private def getUsers(): Map[String, String] = {
    val m = loadConfigOrThrow[Map[String, String]](StandaloneConfigKeys.usersConfigKey)
    m.map { case (name, key) => (name.replace('-', '.'), key) }
  }
}
