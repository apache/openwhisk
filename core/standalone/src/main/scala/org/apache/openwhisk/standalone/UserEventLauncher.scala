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
import akka.stream.ActorMaterializer
import org.apache.commons.io.{FileUtils, IOUtils}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.standalone.StandaloneDockerSupport.{checkOrAllocatePort, containerName, createRunCmd}
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.{ExecutionContext, Future}

class UserEventLauncher(docker: StandaloneDockerClient,
                        owPort: Int,
                        kafkaDockerPort: Int,
                        existingUserEventSvcPort: Option[Int],
                        workDir: File,
                        dataDir: File)(implicit logging: Logging,
                                       ec: ExecutionContext,
                                       actorSystem: ActorSystem,
                                       materializer: ActorMaterializer,
                                       tid: TransactionId) {

  //owPort+1 is used by Api Gateway
  private val userEventPort = existingUserEventSvcPort.getOrElse(checkOrAllocatePort(owPort + 2))
  private val prometheusPort = checkOrAllocatePort(9090)
  private val grafanaPort = checkOrAllocatePort(3000)

  case class UserEventConfig(image: String, prometheusImage: String, grafanaImage: String)

  private val userEventConfig = loadConfigOrThrow[UserEventConfig](StandaloneConfigKeys.userEventConfigKey)

  private val hostIp = StandaloneDockerSupport.getLocalHostIp()

  def run(): Future[Seq[ServiceContainer]] = {
    for {
      userEvent <- runUserEvents()
      (promContainer, promSvc) <- runPrometheus()
      grafanaSvc <- runGrafana(promContainer)
    } yield {
      logging.info(this, "Enabled the user-event config")
      System.setProperty("whisk.user-events.enabled", "true")
      Seq(userEvent, promSvc, grafanaSvc)
    }
  }

  def runUserEvents(): Future[ServiceContainer] = {
    existingUserEventSvcPort match {
      case Some(_) =>
        logging.info(this, s"Connecting to pre existing user-event service at $userEventPort")
        Future.successful(ServiceContainer(userEventPort, s"http://localhost:$userEventPort", "Existing user-event"))
      case None =>
        val env = Map("KAFKA_HOSTS" -> s"$hostIp:$kafkaDockerPort")

        logging.info(this, s"Starting User Events: $userEventPort")
        val name = containerName("user-events")
        val params = Map("-p" -> Set(s"$userEventPort:9095"))
        val args = createRunCmd(name, env, params)

        val f = docker.runDetached(userEventConfig.image, args, true)
        f.map(_ => ServiceContainer(userEventPort, s"http://localhost:$userEventPort", name))
    }
  }

  def runPrometheus(): Future[(StandaloneDockerContainer, ServiceContainer)] = {
    logging.info(this, s"Starting Prometheus at $prometheusPort")
    val baseParams = Map("-p" -> Set(s"$prometheusPort:9090"))
    val promConfigDir = newDir(workDir, "prometheus")
    val promDataDir = newDir(dataDir, "prometheus")

    val configFile = new File(promConfigDir, "prometheus.yml")
    FileUtils.write(configFile, prometheusConfig, UTF_8)

    val volParams = Map(
      "-v" -> Set(s"${promDataDir.getAbsolutePath}:/prometheus", s"${promConfigDir.getAbsolutePath}:/etc/prometheus/"))
    val name = containerName("prometheus")
    val args = createRunCmd(name, Map.empty, baseParams ++ volParams)
    val f = docker.runDetached(userEventConfig.prometheusImage, args, shouldPull = true)
    val sc = ServiceContainer(prometheusPort, s"http://localhost:$prometheusPort", name)
    f.map(c => (c, sc))
  }

  def runGrafana(promContainer: StandaloneDockerContainer): Future[ServiceContainer] = {
    logging.info(this, s"Starting Grafana at $grafanaPort")
    val baseParams = Map("-p" -> Set(s"$grafanaPort:3000"))
    val grafanaConfigDir = newDir(workDir, "grafana")
    val grafanaDataDir = newDir(dataDir, "grafana")

    val promUrl = s"http://$hostIp:$prometheusPort"
    unzipGrafanaConfig(grafanaConfigDir, promUrl)

    val env = Map(
      "GF_PATHS_PROVISIONING" -> "/etc/grafana/provisioning",
      "GF_USERS_ALLOW_SIGN_UP" -> "false",
      "GF_AUTH_ANONYMOUS_ENABLED" -> "true",
      "GF_AUTH_ANONYMOUS_ORG_NAME" -> "Main Org.",
      "GF_AUTH_ANONYMOUS_ORG_ROLE" -> "Admin")

    val volParams = Map(
      "-v" -> Set(
        s"${grafanaDataDir.getAbsolutePath}:/var/lib/grafana",
        s"${grafanaConfigDir.getAbsolutePath}/provisioning/:/etc/grafana/provisioning/",
        s"${grafanaConfigDir.getAbsolutePath}/dashboards/:/var/lib/grafana/dashboards/"))
    val name = containerName("grafana")
    val args = createRunCmd(name, env, baseParams ++ volParams)
    val f = docker.runDetached(userEventConfig.grafanaImage, args, shouldPull = true)
    val sc = ServiceContainer(grafanaPort, s"http://localhost:$grafanaPort", name)
    f.map(_ => sc)
  }

  private def prometheusConfig = {
    val config = IOUtils.resourceToString("/prometheus.yml", UTF_8)
    val pattern = "'user-events:9095'"
    require(config.contains(pattern), s"Did not found expected pattern $pattern in prometheus config $config")

    val targets = s"'$hostIp:$userEventPort', '$hostIp:$owPort'"
    config.replace(pattern, targets)
  }

  private def unzipGrafanaConfig(configDir: File, promUrl: String): Unit = {
    val is = getClass.getResourceAsStream("/grafana-config.zip")
    if (is != null) {
      Unzip(is, configDir)
      val configFile = new File(configDir, "provisioning/datasources/datasource.yml")
      val config = FileUtils.readFileToString(configFile, UTF_8)
      val updatedConfig = config.replace("http://prometheus:9090", promUrl)
      FileUtils.write(configFile, updatedConfig, UTF_8)
    } else {
      logging.warn(
        this,
        "Did not found the grafana-config.zip in classpath. Make sure its packaged and present in classpath")
    }
  }

  private def newDir(baseDir: File, name: String) = {
    val dir = new File(baseDir, name)
    FileUtils.forceMkdir(dir)
    dir
  }
}
