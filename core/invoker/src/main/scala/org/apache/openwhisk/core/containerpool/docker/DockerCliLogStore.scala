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

import java.time.Instant

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{AkkaLogging, Logging, TransactionId}
import org.apache.openwhisk.core.containerpool.Container.ACTIVATION_LOG_SENTINEL
import org.apache.openwhisk.core.containerpool.logging.{DockerToActivationLogStore, LogStore, LogStoreProvider}
import org.apache.openwhisk.core.containerpool.{Container, ContainerId}
import org.apache.openwhisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Docker based log store implementation which fetches logs via cli command.
 * This mode is inefficient and is only provided for running in developer modes
 */
object DockerCliLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = {
    //Logger is currently not passed implicitly to LogStoreProvider. So create one explicitly
    implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))
    new DockerCliLogStore(actorSystem)
  }
}

class DockerCliLogStore(system: ActorSystem)(implicit log: Logging) extends DockerToActivationLogStore(system) {
  private val client = new ExtendedDockerClient()(system.dispatcher)(log, system)
  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {
    client
      .collectLogs(container.containerId, activation.start, activation.end)(transid)
      .map(logs => ActivationLogs(logs.linesIterator.takeWhile(!_.contains(ACTIVATION_LOG_SENTINEL)).toVector))
  }
}

class ExtendedDockerClient(dockerHost: Option[String] = None)(executionContext: ExecutionContext)(implicit log: Logging,
                                                                                                  as: ActorSystem)
    extends DockerClientWithFileAccess(dockerHost)(executionContext)
    with DockerApiWithFileAccess
    with WindowsDockerClient {

  implicit private val ec: ExecutionContext = executionContext
  private val waitForLogs: FiniteDuration = 2.seconds
  private val logTimeSpanMargin = 1.second

  def collectLogs(id: ContainerId, since: Instant, untill: Instant)(implicit transid: TransactionId): Future[String] = {
    //Add a slight buffer to account for delay writes of logs
    val end = untill.plusSeconds(logTimeSpanMargin.toSeconds)
    runCmd(
      Seq(
        "logs",
        id.asString,
        "--since",
        since.getEpochSecond.toString,
        "--until",
        end.getEpochSecond.toString,
        "--timestamps"),
      waitForLogs)
  }
}
