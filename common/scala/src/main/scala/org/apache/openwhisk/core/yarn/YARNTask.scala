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

package org.apache.openwhisk.core.yarn

import java.time.Instant

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.Source
import akka.util.{ByteString, Timeout}
import spray.json._
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.{Container, ContainerAddress, ContainerId}
import org.apache.openwhisk.core.containerpool.logging.LogLine
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.yarn.YARNComponentActor.RemoveContainer
import akka.pattern.ask
import scala.concurrent.duration._

import scala.concurrent.{ExecutionContext, Future}

/**
 * YARNTask implementation of Container.
 * Differences from DockerContainer include:
 * - does not launch container using docker cli, but rather the YARN framework
 * - does not support pause/resume
 * - does not support log collection (currently), but does provide a message indicating logs can be viewed via YARN UI
 * (external log collection and retrieval must be enabled via LogStore SPI to expose logs to wsk cli)
 */
class YARNTask(override protected val id: ContainerId,
               override protected[core] val addr: ContainerAddress,
               override protected val ec: ExecutionContext,
               override protected val logging: Logging,
               override protected val as: ActorSystem,
               val component_instance_name: String,
               imageName: ImageName,
               yarnConfig: YARNConfig,
               yarnComponentActor: ActorRef)
    extends Container {

  val containerRemoveTimeoutMS = 60000

  /** Stops the container from consuming CPU cycles. */
  override def suspend()(implicit transid: TransactionId): Future[Unit] = {
    // suspend not supported (just return result from super)
    super.suspend()
  }

  /** Dual of halt. */
  override def resume()(implicit transid: TransactionId): Future[Unit] = {
    // resume not supported
    Future.successful(())
  }

  /** Completely destroys this instance of the container. */
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {

    implicit val timeout: Timeout = Timeout(containerRemoveTimeoutMS.milliseconds)
    ask(yarnComponentActor, RemoveContainer(component_instance_name)).mapTo[Unit]
  }

  /**
   * Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear.
   * For YARN, this log message is static per container, just indicating that YARN logs can be found via the YARN UI.
   * To disable this message, and just store an static log message per activation, set
   *     whisk.yarn.yarnLinkLogMessage=false
   */
  private val linkedLogMsg =
    s"Logs are not collected from YARN containers currently. " +
      s"You can browse the logs for YARN Service ${yarnConfig.serviceName} using the yarn UI at ${yarnConfig.masterUrl}"
  private val noLinkLogMsg = "Log collection is not configured correctly, check with your service administrator."
  private val logMsg = if (yarnConfig.yarnLinkLogMessage) linkedLogMsg else noLinkLogMsg
  override def logs(limit: ByteSize, waitForSentinel: Boolean)(
    implicit transid: TransactionId): Source[ByteString, Any] =
    Source.single(ByteString(LogLine(logMsg, "stdout", Instant.now.toString).toJson.compactPrint))
}
