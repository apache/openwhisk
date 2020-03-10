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

package org.apache.openwhisk.core.mesos

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel
import akka.event.Logging.InfoLevel
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.Timeout
import com.adobe.api.platform.runtime.mesos.Bridge
import com.adobe.api.platform.runtime.mesos.CommandDef
import com.adobe.api.platform.runtime.mesos.Constraint
import com.adobe.api.platform.runtime.mesos.DeleteTask
import com.adobe.api.platform.runtime.mesos.HealthCheckConfig
import com.adobe.api.platform.runtime.mesos.Host
import com.adobe.api.platform.runtime.mesos.Running
import com.adobe.api.platform.runtime.mesos.SubmitTask
import com.adobe.api.platform.runtime.mesos.TaskDef
import com.adobe.api.platform.runtime.mesos.User
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import spray.json._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.LoggingMarkers
import org.apache.openwhisk.common.MetricEmitter
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.containerpool.ContainerAddress
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.containerpool.logging.LogLine
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size._

/**
 * MesosTask implementation of Container.
 * Differences from DockerContainer include:
 * - does not launch container using docker cli, but rather a Mesos framework
 * - does not support pause/resume
 * - does not support log collection (currently), but does provide a message indicating logs can be viewed via Mesos UI
 * (external log collection and retrieval must be enabled via LogStore SPI to expose logs to wsk cli)
 */
case object Environment
case class CreateContainer(image: String, memory: String, cpuShare: String)

object MesosTask {

  val LAUNCH_CMD = "launch"
  val KILL_CMD = "kill"

  def create(mesosClientActor: ActorRef,
             mesosConfig: MesosConfig,
             taskIdGenerator: () => String,
             transid: TransactionId,
             image: String,
             userProvidedImage: Boolean = false,
             memory: ByteSize = 256.MB,
             cpuShares: Int = 0,
             environment: Map[String, String] = Map.empty,
             network: String = "bridge",
             dnsServers: Seq[String] = Seq.empty,
             name: Option[String] = None,
             parameters: Map[String, Set[String]] = Map.empty,
             constraints: Seq[Constraint] = Seq.empty)(implicit ec: ExecutionContext,
                                                       log: Logging,
                                                       as: ActorSystem): Future[Container] = {
    implicit val tid = transid

    val mesosCpuShares = cpuShares / 1024.0 // convert openwhisk (docker based) shares to mesos (cpu percentage)
    val mesosRam = memory.toMB.toInt

    val taskId = taskIdGenerator()
    val lowerNetwork = network.toLowerCase // match bridge+host without case, but retain case for user specified network
    val taskNetwork = lowerNetwork match {
      case "bridge" => Bridge
      case "host"   => Host
      case _        => User(network)
    }
    val dnsOrEmpty = if (dnsServers.nonEmpty) Map("dns" -> dnsServers.toSet) else Map.empty[String, Set[String]]

    //transform our config to mesos-actor config:
    val healthCheckConfig = mesosConfig.healthCheck.map(
      c =>
        HealthCheckConfig(
          c.portIndex,
          c.delay.toSeconds.toDouble,
          c.interval.toSeconds.toDouble,
          c.timeout.toSeconds.toDouble,
          c.gracePeriod.toSeconds.toDouble,
          c.maxConsecutiveFailures))
    //define task
    val task = new TaskDef(
      taskId,
      name.getOrElse(image), // task name either the indicated name, or else the image name
      image,
      mesosCpuShares,
      mesosRam,
      List(8080), // all action containers listen on 8080
      healthCheckConfig, // port at index 0 used for health
      false,
      taskNetwork,
      dnsOrEmpty ++ parameters,
      Some(CommandDef(environment)),
      constraints.toSet)

    val taskLaunchTimeout = Timeout(mesosConfig.timeouts.taskLaunch)
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_MESOS_CMD(LAUNCH_CMD),
      s"launching mesos task for taskid $taskId (image:$image, mem: $mesosRam, cpu: $mesosCpuShares) (timeout: $taskLaunchTimeout)",
      logLevel = InfoLevel)

    val launched: Future[Running] =
      mesosClientActor.ask(SubmitTask(task))(taskLaunchTimeout).mapTo[Running]

