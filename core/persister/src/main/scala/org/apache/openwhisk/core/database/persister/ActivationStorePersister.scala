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

package org.apache.openwhisk.core.database.persister
import akka.Done
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{ActivationStore, UserContext}
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entity.{
  BasicAuthenticationAuthKey,
  EntityName,
  Identity,
  Namespace,
  Secret,
  Subject,
  UUID,
  WhiskActivation
}

import scala.concurrent.{ExecutionContext, Future}

class ActivationStorePersister(store: ActivationStore)(implicit ec: ExecutionContext) extends ActivationPersister {

  /**
   * ActivationStore needs a user context. So create a dummy context here as its assumed that ActivationStore configured
   * to push to Kafka would have done the required filtering
   */
  private val userContext: UserContext = {
    val whiskSystem = "whisk.system"
    val uuid = UUID()
    val i = Identity(
      Subject(whiskSystem),
      Namespace(EntityName(whiskSystem), uuid),
      BasicAuthenticationAuthKey(uuid, Secret()),
      Set[Privilege]())
    UserContext(i)
  }

  override def persist(act: WhiskActivation)(implicit tid: TransactionId): Future[Done] = {
    //TODO Handle conflict if same activation is stored multiple
    //times due to some Kafka issue then error for conflict should be ignored
    store.store(act, userContext)(tid, None).map(_ => Done)
  }
}
