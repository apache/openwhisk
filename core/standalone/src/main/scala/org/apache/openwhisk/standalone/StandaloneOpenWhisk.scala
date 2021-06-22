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
import org.apache.commons.io.{FileUtils, FilenameUtils, IOUtils}
import org.apache.openwhisk.common.TransactionId.systemPrefix
import org.apache.openwhisk.common.{AkkaLogging, Config, Logging, TransactionId}
import org.apache.openwhisk.core.cli.WhiskAdmin
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.standalone.ColorOutput.clr
import org.apache.openwhisk.standalone.StandaloneDockerSupport.checkOrAllocatePort
import org.rogach.scallop.ScallopConf
import pureconfig._
import pureconfig.generic.auto._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.io.AnsiColor
import scala.util.{Failure, Success, Try}
import KafkaLauncher._

class Conf(arguments: Seq[String]) extends ScallopConf(Conf.expandAllMode(arguments)) {
  import StandaloneOpenWhisk.preferredPgPort
  banner(StandaloneOpenWhisk.banner)
  footer("\nOpenWhisk standalone server")
  StandaloneOpenWhisk.gitInfo.foreach(g => version(s"Git Commit - ${g.commitId}"))

  this.printedName = "openwhisk"
  val configFile =
    opt[File](descr = "application.conf which overrides the default standalone.conf", validate = _.canRead)
  val manifest = opt[File](descr = "Manifest JSON defining the supported runtimes", validate = _.canRead)
  val port = opt[Int](descr = "Server port", default = Some(3233))

  val verbose = tally()
  val disableColorLogging = opt[Boolean](descr = "Disables colored logging", noshort = true)
  val apiGw = opt[Boolean](descr = "Enable API Gateway support", noshort = true)
  val couchdb = opt[Boolean](descr = "Enable CouchDB support", noshort = true)
  val clean = opt[Boolean](descr = "Clean any existing state like database", noshort = true)
  val devMode = opt[Boolean](
    descr = "Developer mode speeds up the startup by disabling preflight checks and avoiding explicit pulls.",
    noshort = true)
  val apiGwPort = opt[Int](descr = "API Gateway Port", default = Some(3234), noshort = true)
  val dataDir = opt[File](descr = "Directory used for storage", default = Some(StandaloneOpenWhisk.defaultWorkDir))

  val kafka = opt[Boolean](descr = "Enable embedded Kafka support", noshort = true)
  val kafkaUi = opt[Boolean](descr = "Enable Kafka UI", noshort = true)

  //The port option below express following usage. Note that "preferred"" port values are not configured as default
  // on purpose
  // - no config - Attempt to use default port. For e.g. 9092 for Kafka
  // - non config if default preferred port is busy then select a random port
  // - port config provided - Then this port would be used. If port is already busy
  //   then that service would not start. This is mostly meant to be used for test setups
  //   where test logic would determine a port and needs the service to start on that port only
  val kafkaPort = opt[Int](
    descr =
      s"Kafka port. If not specified then $preferredKafkaPort or some random free port (if $preferredKafkaPort is busy) would be used",
    noshort = true,
    required = false)

  val kafkaDockerPort = opt[Int](
    descr = s"Kafka port for use by docker based services. If not specified then $preferredKafkaDockerPort or some random free port " +
      s"(if $preferredKafkaDockerPort is busy) would be used",
    noshort = true,
    required = false)

  val zkPort = opt[Int](
    descr =
      s"Zookeeper port. If not specified then $preferredZkPort or some random free port (if $preferredZkPort is busy) would be used",
    noshort = true,
    required = false)

  val userEvents = opt[Boolean](descr = "Enable User Events along with Prometheus and Grafana", noshort = true)

  val all = opt[Boolean](
    descr = "Enables all the optional services supported by Standalone OpenWhisk like CouchDB, Kafka etc",
    noshort = true)

  val devKcf = opt[Boolean](descr = "Enables KubernetesContainerFactory for local development")

  val noUi = opt[Boolean](descr = "Disable Playground UI", noshort = true)

  val uiPort = opt[Int](
    descr = s"Playground UI server port. If not specified then $preferredPgPort or some random free port " +
      s"(if $StandaloneOpenWhisk is busy) would be used",
    noshort = true)

  val noBrowser = opt[Boolean](descr = "Disable Launching Browser", noshort = true)

