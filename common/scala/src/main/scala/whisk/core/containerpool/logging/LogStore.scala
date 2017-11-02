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

package whisk.core.containerpool.logging

import akka.actor.ActorSystem
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity.{ActivationLogs, ExecutableWhiskAction, WhiskActivation}
import whisk.spi.Spi

import scala.concurrent.Future

/**
 * Interface to gather logs after the activation, define their way of storage and fetch them from that storage upon
 * user request.
 */
trait LogStore {

  /** Additional parameters to pass to container creation */
  def containerParameters: Map[String, Set[String]]

  /**
   * Fetch relevant logs for the given activation from the store.
   *
   * @param activation activation to fetch the logs for
   * @return the relevant logs
   */
  def logs(activation: WhiskActivation): Future[ActivationLogs]

  /**
   * Collect logs after the activation has finished.
   *
   * These are the logs that get stored along the activation record in the database. These can be used as a placeholder
   * with a hint to the user, if logs are stored in an external system.
   *
   * @param transid transaction the activation ran in
   * @param container container used by the activation
   * @param action action that was activated
   * @return logs for the given activation
   */
  def collectLogs(transid: TransactionId, container: Container, action: ExecutableWhiskAction): Future[Vector[String]]
}

trait LogStoreProvider extends Spi {
  def logStore(actorSystem: ActorSystem): LogStore
}
