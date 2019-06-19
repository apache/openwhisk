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
import java.nio.charset.StandardCharsets.UTF_8

import akka.actor.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.stream.ActorMaterializer
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.common.{AkkaLogging, Config, Logging}
import org.apache.openwhisk.core.cli.WhiskAdmin
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.rogach.scallop.ScallopConf
import pureconfig.loadConfigOrThrow

import scala.concurrent.Await
import scala.concurrent.duration._

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner("OpenWhisk standalone launcher")

  val configFile = opt[File](descr = "application.conf which overrides the default standalone.conf")
  val manifest = opt[File](descr = "Manifest json defining the supported runtimes")
  val port = opt[Int](descr = "Server port", default = Some(8080))

  verify()
}

object Main extends SLF4JLogging {
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

  def main(args: Array[String]): Unit = {
    val conf = new Conf(args)
    initialize(conf)

    //Create actor system only after initializing the config
    implicit val actorSystem = ActorSystem("standalone-actor-system")
    implicit val materializer = ActorMaterializer.create(actorSystem)
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

    bootstrapUsers()
    startController()
  }

  def configureServerPort(conf: Conf) = {
    val port = conf.port()
    log.info(s"Starting OpenWhisk standalone on port $port")
    setConfigProp(WhiskConfig.servicePort, port.toString)
  }

  def initialize(conf: Conf): Unit = {
    configureServerPort(conf)
    configureOSSpecificOpts()
    initConfigLocation(conf)
    configureRuntimeManifest(conf)
    loadWhiskConfig()
  }

  def startController()(implicit actorSystem: ActorSystem, logger: Logging): Unit = {
    Controller.start(Array("standalone"))
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

  def configKey(k: String): String = Config.prefix + k.replace('-', '.')

  def loadWhiskConfig(): Unit = {
    val config = loadConfigOrThrow[Map[String, String]](ConfigKeys.whiskConfig)
    config.foreach { case (k, v) => setConfigProp(k, v) }
  }

  def configureRuntimeManifest(conf: Conf): Unit = {
    val manifest = conf.manifest.toOption match {
      case Some(file) =>
        FileUtils.readFileToString(file, UTF_8)
      case None =>
        defaultRuntime
    }
    setConfigProp(WhiskConfig.runtimesManifest, manifest)
  }

  def setConfigProp(key: String, value: String): Unit = {
    System.setProperty(configKey(key), value)
  }

  def bootstrapUsers()(implicit actorSystem: ActorSystem, materializer: ActorMaterializer, logging: Logging): Unit = {
    val users = loadConfigOrThrow[Map[String, String]]("whisk.users")

    users.foreach {
      case (name, key) =>
        val subject = name.replace('-', '.')
        val conf = new org.apache.openwhisk.core.cli.Conf(Seq("user", "create", "--auth", key, subject))
        val admin = WhiskAdmin(conf)
        Await.ready(admin.executeCommand(), 60.seconds)
        logging.info(this, s"Created user [$subject]")
    }
  }

  def configureOSSpecificOpts(): Unit = {
    if (SystemUtils.IS_OS_MAC) {
      System.setProperty("whisk.docker.container-factory.use-runc", "False")
      System.setProperty(
        "whisk.spi.ContainerFactoryProvider",
        "org.apache.openwhisk.core.containerpool.docker.DockerForMacContainerFactoryProvider")
    }
  }
}
