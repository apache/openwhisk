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

import org.apache.commons.io.FileUtils
import org.apache.openwhisk.common.Config
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.core.controller.Controller
import org.rogach.scallop.ScallopConf
import pureconfig.loadConfigOrThrow

class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  banner("OpenWhisk standalone launcher")

  val configFile = opt[File](descr = "application.conf which overwrites the default whisk.conf")
  val manifest = opt[File](descr = "Manifest json defining the supported runtimes")

  verify()
}

object Main {
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
    initConfigLocation(conf)
    configureRuntimeManifest(conf)
    loadWhiskConfig()
    Controller.main(Array("standalone"))
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
    config.foreach { case (k, v) => System.setProperty(configKey(k), v) }
  }

  def configureRuntimeManifest(conf: Conf): Unit = {
    val manifest = conf.manifest.toOption match {
      case Some(file) =>
        FileUtils.readFileToString(file, UTF_8)
      case None =>
        defaultRuntime
    }
    System.setProperty(configKey(WhiskConfig.runtimesManifest), manifest)
  }
}
