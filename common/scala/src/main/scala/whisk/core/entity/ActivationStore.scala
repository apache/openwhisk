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

package whisk.core.entity

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json.JsObject
import whisk.common.{Logging, TransactionId}
import whisk.core.database.{ArtifactStore, CacheChangeNotification, StaleParameter}
import whisk.spi.Spi

import scala.concurrent.Future

trait ActivationStore {

  val artifactStore: ArtifactStore[WhiskActivation]

  def store(activation: WhiskActivation)(implicit transid: TransactionId,
                                         notifier: Option[CacheChangeNotification]): Future[DocInfo]

  def get(activationId: ActivationId)(implicit transid: TransactionId): Future[WhiskActivation]

  def delete(activationId: ActivationId)(implicit transid: TransactionId,
                                         notifier: Option[CacheChangeNotification]): Future[Boolean]

  def response(activationId: ActivationId)(implicit transid: TransactionId): Future[WhiskActivation]

  def countCollectionInNamespace(path: EntityPath,
                                 skip: Int,
                                 since: Option[Instant] = None,
                                 upto: Option[Instant] = None,
                                 stale: StaleParameter = StaleParameter.No,
                                 viewName: View)(implicit transid: TransactionId): Future[JsObject]

  def listActivationsMatchingName(namespace: EntityPath,
                                  path: EntityPath,
                                  skip: Int,
                                  limit: Int,
                                  includeDocs: Boolean = false,
                                  since: Option[Instant] = None,
                                  upto: Option[Instant] = None,
                                  stale: StaleParameter = StaleParameter.No)(
    implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]]

  def listCollectionInNamespace(path: EntityPath,
                                skip: Int,
                                limit: Int,
                                includeDocs: Boolean = false,
                                since: Option[Instant] = None,
                                upto: Option[Instant] = None,
                                stale: StaleParameter = StaleParameter.No)(
    implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]]
}

trait ActivationStoreProvider extends Spi {
  def activationStore(actorSystem: ActorSystem, actorMaterializer: ActorMaterializer, logging: Logging): ActivationStore
}
