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

package whisk.core.containerpool.docker

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.containerpool.BlackboxStartupError
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.WhiskContainerStartupError
import whisk.core.entity.ByteSize
import whisk.core.entity.size._

object DockerContainer {

  /**
   * Creates a container running on a docker daemon.
   *
   * @param transid transaction creating the container
   * @param image image to create the container from
   * @param userProvidedImage whether the image is provided by the user
   *     or is an OpenWhisk provided image
   * @param memory memorylimit of the container
   * @param cpuShares sharefactor for the container
   * @param environment environment variables to set on the container
   * @param network network to launch the container in
   * @param dnsServers list of dns servers to use in the container
   * @param name optional name for the container
   * @return a Future which either completes with a DockerContainer or one of two specific failures
   */
  def create(transid: TransactionId,
             image: String,
             userProvidedImage: Boolean = false,
             memory: ByteSize = 256.MB,
             cpuShares: Int = 0,
             environment: Map[String, String] = Map(),
             network: String = "bridge",
             dnsServers: Seq[String] = Seq(),
             name: Option[String] = None,
             dockerRunParameters: Map[String, Set[String]])(implicit docker: DockerApiWithFileAccess,
                                                            runc: RuncApi,
                                                            as: ActorSystem,
                                                            ec: ExecutionContext,
                                                            log: Logging): Future[DockerContainer] = {
    implicit val tid = transid

    val environmentArgs = environment.flatMap {
      case (key, value) => Seq("-e", s"$key=$value")
    }

    val params = dockerRunParameters.flatMap {
      case (key, valueList) => valueList.toList.flatMap(Seq(key, _))
    }

    val args = Seq(
      "--cpu-shares",
      cpuShares.toString,
      "--memory",
      s"${memory.toMB}m",
      "--memory-swap",
      s"${memory.toMB}m",
      "--network",
      network) ++
      environmentArgs ++
      name.map(n => Seq("--name", n)).getOrElse(Seq.empty) ++
      params
    val pulled = if (userProvidedImage) {
      docker.pull(image).recoverWith {
        case _ => Future.failed(BlackboxStartupError(s"Failed to pull container image '${image}'."))
      }
    } else Future.successful(())

    for {
      _ <- pulled
      id <- docker.run(image, args).recoverWith {
        case _ => Future.failed(WhiskContainerStartupError(s"Failed to run container with image '${image}'."))
      }
      ip <- docker.inspectIPAddress(id, network).recoverWith {
        // remove the container immediately if inspect failed as
        // we cannot recover that case automatically
        case _ =>
          docker.rm(id)
          Future.failed(WhiskContainerStartupError(s"Failed to obtain IP address of container '${id.asString}'."))
      }
    } yield new DockerContainer(id, ip)
  }
}

/**
 * Represents a container as run by docker.
 *
 * This class contains OpenWhisk specific behavior and as such does not necessarily
 * use docker commands to achieve the effects needed.
 *
 * @constructor
 * @param id the id of the container
 * @param addr the ip of the container
 */
class DockerContainer(protected val id: ContainerId, protected val addr: ContainerAddress)(
  implicit docker: DockerApiWithFileAccess,
  runc: RuncApi,
  as: ActorSystem,
  protected val ec: ExecutionContext,
  protected val logging: Logging)
    extends Container
    with DockerActionLogDriver {

  /** The last read-position in the log file */
  private var logFileOffset = 0L

  protected val logsRetryCount = 15
  protected val logsRetryWait = 100.millis

  def suspend()(implicit transid: TransactionId): Future[Unit] = runc.pause(id)
  def resume()(implicit transid: TransactionId): Future[Unit] = runc.resume(id)
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    docker.rm(id)
  }

  /**
   * Obtains the container's stdout and stderr output and converts it to our own JSON format.
   * At the moment, this is done by reading the internal Docker log file for the container.
   * Said file is written by Docker's JSON log driver and has a "well-known" location and name.
   *
   * For warm containers, the container log file already holds output from
   * previous activations that have to be skipped. For this reason, a starting position
   * is kept and updated upon each invocation.
   *
   * If asked, check for sentinel markers - but exclude the identified markers from
   * the result returned from this method.
   *
   * Only parses and returns as much logs as fit in the passed log limit.
   * Even if the log limit is exceeded, advance the starting position for the next invocation
   * behind the bytes most recently read - but don't actively read any more until sentinel
   * markers have been found.
   *
   * @param limit the limit to apply to the log size
   * @param waitForSentinel determines if the processor should wait for a sentinel to appear
   *
   * @return a vector of Strings with log lines in our own JSON format
   */
  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Future[Vector[String]] = {

    def readLogs(retries: Int): Future[Vector[String]] = {
      docker
        .rawContainerLogs(id, logFileOffset)
        .flatMap { rawLogBytes =>
          val rawLog =
            new String(rawLogBytes.array, rawLogBytes.arrayOffset, rawLogBytes.position, StandardCharsets.UTF_8)
          val (isComplete, isTruncated, formattedLogs) = processJsonDriverLogContents(rawLog, waitForSentinel, limit)

          if (retries > 0 && !isComplete && !isTruncated) {
            logging.info(this, s"log cursor advanced but missing sentinel, trying $retries more times")
            akka.pattern.after(logsRetryWait, as.scheduler)(readLogs(retries - 1))
          } else {
            logFileOffset += rawLogBytes.position - rawLogBytes.arrayOffset
            Future.successful(formattedLogs)
          }
        }
        .andThen {
          case Failure(e) =>
            logging.error(this, s"Failed to obtain logs of ${id.asString}: ${e.getClass} - ${e.getMessage}")
        }
    }

    readLogs(logsRetryCount)
  }

}
