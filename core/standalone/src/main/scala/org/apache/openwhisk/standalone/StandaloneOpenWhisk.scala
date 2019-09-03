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
import scala.util.{Failure, Success, Try}

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
  val apiGw = opt[Boolean](descr = "Enable API Gateway support", noshort = true)
  val couchdb = opt[Boolean](descr = "Enable CouchDB support", noshort = true)
  val clean = opt[Boolean](descr = "Clean any existing state like database", noshort = true)
  val devMode = opt[Boolean](
    descr = "Developer mode speeds up the startup by disabling preflight checks and avoiding explicit pulls.",
    noshort = true)
  val apiGwPort = opt[Int](descr = "Api Gateway Port", default = Some(3234), noshort = true)
  val dataDir = opt[File](descr = "Directory used for storage", default = Some(StandaloneOpenWhisk.defaultWorkDir))

  verify()

  val colorEnabled = !disableColorLogging()

  def serverUrl: Uri = Uri(s"http://${StandaloneDockerSupport.getLocalHostName()}:${port()}")
}

case class GitInfo(commitId: String, commitTime: String)

object StandaloneConfigKeys {
  val usersConfigKey = "whisk.users"
  val redisConfigKey = "whisk.standalone.redis"
  val apiGwConfigKey = "whisk.standalone.api-gateway"
  val couchDBConfigKey = "whisk.standalone.couchdb"
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

