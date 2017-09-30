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
import whisk.spi.Spi

trait LogStore {

  def containerParameters: Map[String, Set[String]]
  def logs(activation: WhiskActivation): Future[ActivationLogs]
  def collectLogs(transid: TransactionId, container: Container, action: ExecutableWhiskAction): Future[Vector[String]]
}

trait LogStoreProvider extends Spi {
  def logStore(actorSystem: ActorSystem): LogStore
}
