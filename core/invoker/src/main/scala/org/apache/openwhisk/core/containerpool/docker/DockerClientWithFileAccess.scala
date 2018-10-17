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

package org.apache.openwhisk.core.containerpool.docker

import java.io.File
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.alpakka.file.scaladsl.FileTailSource
import akka.stream.scaladsl.{FileIO, Source => AkkaSource}
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.ContainerAddress

import scala.io.Source
import scala.concurrent.duration.FiniteDuration

class DockerClientWithFileAccess(dockerHost: Option[String] = None,
                                 containersDirectory: File = Paths.get("containers").toFile)(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends DockerClient(dockerHost)(executionContext)
    with DockerApiWithFileAccess {

  implicit private val ec = executionContext

  /**
   * Provides the home directory of the specified Docker container.
   *
   * Assumes that property "containersDirectory" holds the location of the
   * home directory of all Docker containers. Default: directory "containers"
   * in the current working directory.
   *
   * Does not verify that the returned directory actually exists.
   *
   * @param containerId Id of the desired Docker container
   * @return canonical location of the container's home directory
   */
  protected def containerDirectory(containerId: ContainerId) = {
    new File(containersDirectory, containerId.asString).getCanonicalFile()
  }

  /**
   * Provides the configuration file of the specified Docker container.
   *
   * Assumes that the file has the well-known location and name.
   *
   * Does not verify that the returned file actually exists.
   *
   * @param containerId Id of the desired Docker container
   * @return canonical location of the container's configuration file
   */
  protected def containerConfigFile(containerId: ContainerId) = {
    new File(containerDirectory(containerId), "config.v2.json").getCanonicalFile()
  }

  /**
   * Provides the log file of the specified Docker container written by
   * Docker's JSON log driver.
   *
   * Assumes that the file has the well-known location and name.
   *
   * Does not verify that the returned file actually exists.
   *
   * @param containerId Id of the desired Docker container
   * @return canonical location of the container's log file
   */
  protected def containerLogFile(containerId: ContainerId) = {
    new File(containerDirectory(containerId), s"${containerId.asString}-json.log").getCanonicalFile()
  }

  /**
   * Provides the contents of the specified Docker container's configuration
   * file as JSON object.
   *
   * @param configFile the container's configuration file in JSON format
   * @return contents of configuration file as JSON object
   */
  protected def configFileContents(configFile: File): Future[JsObject] = Future {
    blocking { // Needed due to synchronous file operations
      val source = Source.fromFile(configFile)
      val config = try source.mkString
      finally source.close()
      config.parseJson.asJsObject
    }
  }

  /**
   * Extracts the IP of the container from the local config file of the docker daemon.
   *
   * A container may have more than one network. The container has an
   * IP address in each of these networks such that the network name
   * is needed.
   *
   * @param id the id of the container to get the IP address from
   * @param network name of the network to get the IP address from
   * @return the ip address of the container
   */
  protected def ipAddressFromFile(id: ContainerId, network: String): Future[ContainerAddress] = {
    configFileContents(containerConfigFile(id)).map { json =>
      val networks = json.fields("NetworkSettings").asJsObject.fields("Networks").asJsObject
      val specifiedNetwork = networks.fields(network).asJsObject
      val ipAddr = specifiedNetwork.fields("IPAddress")
      ContainerAddress(ipAddr.convertTo[String])
    }
  }

  // See extended trait for description
  override def inspectIPAddress(id: ContainerId, network: String)(
    implicit transid: TransactionId): Future[ContainerAddress] = {
    ipAddressFromFile(id, network).recoverWith {
      case _ => super.inspectIPAddress(id, network)
    }
  }

  override def isOomKilled(id: ContainerId)(implicit transid: TransactionId): Future[Boolean] =
    configFileContents(containerConfigFile(id))
      .map(_.fields("State").asJsObject.fields("OOMKilled").convertTo[Boolean])
      .recover { case _ => false }

  private val readChunkSize = 8192 // bytes
  override def rawContainerLogs(containerId: ContainerId,
                                fromPos: Long,
                                pollInterval: Option[FiniteDuration]): AkkaSource[ByteString, Any] =
    try {
      // If there is no waiting interval, we can end the stream early by reading just what is there from file.
      pollInterval match {
        case Some(interval) => FileTailSource(containerLogFile(containerId).toPath, readChunkSize, fromPos, interval)
        case None           => FileIO.fromPath(containerLogFile(containerId).toPath, readChunkSize, fromPos)
      }
    } catch {
      case t: Throwable => AkkaSource.failed(t)
    }
}

trait DockerApiWithFileAccess extends DockerApi {

  /**
   * Reads logs from the container written json-log file and returns them
   * streamingly in bytes.
   *
   * @param containerId id of the container to get the logs for
   * @param fromPos position to start to read in the file
   * @param pollInterval interval to poll for changes of the file
   * @return a source emitting chunks read from the log-file
   */
  def rawContainerLogs(containerId: ContainerId,
                       fromPos: Long,
                       pollInterval: Option[FiniteDuration]): AkkaSource[ByteString, Any]
}