  val wskPath = System.getProperty("whisk.standalone.wsk", "wsk")

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)

    printBanner(conf)
    if (!conf.devMode()) {
      PreFlightChecks(conf).run()
    }

    configureLogging(conf)
    initialize(conf)
    //Create actor system only after initializing the config
    implicit val actorSystem: ActorSystem = ActorSystem("standalone-actor-system")
    implicit val materializer: ActorMaterializer = ActorMaterializer.create(actorSystem)
    implicit val logger: Logging = createLogging(actorSystem, conf)
    implicit val ec: ExecutionContext = actorSystem.dispatcher

    val (dataDir, workDir) = initializeDirs(conf)
    val (dockerClient, dockerSupport) = prepareDocker(conf)

    val (apiGwApiPort, apiGwSvcs) = if (conf.apiGw()) {
      startApiGateway(conf, dockerClient, dockerSupport)
    } else (-1, Seq.empty)

    val couchSvcs = if (conf.couchdb()) Some(startCouchDb(dataDir, dockerClient)) else None
    val svcs = Seq(apiGwSvcs, couchSvcs.toList).flatten
    if (svcs.nonEmpty) {
      new ServiceInfoLogger(conf, svcs, dataDir).run()
    }

    startServer(conf)
    new ServerStartupCheck(conf.serverUrl, "OpenWhisk").waitForServerToStart()

    if (conf.apiGw()) {
      installRouteMgmt(conf, workDir, apiGwApiPort)
    }
  }

  def initialize(conf: Conf): Unit = {
    configureBuildInfo()
    configureServerPort(conf)
    configureOSSpecificOpts()
    initConfigLocation(conf)
    configureRuntimeManifest(conf)
    loadWhiskConfig()

    if (conf.devMode()) {
      configureDevMode()
    }
  }

  def startServer(
    conf: Conf)(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, logging: Logging): Unit = {
    bootstrapUsers()
    startController()
  }

  private def configureServerPort(conf: Conf) = {
    val port = conf.port()
    log.info(s"Starting OpenWhisk standalone on port $port")
    System.setProperty(WhiskConfig.disableWhiskPropsFileRead, "true")
    setConfigProp(WhiskConfig.servicePort, port.toString)
    setConfigProp(WhiskConfig.wskApiPort, port.toString)
    setConfigProp(WhiskConfig.wskApiProtocol, "http")

    //Using hostInternalName instead of getLocalHostIp as using docker alpine way to
    //determine the ip is seen to be failing with older version of Docker
    //So to keep main flow which does not use api gw working fine play safe
    setConfigProp(WhiskConfig.wskApiHostname, StandaloneDockerSupport.getLocalHostInternalName())
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

  private def configureOSSpecificOpts(): Unit = {
    //Set the interface based on OS
    setSysProp("whisk.controller.interface", StandaloneDockerSupport.getLocalHostName())
  }

  private def loadGitInfo() = {
    val info = loadPropResource("git.properties")
    for {
      commit <- info.get("git.commit.id.abbrev")
      time <- info.get("git.commit.time")
    } yield GitInfo(commit, time)
  }

  private def printBanner(conf: Conf): Unit = {
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

  private def prepareDocker(conf: Conf)(implicit logging: Logging,
                                        as: ActorSystem,
                                        ec: ExecutionContext): (StandaloneDockerClient, StandaloneDockerSupport) = {
    //In dev mode disable the pull
    val pullDisabled = conf.devMode()
    val dockerClient = new StandaloneDockerClient(pullDisabled)
    val dockerSupport = new StandaloneDockerSupport(dockerClient)

    //Remove any existing launched containers
    dockerSupport.cleanup()
    (dockerClient, dockerSupport)
  }

  private def startApiGateway(conf: Conf, dockerClient: StandaloneDockerClient, dockerSupport: StandaloneDockerSupport)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext): (Int, Seq[ServiceContainer]) = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "apiMgmt")

    // api port is the port used by rout management actions to configure the api gw upon wsk api commands
    // mgmt port is the port used by end user while making actual use of api gw
    val apiGwApiPort = StandaloneDockerSupport.checkOrAllocatePort(9000)
    val apiGwMgmtPort = conf.apiGwPort()

    val gw = new ApiGwLauncher(dockerClient, apiGwApiPort, apiGwMgmtPort, conf.port())
    val f = gw.run()
    val g = f.andThen {
      case Success(_) =>
        logging.info(
          this,
          s"Api Gateway started successfully at http://${StandaloneDockerSupport.getLocalHostName()}:$apiGwMgmtPort")
      case Failure(t) =>
        logging.error(this, "Error starting Api Gateway" + t)
    }
    val services = Await.result(g, 5.minutes)
    (apiGwApiPort, services)
  }

  private def installRouteMgmt(conf: Conf, workDir: File, apiGwApiPort: Int)(implicit logging: Logging): Unit = {
    val user = "whisk.system"
    val apiGwHostv2 = s"http://${StandaloneDockerSupport.getLocalHostIp()}:$apiGwApiPort/v2"
    val authKey = getUsers().getOrElse(
      user,
      throw new Exception(s"Did not found auth key for $user which is needed to install the api management package"))
    val installer = InstallRouteMgmt(workDir, authKey, conf.serverUrl, "/" + user, Uri(apiGwHostv2), wskPath)
    installer.run()
  }

  private def initializeDirs(conf: Conf)(implicit logging: Logging): (File, File) = {
    val baseDir = conf.dataDir()
    val thisServerDir = s"server-${conf.port()}"
    val dataDir = new File(baseDir, thisServerDir)
    if (conf.clean() && dataDir.exists()) {
      FileUtils.deleteDirectory(dataDir)
      logging.info(this, s"Cleaned existing directory ${dataDir.getAbsolutePath}")
    }
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

  private def startCouchDb(dataDir: File, dockerClient: StandaloneDockerClient)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext,
    materializer: ActorMaterializer): ServiceContainer = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "couchDB")
    val port = StandaloneDockerSupport.checkOrAllocatePort(5984)
    val dbDataDir = new File(dataDir, "couchdb")
    FileUtils.forceMkdir(dbDataDir)
    val db = new CouchDBLauncher(dockerClient, port, dbDataDir)
    val f = db.run()
    val g = f.andThen {
      case Success(_) =>
      case Failure(t) =>
        logging.error(this, "Error starting CouchDB" + t)
    }
    Await.result(g, 5.minutes)
  }

  private def configureDevMode(): Unit = {
    setSysProp("whisk.docker.standalone.container-factory.pull-standard-images", "false")
  }
}
