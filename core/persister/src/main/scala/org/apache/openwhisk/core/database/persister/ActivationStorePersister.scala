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
import com.google.common.base.Throwables
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{ActivationStore, DocumentConflictException, UserContext}
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
    store
      .store(act, userContext)(tid, None)
      .recoverWith {
        //TODO Metric - Conflict counts
        //Recover for conflict case as its possible in case of unclean shutdown persister need to process
        // same activation again
        //Checking the chain as exception may get wrapped
        case t if Throwables.getRootCause(t).isInstanceOf[DocumentConflictException] => Future.successful(Done)
      }
      .map(_ => Done)
  }
}
