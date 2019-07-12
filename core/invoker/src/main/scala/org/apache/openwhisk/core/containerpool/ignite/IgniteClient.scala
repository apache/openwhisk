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

package org.apache.openwhisk.core.containerpool.ignite

import java.io.FileNotFoundException
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.containerpool.docker.{DockerClientConfig, ProcessRunner}
import pureconfig.loadConfigOrThrow

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.Try

case class IgniteTimeoutConfig(create: Duration)

case class IgniteClientConfig(timeouts: IgniteTimeoutConfig)

class IgniteClient(config: IgniteClientConfig = loadConfigOrThrow[IgniteClientConfig](ConfigKeys.igniteClient),
                   dockerConfig: DockerClientConfig = loadConfigOrThrow[DockerClientConfig](ConfigKeys.dockerClient))(
  implicit ec: ExecutionContext,
  system: ActorSystem,
  log: Logging)
    extends ProcessRunner {

  protected val igniteCmd: Seq[String] = {
    val alternatives = List("/usr/bin/ignite", "/usr/local/bin/ignite")

    val dockerBin = Try {
      alternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate ignite binary (tried: ${alternatives.mkString(", ")}).")
    }
    Seq(dockerBin)
  }
}

trait IgniteApi {
  def inspectIPAddress(containerId: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress]

  def containerId(igniteId: IgniteId)(implicit transid: TransactionId): Future[ContainerId]

  def run(imageToUse: String, args: Seq[String])(implicit transid: TransactionId): Future[IgniteId]

  def importImage(publicImageName: String)(implicit transid: TransactionId): Future[Boolean]

  def rm(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit]
}