  val devUserEventsPort = opt[Int](
    descr = "Specify the port for the user-event service. This mode can be used for local " +
      "development of user-event service by configuring Prometheus to connect to existing running service instance")

  val enableBootstrap = opt[Boolean](
    descr =
      "Enable bootstrap of default users and actions like those needed for Api Gateway or Playground UI. " +
        "By default bootstrap is done by default when using Memory store or default CouchDB support. " +
        "When using other stores enable this flag to get bootstrap done",
    noshort = true)

  mainOptions = Seq(manifest, configFile, apiGw, couchdb, userEvents, kafka, kafkaUi)

  verify()

  val colorEnabled = !disableColorLogging()

  def serverUrl: Uri = Uri(s"http://${StandaloneDockerSupport.getLocalHostName()}:${port()}")
}

object Conf {
  def expandAllMode(args: Seq[String]): Seq[String] = {
    if (args.contains("--all")) {
      val svcs = Seq("api-gw", "couchdb", "user-events", "kafka", "kafka-ui")
      val buf = args.toBuffer
      svcs.foreach { s =>
        val arg = "--" + s
        if (!buf.contains(arg)) {
          buf += arg
        }
      }
      buf.toList
    } else {
      args
    }
  }
}

case class GitInfo(commitId: String, commitTime: String)

object StandaloneConfigKeys {
  val usersConfigKey = "whisk.users"
  val redisConfigKey = "whisk.standalone.redis"
  val apiGwConfigKey = "whisk.standalone.api-gateway"
  val couchDBConfigKey = "whisk.standalone.couchdb"
  val userEventConfigKey = "whisk.standalone.user-events"
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
     |        "kind": "nodejs:14",
     |        "default": true,
     |        "image": {
     |          "prefix": "openwhisk",
     |          "name": "action-nodejs-v14",
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

  val preferredPgPort = 3232

  private val systemUser = "whisk.system"

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
    implicit val logger: Logging = createLogging(actorSystem, conf)
    implicit val ec: ExecutionContext = actorSystem.dispatcher

    val owPort = conf.port()
    val (dataDir, workDir) = initializeDirs(conf)
    val (dockerClient, dockerSupport) = prepareDocker(conf)

    val defaultSvcs = Seq(
      ServiceContainer(owPort, s"http://${StandaloneDockerSupport.getLocalHostName()}:$owPort", "Controller"))

    val (apiGwApiPort, apiGwSvcs) = if (conf.apiGw()) {
      startApiGateway(conf, dockerClient, dockerSupport)
    } else (-1, Seq.empty)

    val (kafkaDockerPort, kafkaSvcs) = if (conf.kafka() || conf.userEvents()) {
      startKafka(workDir, dockerClient, conf, conf.kafkaUi())
    } else (-1, Seq.empty)

    val couchSvcs = if (conf.couchdb()) Some(startCouchDb(dataDir, dockerClient)) else None
    val userEventSvcs =
      if (conf.userEvents() || conf.devUserEventsPort.isSupplied)
        startUserEvents(conf.port(), kafkaDockerPort, conf.devUserEventsPort.toOption, workDir, dataDir, dockerClient)
      else Seq.empty

    val pgLauncher = if (conf.noUi()) None else Some(createPgLauncher(owPort, conf))
    val pgSvc = pgLauncher.map(pg => Seq(pg.run())).getOrElse(Seq.empty)

    val svcs = Seq(defaultSvcs, apiGwSvcs, couchSvcs.toList, kafkaSvcs, userEventSvcs, pgSvc).flatten
    new ServiceInfoLogger(conf, svcs, dataDir).run()

    startServer(conf)
    new ServerStartupCheck(conf.serverUrl, "OpenWhisk").waitForServerToStart()

    if (canInstallUserAndActions(conf)) {
      if (conf.apiGw()) {
        installRouteMgmt(conf, workDir, apiGwApiPort)
      }
      pgLauncher.foreach(_.install())
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

  def startServer(conf: Conf)(implicit actorSystem: ActorSystem, logging: Logging): Unit = {
    if (canInstallUserAndActions(conf)) {
      bootstrapUsers()
    }
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
        val config = if (conf.devKcf()) {
          "standalone-kcf.conf"
        } else "standalone.conf"
        System.setProperty("config.resource", config)

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

  private def bootstrapUsers()(implicit actorSystem: ActorSystem, logging: Logging): Unit = {
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
    val apiGwHostv2 = s"http://${StandaloneDockerSupport.getLocalHostIp()}:$apiGwApiPort/v2"
    val authKey = systemAuthKey
    val installer = InstallRouteMgmt(workDir, authKey, conf.serverUrl, "/" + systemUser, Uri(apiGwHostv2), wskPath)
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
    ec: ExecutionContext): ServiceContainer = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "couchDB")
    val port = checkOrAllocatePort(5984)
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

  private def startKafka(workDir: File, dockerClient: StandaloneDockerClient, conf: Conf, kafkaUi: Boolean)(
    implicit logging: Logging,
    as: ActorSystem,
    ec: ExecutionContext): (Int, Seq[ServiceContainer]) = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "kafka")
    val kafkaPort = getPort(conf.kafkaPort.toOption, preferredKafkaPort)
    val kafkaDockerPort = getPort(conf.kafkaDockerPort.toOption, preferredKafkaDockerPort)
    val k = new KafkaLauncher(
      dockerClient,
      kafkaPort,
      kafkaDockerPort,
      getPort(conf.zkPort.toOption, preferredZkPort),
      workDir,
      kafkaUi)

    val f = k.run()
    val g = f.andThen {
      case Success(_) =>
        logging.info(
          this,
          s"Kafka started successfully at http://${StandaloneDockerSupport.getLocalHostName()}:$kafkaPort")
      case Failure(t) =>
        logging.error(this, "Error starting Kafka" + t)
    }
    val services = Await.result(g, 5.minutes)

    setConfigProp(WhiskConfig.kafkaHostList, s"localhost:$kafkaPort")
    setSysProp("whisk.spi.MessagingProvider", "org.apache.openwhisk.connector.kafka.KafkaMessagingProvider")
    setSysProp("whisk.spi.LoadBalancerProvider", "org.apache.openwhisk.standalone.KafkaAwareLeanBalancer")
    (kafkaDockerPort, services)
  }

