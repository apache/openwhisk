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

package whisk.core.mesos

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.Timeout
import com.adobe.api.platform.runtime.mesos.Bridge
import com.adobe.api.platform.runtime.mesos.CommandDef
import com.adobe.api.platform.runtime.mesos.Constraint
import com.adobe.api.platform.runtime.mesos.DeleteTask
import com.adobe.api.platform.runtime.mesos.Host
import com.adobe.api.platform.runtime.mesos.Running
import com.adobe.api.platform.runtime.mesos.SubmitTask
import com.adobe.api.platform.runtime.mesos.TaskDef
import com.adobe.api.platform.runtime.mesos.User
import java.time.Instant
import org.apache.mesos.v1.Protos.TaskState
import org.apache.mesos.v1.Protos.TaskStatus
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import spray.json._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.logging.LogLine
import whisk.core.entity.ByteSize
import whisk.core.entity.size._

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
  val taskLaunchTimeout = Timeout(45 seconds)
  val taskDeleteTimeout = Timeout(30 seconds)

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

    log.info(this, s"creating task for image $image...")

    val mesosCpuShares = cpuShares / 1024.0 // convert openwhisk (docker based) shares to mesos (cpu percentage)
    val mesosRam = memory.toMB.toInt

    val taskId = taskIdGenerator()
    val lowerNetwork = network.toLowerCase // match bridge+host without case, but retain case for user specified network
    val taskNetwork = lowerNetwork match {
      case "bridge" => Bridge
      case "host"   => Host
      case _        => User(network)
    }
    val dnsOrEmpty = if (dnsServers.nonEmpty) Map("dns" -> dnsServers.toSet) else Map.empty

    val task = new TaskDef(
      taskId,
      name.getOrElse(image), // task name either the indicated name, or else the image name
      image,
      mesosCpuShares,
      mesosRam,
      List(8080), // all action containers listen on 8080
      Some(0), // port at index 0 used for health
      false,
      taskNetwork,
      dnsOrEmpty ++ parameters,
      Some(CommandDef(environment)),
      constraints.toSet)

    val launched: Future[Running] =
      mesosClientActor.ask(SubmitTask(task))(taskLaunchTimeout).mapTo[Running]

    launched.map(taskDetails => {
      val taskHost = taskDetails.hostname
      val taskPort = taskDetails.hostports(0)
      log.info(this, s"launched task with state ${taskDetails.taskStatus.getState} at ${taskHost}:${taskPort}")
      val containerIp = new ContainerAddress(taskHost, taskPort)
      val containerId = new ContainerId(taskId);
      new MesosTask(containerId, containerIp, ec, log, as, taskId, mesosClientActor, mesosConfig)
    })

  }

}

object JsonFormatters extends DefaultJsonProtocol {
  implicit val createContainerJson = jsonFormat3(CreateContainer)
}

class MesosTask(override protected val id: ContainerId,
                override protected val addr: ContainerAddress,
                override protected val ec: ExecutionContext,
                override protected val logging: Logging,
                override protected val as: ActorSystem,
                taskId: String,
                mesosClientActor: ActorRef,
                mesosConfig: MesosConfig)
    extends Container {

  /** Stops the container from consuming CPU cycles. */
  override def suspend()(implicit transid: TransactionId): Future[Unit] = {
    // suspend not supported
    Future.successful(Unit)
  }

  /** Dual of halt. */
  override def resume()(implicit transid: TransactionId): Future[Unit] = {
    // resume not supported
    Future.successful(Unit)
  }

  /** Completely destroys this instance of the container. */
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    mesosClientActor
      .ask(DeleteTask(taskId))(MesosTask.taskDeleteTimeout)
      .mapTo[TaskStatus]
      .map(taskStatus => {
        // verify that task ended in TASK_KILLED state (but don't fail if it didn't...)
        if (taskStatus.getState != TaskState.TASK_KILLED) {
          logging.error(this, s"task kill resulted in unexpected state ${taskStatus.getState}")
        } else {
          logging.info(this, s"task killed ended with state ${taskStatus.getState}")
        }
      })(ec)
  }

  /**
   * Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear.
   * For Mesos, this log message is static per container, just indicating that Mesos logs can be found via the Mesos UI.
   * To disable this message, and just store an static log message per activation, set
   *     whisk.mesos.mesosLinkLogMessage=false
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