    launched
      .andThen {
        case Success(taskDetails) =>
          transid.finished(this, start, s"launched task ${taskId} at ${taskDetails.hostname}:${taskDetails
            .hostports(0)}", logLevel = InfoLevel)
        case Failure(ate: AskTimeoutException) =>
          transid.failed(this, start, s"task launch timed out ${ate.getMessage}", ErrorLevel)
          MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_MESOS_CMD_TIMEOUT(LAUNCH_CMD))
          //kill the task whose launch timed out
          destroy(mesosClientActor, mesosConfig, taskId)
        case Failure(t) =>
          //kill the task whose launch timed out
          destroy(mesosClientActor, mesosConfig, taskId)
          transid.failed(this, start, s"task launch failed ${t.getMessage}", ErrorLevel)
      }
      .map(taskDetails => {
        val taskHost = taskDetails.hostname
        val taskPort = taskDetails.hostports(0)
        val containerIp = new ContainerAddress(taskHost, taskPort)
        val containerId = new ContainerId(taskId);
        new MesosTask(containerId, containerIp, ec, log, as, taskId, mesosClientActor, mesosConfig)
      })

  }
  private def destroy(mesosClientActor: ActorRef, mesosConfig: MesosConfig, taskId: String)(
    implicit transid: TransactionId,
    logging: Logging,
    ec: ExecutionContext): Future[Unit] = {
    val taskDeleteTimeout = Timeout(mesosConfig.timeouts.taskDelete)

    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_MESOS_CMD(MesosTask.KILL_CMD),
      s"killing mesos taskid $taskId (timeout: ${taskDeleteTimeout})",
      logLevel = InfoLevel)

    mesosClientActor
      .ask(DeleteTask(taskId))(taskDeleteTimeout)
      .andThen {
        case Success(_) => transid.finished(this, start, logLevel = InfoLevel)
        case Failure(ate: AskTimeoutException) =>
          transid.failed(this, start, s"task destroy timed out ${ate.getMessage}", ErrorLevel)
          MetricEmitter.emitCounterMetric(LoggingMarkers.INVOKER_MESOS_CMD_TIMEOUT(MesosTask.KILL_CMD))
        case Failure(t) => transid.failed(this, start, s"task destroy failed ${t.getMessage}", ErrorLevel)
      }
      .map(_ => {})
  }
}

object JsonFormatters extends DefaultJsonProtocol {
  implicit val createContainerJson = jsonFormat3(CreateContainer)
}

class MesosTask(override protected val id: ContainerId,
                override protected[core] val addr: ContainerAddress,
                override protected implicit val ec: ExecutionContext,
                override protected implicit val logging: Logging,
                override protected val as: ActorSystem,
                taskId: String,
                mesosClientActor: ActorRef,
                mesosConfig: MesosConfig)
    extends Container {

  /** Stops the container from consuming CPU cycles. */
  override def suspend()(implicit transid: TransactionId): Future[Unit] = {
    super.suspend()
    // suspend not supported (just return result from super)
  }

  /** Dual of halt. */
  override def resume()(implicit transid: TransactionId): Future[Unit] = {
    super.resume()
    // resume not supported (just return result from super)
  }

  /** Completely destroys this instance of the container. */
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    MesosTask.destroy(mesosClientActor, mesosConfig, taskId)
  }

  /**
   * Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear.
   * For Mesos, this log message is static per container, just indicating that Mesos logs can be found via the Mesos UI.
   * To disable this message, and just store an static log message per activation, set
   *     org.apache.openwhisk.mesos.mesosLinkLogMessage=false
   */
  private val linkedLogMsg =
    s"Logs are not collected from Mesos containers currently. " +
      s"You can browse the logs for Mesos Task ID $taskId using the mesos UI at ${mesosConfig.masterPublicUrl
        .getOrElse(mesosConfig.masterUrl)}"
  private val noLinkLogMsg = "Log collection is not configured correctly, check with your service administrator."
  private val logMsg = if (mesosConfig.mesosLinkLogMessage) linkedLogMsg else noLinkLogMsg
  override def logs(limit: ByteSize, waitForSentinel: Boolean)(
    implicit transid: TransactionId): Source[ByteString, Any] =
    Source.single(ByteString(LogLine(logMsg, "stdout", Instant.now.toString).toJson.compactPrint))
}
