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

import scala.concurrent.{ExecutionContext, Future}

/**
 * Docker based implementation of a LogStore.
 *
 * Relies on docker's implementation details with regards to the JSON log-driver. When using the JSON log-driver
 * docker writes stdout/stderr to a JSON formatted file which is read by this store. Logs are written in the
 * activation record itself and thus stored in CouchDB.
 */
class DockerLogStore(system: ActorSystem) extends LogStore {
  implicit val ec: ExecutionContext = system.dispatcher

  /* "json-file" is the log-driver that writes out to file */
  override val containerParameters = Map("--log-driver" -> Set("json-file"))

  /* As logs are already part of the activation record, just return that bit of it */
  override def fetchLogs(activation: WhiskActivation): Future[ActivationLogs] = Future.successful(activation.logs)

  override def collectLogs(transid: TransactionId,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {
    container.logs(action.limits.logs.asMegaBytes, action.exec.sentinelledLogs)(transid).map(ActivationLogs(_))
  }
}

object DockerLogStoreProvider extends LogStoreProvider {
  override def logStore(actorSystem: ActorSystem): LogStore = new DockerLogStore(actorSystem)
}
