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

package org.apache.openwhisk.core.containerpool.logging

import java.time.Instant

import akka.actor.ActorSystem
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.entity.{ActivationId, ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import org.apache.openwhisk.spi.Spi
import org.apache.openwhisk.core.database.UserContext

import scala.concurrent.Future

/**
 * Interface to gather logs after the activation, define their way of storage and fetch them from that storage upon
 * user request.
 *
 * Lifecycle wise, log-handling runs through two steps:
 * 1. Collecting logs after an activation has run to store them in the database.
 * 2. Fetching logs from the API to use them (as a user).
 *
 * Both of those lifecycle steps can independently implemented via {@code collectLogs} and {@code fetchLogs}
 * respectively.
 *
 * The implementation can choose to not fetch logs at all but use the underlying container orchestrator to handle
 * log-storage/forwarding (via {@code containerParameters}). In this case, {@code collectLogs} would be implemented
 * as a stub, only returning a line of log hinting that logs are stored elsewhere. {@code fetchLogs} though can
 * implement the API of the backend system to be able to still show the logs of a specific activation via the OpenWhisk
 * API.
 */
trait LogStore {

  /** Additional parameters to pass to container creation */
  def containerParameters: Map[String, Set[String]]

  /**
   * Determines if log collection is actually done by the implementation or done out of band by the
   * underlying container orchestrator
   */
  val logCollectionOutOfBand: Boolean = false

  /**
   * Collect logs after the activation has finished.
   *
   * This method is called after an activation has finished. The logs gathered here are stored along the activation
   * record in the database.
   *
   * @param transid transaction the activation ran in
   * @param user the user who ran the activation
   * @param activation the activation record
   * @param container container used by the activation
   * @param action action that was activated
   * @return logs for the given activation
   */
  def collectLogs(transid: TransactionId,
                  user: Identity,
                  activation: WhiskActivation,
                  container: Container,
                  action: ExecutableWhiskAction): Future[ActivationLogs]

  /**
   * Fetch relevant logs for the given activation from the store.
   *
   * This method is called when a user requests logs via the API.
   * The "logs" parameter is not empty if:
   * - the activation record exists
   * - the logs are stored embedded in the activation record
   *
   * @param namespace namespace to fetch the logs for
   * @param activationId activation to fetch the logs for
   * @param start activation start
   * @param end activation end
   * @return the relevant logs
   */
  def fetchLogs(namespace: String,
                activationId: ActivationId,
                start: Option[Instant],
                end: Option[Instant],
                logs: Option[ActivationLogs],
                content: UserContext): Future[ActivationLogs]
}

trait LogStoreProvider extends Spi {
  def instance(actorSystem: ActorSystem): LogStore
}

/** Indicates reading logs has failed either terminally or truncated logs */
case class LogCollectingException(partialLogs: ActivationLogs) extends Exception("Failed to read logs")
