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
import scala.concurrent.Future
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.WhiskActivation

/** Docker based LogStore impl. Uses docker cli to collect logs directly from containers */
object DockerLogStore extends LogStore {

  override def containerParameters = Map[String, Set[String]]() //use defaults for docker logging of stdout/stderr

  override def logs(activation: WhiskActivation): Future[ActivationLogs] = {
    Future.successful(activation.logs)
  }

  override def collectLogs(transid: TransactionId, container: Container, action: ExecutableWhiskAction) = {
    implicit val tid = transid
    //get logs from `docker logs` command
    container.logs(action.limits.logs.asMegaBytes, action.exec.sentinelledLogs)
  }
}

object DockerLogStoreProvider extends LogStoreProvider {
  override def logStore(actorSystem: ActorSystem) = DockerLogStore
}
