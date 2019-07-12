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
import akka.event.Logging.{ErrorLevel, InfoLevel}
import org.apache.openwhisk.common.{Logging, LoggingMarkers, MetricEmitter, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.docker.{
  DockerClient,
  DockerClientConfig,
  ProcessRunner,
  ProcessTimeoutException
}
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import pureconfig.loadConfigOrThrow

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class IgniteTimeoutConfig(create: Duration,
                               version: Duration,
                               inspect: Duration,
                               rm: Duration,
                               run: Duration,
                               ps: Duration)

case class IgniteClientConfig(timeouts: IgniteTimeoutConfig)

class IgniteClient(dockerClient: DockerClient,
                   config: IgniteClientConfig = loadConfigOrThrow[IgniteClientConfig](ConfigKeys.igniteClient),
                   dockerConfig: DockerClientConfig = loadConfigOrThrow[DockerClientConfig](ConfigKeys.dockerClient))(
  override implicit val executionContext: ExecutionContext,
  implicit val system: ActorSystem,
  implicit val log: Logging)
    extends IgniteApi
    with ProcessRunner {

  protected val igniteCmd: Seq[String] = {
    val alternatives = List("/usr/bin/ignite", "/usr/local/bin/ignite")

    val dockerBin = Try {
      alternatives.find(a => Files.isExecutable(Paths.get(a))).get
    } getOrElse {
      throw new FileNotFoundException(s"Couldn't locate ignite binary (tried: ${alternatives.mkString(", ")}).")
    }
    Seq(dockerBin, "-q")
  }

  // Invoke ignite CLI to determine client version.
  // If the ignite client version cannot be determined, an exception will be thrown and instance initialization will fail.
  // Rationale: if we cannot invoke `ignite version` successfully, it is unlikely subsequent `ignite` invocations will succeed.
  protected def getClientVersion(): String = {
    //TODO Ignite currently does not support formatting. So just get and log the verbatim version details
    val vf = executeProcess(igniteCmd ++ Seq("version"), config.timeouts.version)
      .andThen {
        case Success(version) => log.info(this, s"Detected ignite client version $version")
        case Failure(e) =>
          log.error(this, s"Failed to determine ignite client version: ${e.getClass} - ${e.getMessage}")
      }
    Await.result(vf, 2 * config.timeouts.version)
  }
  val clientVersion: String = getClientVersion()

  protected def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = igniteCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_IGNITE_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) => transid.finished(this, start)
      case Failure(pte: ProcessTimeoutException) =>
        transid.failed(this, start, pte.getMessage, ErrorLevel)
        MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_IGNITE_CMD_TIMEOUT(args.head))
      case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }

  override def inspectIPAddress(containerId: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress] =
    dockerClient.inspectIPAddress(containerId, "bridge")

  override def containerId(igniteId: IgniteId)(implicit transid: TransactionId): Future[ContainerId] = {
    //Each ignite vm would be backed by a Docker container whose name would be `ignite-<vm id>`
    //Use that to find the backing containerId
    dockerClient
      .runCmd(Seq("inspect", "--format", s"{{.Id}}", s"ignite-${igniteId.asString}"), config.timeouts.inspect)
      .flatMap {
        case "<no value>" => Future.failed(new NoSuchElementException)
        case stdout       => Future.successful(ContainerId(stdout))
      }
  }

  override def run(image: String, args: Seq[String])(implicit transid: TransactionId): Future[IgniteId] = {
    runCmd(Seq("run", image) ++ args, config.timeouts.run).map(IgniteId.apply)
  }

  private val importedImages = new TrieMap[String, Boolean]()
  private val importsInFlight = TrieMap[String, Future[Boolean]]()
  override def importImage(image: String)(implicit transid: TransactionId): Future[Boolean] = {
    //TODO Add support for latest
    if (importedImages.contains(image)) Future.successful(true)
    else {
      importsInFlight.getOrElseUpdate(
        image, {
          runCmd(Seq("image", "import", image), config.timeouts.create)
            .map { stdout =>
              log.info(this, s"Imported image $image - $stdout")
              true
            }
            .andThen {
              case _ =>
                importsInFlight.remove(image)
                importedImages.put(image, true)
            }
        })
    }
  }

  override def rm(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("vm", "rm", igniteId.asString), config.timeouts.rm).map(_ => ())

  override def stop(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("vm", "stop", igniteId.asString), config.timeouts.rm).map(_ => ())

  override def listRunningVMs()(implicit transid: TransactionId): Future[Seq[VMInfo]] = {
    //Each ignite vm has a backing container whose label is set to vm name and name to vm id
    val filter = "--format='{{.ID }}|{{ .Label \"ignite.name\" }}|{{.Names}}'"
    val cmd = Seq("ps", "--no-trunc", filter)
    runCmd(cmd, config.timeouts.ps).map(_.linesIterator.toSeq.map(VMInfo.apply))
  }
}

case class VMInfo(containerId: ContainerId, igniteId: IgniteId, name: String)

object VMInfo {
  def apply(value: String): VMInfo = {
    val Array(conatinerId, name, vmId) = value.split("|")
    val igniteId = vmId.split("-").last
    new VMInfo(ContainerId(conatinerId), IgniteId(igniteId), name)
  }
}

trait IgniteApi {
  protected implicit val executionContext: ExecutionContext

  def inspectIPAddress(containerId: ContainerId)(implicit transid: TransactionId): Future[ContainerAddress]

  def containerId(igniteId: IgniteId)(implicit transid: TransactionId): Future[ContainerId]

  def run(image: String, args: Seq[String])(implicit transid: TransactionId): Future[IgniteId]

  def importImage(image: String)(implicit transid: TransactionId): Future[Boolean]

  def rm(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit]

  def stop(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit]

  def listRunningVMs()(implicit transid: TransactionId): Future[Seq[VMInfo]]

  def stopAndRemove(igniteId: IgniteId)(implicit transid: TransactionId): Future[Unit] = {
    for {
      _ <- stop(igniteId)
      _ <- rm(igniteId)
    } yield Unit
  }
}
