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

import java.net.{ServerSocket, Socket}

import akka.Done
import akka.actor.{ActorSystem, CoordinatedShutdown}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.containerpool.docker.{DockerClient, DockerClientConfig, WindowsDockerClient}
import pureconfig.loadConfigOrThrow

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class StandaloneDockerSupport(docker: DockerClient)(implicit logging: Logging,
                                                    ec: ExecutionContext,
                                                    actorSystem: ActorSystem) {
  CoordinatedShutdown(actorSystem)
    .addTask(
      CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
      "cleanup containers launched for Standalone Server support") { () =>
      cleanup()
      Future.successful(Done)
    }

  def cleanup(): Unit = {
    implicit val transid = TransactionId(TransactionId.systemPrefix + "standalone")
    val cleaning =
      docker.ps(filters = Seq("name" -> StandaloneDockerSupport.prefix), all = true).flatMap { containers =>
        logging.info(this, s"removing ${containers.size} containers launched for Standalone server support.")
        val removals = containers.map { id =>
          docker.rm(id)
        }
        Future.sequence(removals)
      }
    Await.ready(cleaning, 30.seconds)
  }
}

object StandaloneDockerSupport {
  val prefix = "whisk-standalone-"
  val network = "bridge"

  def checkOrAllocatePort(preferredPort: Int): Int = {
    if (isPortFree(preferredPort)) preferredPort else freePort()
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally if (socket != null) socket.close()
  }

  def isPortFree(port: Int): Boolean = {
    Try(new Socket("localhost", port).close()).isFailure
  }

  def createRunCmd(name: String,
                   environment: Map[String, String] = Map.empty,
                   dockerRunParameters: Map[String, Set[String]] = Map.empty): Seq[String] = {
    val environmentArgs = environment.flatMap {
      case (key, value) => Seq("-e", s"$key=$value")
    }

    val params = dockerRunParameters.flatMap {
      case (key, valueList) => valueList.toList.flatMap(Seq(key, _))
    }

    Seq("--name", containerName(name), "--network", network) ++
      environmentArgs ++ params
  }

  def containerName(name: String) = {
    prefix + name
  }
}

class StandaloneDockerClient(implicit log: Logging, as: ActorSystem, ec: ExecutionContext)
    extends DockerClient()(ec)
    with WindowsDockerClient {
  override def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] =
    super.runCmd(args, timeout)

  val clientConfig: DockerClientConfig = loadConfigOrThrow[DockerClientConfig](ConfigKeys.dockerClient)
}

case class StandaloneDockerContainer(id: ContainerId, addr: ContainerAddress)
