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

package org.apache.openwhisk.core.invoker

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.Container
import org.apache.openwhisk.core.containerpool.logging.LogStore
import org.apache.openwhisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}
import org.apache.openwhisk.core.invoker.Invoker.LogsCollector

import scala.concurrent.Future

class LogStoreCollector(store: LogStore) extends LogsCollector {

  override def logsToBeCollected(action: ExecutableWhiskAction): Boolean =
    super.logsToBeCollected(action) && !store.logCollectionOutOfBand

  override def apply(transid: TransactionId,
                     user: Identity,
                     activation: WhiskActivation,
                     container: Container,
                     action: ExecutableWhiskAction): Future[ActivationLogs] =
    store.collectLogs(transid, user, activation, container, action)
}
