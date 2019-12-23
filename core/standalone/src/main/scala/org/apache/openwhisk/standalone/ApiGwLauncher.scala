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

import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model.Uri
import akka.pattern.RetrySupport
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.standalone.StandaloneDockerSupport.{containerName, createRunCmd}
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class ApiGwLauncher(docker: StandaloneDockerClient, apiGwApiPort: Int, apiGwMgmtPort: Int, serverPort: Int)(
  implicit logging: Logging,
  ec: ExecutionContext,
  actorSystem: ActorSystem,
  tid: TransactionId)
    extends RetrySupport {
  private implicit val scd: Scheduler = actorSystem.scheduler
  case class RedisConfig(image: String)
  case class ApiGwConfig(image: String)
  private val redisConfig = loadConfigOrThrow[RedisConfig](StandaloneConfigKeys.redisConfigKey)
  private val apiGwConfig = loadConfigOrThrow[ApiGwConfig](StandaloneConfigKeys.apiGwConfigKey)

  def run(): Future[Seq[ServiceContainer]] = {
    for {
      (redis, redisSvcs) <- runRedis()
      _ <- waitForRedis(redis)
      (_, apiGwSvcs) <- runApiGateway(redis)
      _ <- waitForApiGw()
    } yield Seq(redisSvcs, apiGwSvcs).flatten
  }

  def runRedis(): Future[(StandaloneDockerContainer, Seq[ServiceContainer])] = {
    val defaultRedisPort = 6379
    val redisPort = StandaloneDockerSupport.checkOrAllocatePort(defaultRedisPort)
    logging.info(this, s"Starting Redis at $redisPort")

    val params = Map("-p" -> Set(s"$redisPort:6379"))
    val name = containerName("redis")
    val args = createRunCmd(name, dockerRunParameters = params)
    val f = docker.runDetached(redisConfig.image, args, shouldPull = true)
    val sc = ServiceContainer(redisPort, s"http://localhost:$redisPort", name)
    f.map(c => (c, Seq(sc)))
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

  def runApiGateway(redis: StandaloneDockerContainer): Future[(StandaloneDockerContainer, Seq[ServiceContainer])] = {
    val hostIp = StandaloneDockerSupport.getLocalHostIp()
    val env = Map(
      "BACKEND_HOST" -> s"http://$hostIp:$serverPort",
      "REDIS_HOST" -> redis.addr.host,
      "REDIS_PORT" -> "6379",
      //This is the name used to render the final url. So should be localhost
      //as that would be used by end user outside of docker
      "PUBLIC_MANAGEDURL_HOST" -> StandaloneDockerSupport.getLocalHostName(),
      "PUBLIC_MANAGEDURL_PORT" -> apiGwMgmtPort.toString)

    logging.info(this, s"Starting Api Gateway at api port: $apiGwApiPort, management port: $apiGwMgmtPort")
    val name = containerName("apigw")
    val params = Map("-p" -> Set(s"$apiGwApiPort:9000", s"$apiGwMgmtPort:8080"))
    val args = createRunCmd(name, env, params)

    //TODO ExecManifest is scoped to core. Ideally we would like to do
    // ExecManifest.ImageName(apiGwConfig.image).prefix.contains("openwhisk")
    val pull = apiGwConfig.image.startsWith("openwhisk")
    val f = docker.runDetached(apiGwConfig.image, args, pull)
    val sc = Seq(
      ServiceContainer(apiGwApiPort, s"http://localhost:$apiGwApiPort", s"$name, Api Gateway - Api Service "),
      ServiceContainer(apiGwMgmtPort, s"http://localhost:$apiGwMgmtPort", s"$name, Api Gateway - Management Service"))
    f.map(c => (c, sc))
  }

  def waitForApiGw(): Future[Unit] = {
    new ServerStartupCheck(
      Uri(s"http://${StandaloneDockerSupport.getLocalHostName()}:$apiGwApiPort/v1/apis"),
      "ApiGateway")
      .waitForServerToStart()
    Future.successful(())
  }
}
