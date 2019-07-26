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

import akka.Done
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.Uri
import akka.pattern.RetrySupport
import org.apache.commons.lang3.SystemUtils
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.docker.BrokenDockerContainer
import org.apache.openwhisk.standalone.StandaloneDockerSupport.{containerName, createRunCmd}
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class ApiGwLauncher(
  docker: StandaloneDockerClient,
  apiGwApiPort: Int,
  apiGwMgmtPort: Int,
  localHostName: String,
  serverPort: Int)(implicit logging: Logging, ec: ExecutionContext, actorSystem: ActorSystem, tid: TransactionId)
    extends RetrySupport {
  private implicit val scd: Scheduler = actorSystem.scheduler
  case class RedisConfig(image: String)
  case class ApiGwConfig(image: String)
  private val redisConfig = loadConfigOrThrow[RedisConfig](StandaloneConfigKeys.redisConfigKey)
  private val apiGwConfig = loadConfigOrThrow[ApiGwConfig](StandaloneConfigKeys.apiGwConfigKey)

  def run(): Future[Done] = {
    for {
      redis <- runRedis()
      _ <- waitForRedis(redis)
      hostIp <- getHostIp
      _ <- runApiGateway(redis, hostIp)
      _ <- waitForApiGw()
    } yield Done
  }

  def runRedis(): Future[StandaloneDockerContainer] = {
    val defaultRedisPort = 6379
    val redisPort = StandaloneDockerSupport.checkOrAllocatePort(defaultRedisPort)
    logging.info(this, s"Starting Redis at $redisPort")

    val params = Map("-p" -> Set(s"$redisPort:6379"))
    val args = createRunCmd("redis", dockerRunParameters = params)
    runDetached(redisConfig.image, args, pull = true)
  }

  def waitForRedis(c: StandaloneDockerContainer): Future[Unit] = {
    retry(() => isRedisUp(c), 12, 5.seconds)
  }

  private def isRedisUp(c: StandaloneDockerContainer) = {
    val args = Seq(
      "run",
      "--rm",
      "--name",
      containerName("redis-test"),
      redisConfig.image,
      "redis-cli",
      "-h",
      c.addr.host,
      "-p",
      "6379",
      "ping")
    docker.runCmd(args, docker.clientConfig.timeouts.run).map(out => require(out.toLowerCase == "pong"))
  }

  def runApiGateway(redis: StandaloneDockerContainer, hostIp: String): Future[StandaloneDockerContainer] = {
    val env = Map(
      "BACKEND_HOST" -> s"http://$hostIp:$serverPort",
      "REDIS_HOST" -> redis.addr.host,
      "REDIS_PORT" -> "6379",
      //This is the name used to render the final url. So should be localhost
      //as that would be used by end user outside of docker
      "PUBLIC_MANAGEDURL_HOST" -> "localhost",
      "PUBLIC_MANAGEDURL_PORT" -> apiGwMgmtPort.toString)

    logging.info(this, s"Starting Api Gateway at api port: $apiGwApiPort, management port: $apiGwMgmtPort")
    val params = Map("-p" -> Set(s"$apiGwApiPort:9000", s"$apiGwMgmtPort:8080"))
    val args = createRunCmd("apigw", env, params)

    //TODO ExecManifest is scoped to core. Ideally we would like to do
    // ExecManifest.ImageName(apiGwConfig.image).prefix.contains("openwhisk")
    val pull = apiGwConfig.image.startsWith("openwhisk")
    runDetached(apiGwConfig.image, args, pull)
  }

  def waitForApiGw(): Future[Unit] = {
    new ServerStartupCheck(Uri(s"http://localhost:$apiGwApiPort/v1/apis")).waitForServerToStart()
    Future.successful(())
  }

  private def getHostIp(): Future[String] = {
    if (SystemUtils.IS_OS_LINUX) {
      //For linux case localHostName is resolved to ip at start
      Future.successful(localHostName)
    } else {
      getHostIpNonLinux()
    }
  }

  private def getHostIpNonLinux(): Future[String] = {
    //Gets the hostIp as names like host.docker.internal do not resolve for some reason in api ggateway
    //Based on https://unix.stackexchange.com/a/20793
    val args =
      Seq("run", "--rm", "--name", containerName("alpine"), "alpine", "getent", "hosts", localHostName)
    docker.runCmd(args, docker.clientConfig.timeouts.pull).map(out => out.split(" ").head.trim)
  }

  private def runDetached(image: String, args: Seq[String], pull: Boolean): Future[StandaloneDockerContainer] = {
    for {
      _ <- if (pull) docker.pull(image) else Future.successful(())
      id <- docker.run(image, args).recoverWith {
        case t @ BrokenDockerContainer(brokenId, _) =>
          // Remove the broken container - but don't wait or check for the result.
          // If the removal fails, there is nothing we could do to recover from the recovery.
          docker.rm(brokenId)
          Future.failed(t)
        case t => Future.failed(t)
      }
      ip <- docker.inspectIPAddress(id, StandaloneDockerSupport.network).recoverWith {
        // remove the container immediately if inspect failed as
        // we cannot recover that case automatically
        case e =>
          docker.rm(id)
          Future.failed(e)
      }
    } yield StandaloneDockerContainer(id, ip)
  }
}
