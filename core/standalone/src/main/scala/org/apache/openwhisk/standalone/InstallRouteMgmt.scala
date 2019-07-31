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

import akka.http.scaladsl.model.Uri
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.openwhisk.common.TransactionId.systemPrefix
import org.apache.openwhisk.common.{Logging, TransactionId}

import scala.sys.process.ProcessLogger
import scala.util.Try
import scala.sys.process._

case class InstallRouteMgmt(workDir: File,
                            authKey: String,
                            apiHost: Uri,
                            namespace: String,
                            gatewayUrl: Uri,
                            wsk: String)(implicit log: Logging) {
  case class Action(name: String, desc: String)
  private val noopLogger = ProcessLogger(_ => ())
  private implicit val tid: TransactionId = TransactionId(systemPrefix + "apiMgmt")
  val actionNames = Array(
    Action("createApi", "Create an API"),
    Action("deleteApi", "Delete the API"),
    Action("getApi", "Retrieve the specified API configuration (in JSON format)"))

  def run(): Unit = {
    require(wskExists, s"wsk command not found at $wsk. Route management actions cannot be installed")
    log.info(this, packageUpdateCmd.!!.trim)
    //TODO Optimize to ignore this if package already installed
    actionNames.foreach { action =>
      val name = action.name
      val actionZip = new File(workDir, s"$name.zip")
      FileUtils.copyURLToFile(IOUtils.resourceToURL(s"/$name.zip"), actionZip)
      val cmd = createActionUpdateCmd(action, name, actionZip)
      val result = cmd.!!.trim
      log.info(this, s"Installed $name - $result")
      FileUtils.deleteQuietly(actionZip)
    }
    //This log message is used by tests to confirm that actions are installed
    log.info(this, "Installed Route Management Actions")
  }

  private def createActionUpdateCmd(action: Action, name: String, actionZip: File) = {
    Seq(
      wsk,
      "--apihost",
      apiHost.toString(),
      "--auth",
      authKey,
      "action",
      "update",
      s"$namespace/apimgmt/$name",
      actionZip.getAbsolutePath,
      "-a",
      "description",
      action.desc,
      "--kind",
      "nodejs:default",
      "-a",
      "web-export",
      "true",
      "-a",
      "final",
      "true")
  }

  private def packageUpdateCmd = {
    Seq(
      wsk,
      "--apihost",
      apiHost.toString(),
      "--auth",
      authKey,
      "package",
      "update",
      s"$namespace/apimgmt",
      "--shared",
      "no",
      "-a",
      "description",
      "This package manages the gateway API configuration.",
      "-p",
      "gwUrlV2",
      gatewayUrl.toString())
  }

  def wskExists: Boolean = Try(s"$wsk property get --cliversion".!(noopLogger)).getOrElse(-1) == 0
}