  private def startUserEvents(owPort: Int,
                              kafkaDockerPort: Int,
                              existingUserEventSvcPort: Option[Int],
                              workDir: File,
                              dataDir: File,
                              dockerClient: StandaloneDockerClient)(implicit logging: Logging,
                                                                    as: ActorSystem,
                                                                    ec: ExecutionContext): Seq[ServiceContainer] = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "userevents")
    val k = new UserEventLauncher(dockerClient, owPort, kafkaDockerPort, existingUserEventSvcPort, workDir, dataDir)

    val f = k.run()
    val g = f.andThen {
      case Success(_) =>
        logging.info(this, s"User events started successfully")
      case Failure(t) =>
        logging.error(this, "Error starting Kafka" + t)
    }
    Await.result(g, 5.minutes)
  }

  private def getPort(configured: Option[Int], preferred: Int): Int = {
    configured.getOrElse(checkOrAllocatePort(preferred))
  }

  private def configureDevMode(): Unit = {
    setSysProp("whisk.docker.standalone.container-factory.pull-standard-images", "false")
  }

  private def createPgLauncher(owPort: Int,
                               conf: Conf)(implicit logging: Logging, as: ActorSystem, ec: ExecutionContext) = {
    implicit val tid: TransactionId = TransactionId(systemPrefix + "playground")
    val pgPort = getPort(conf.uiPort.toOption, preferredPgPort)
    new PlaygroundLauncher(
      StandaloneDockerSupport.getLocalHostName(),
      StandaloneDockerSupport.getExternalHostName(),
      owPort,
      pgPort,
      systemAuthKey,
      conf.devMode(),
      conf.noBrowser())
  }

  private def systemAuthKey: String = {
    getUsers().getOrElse(systemUser, throw new Exception(s"Did not found auth key for $systemUser"))
  }

  private def canInstallUserAndActions(conf: Conf)(implicit logging: Logging, actorSystem: ActorSystem): Boolean = {
    val config = actorSystem.settings.config
    val artifactStore = config.getString("whisk.spi.ArtifactStoreProvider")
    if (conf.couchdb() || artifactStore == "org.apache.openwhisk.core.database.memory.MemoryArtifactStoreProvider") {
      true
    } else if (conf.enableBootstrap()) {
      logging.info(this, "Bootstrap is enabled for external ArtifactStore")
      true
    } else {
      logging.info(
        this,
        s"Bootstrap is not enabled as connecting to external ArtifactStore. " +
          s"Start with ${conf.enableBootstrap.name} to bootstrap default users and action")
      false
    }
  }
}
