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

package org.apache.openwhisk.core.database

import java.time.Instant

import akka.actor.ActorSystem
import spray.json.JsObject
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.entity._

import scala.concurrent.Future
import scala.util.{Failure, Success}

class ArtifactActivationStore(actorSystem: ActorSystem, logging: Logging) extends ActivationStore {

  implicit val executionContext = actorSystem.dispatcher

  private val artifactStore: ArtifactStore[WhiskActivation] =
    WhiskActivationStore.datastore()(actorSystem, logging)

  def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {

    logging.debug(this, s"recording activation '${activation.activationId}'")

    val res = WhiskActivation.put(artifactStore, activation)

    res onComplete {
      case Success(id) => logging.debug(this, s"recorded activation")
      case Failure(t) =>
        logging.error(
          this,
          s"failed to record activation ${activation.activationId} with error ${t.getLocalizedMessage}")
    }

    res
  }

  def get(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId): Future[WhiskActivation] = {
    WhiskActivation.get(artifactStore, DocId(activationId.asString))
  }

  /**
   * Here there is added overhead of retrieving the specified activation before deleting it, so this method should not
   * be used in production or performance related code.
   */
  def delete(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = {
    WhiskActivation.get(artifactStore, DocId(activationId.asString)) flatMap { doc =>
      WhiskActivation.del(artifactStore, doc.docinfo)
    }
  }

  def countActivationsInNamespace(namespace: EntityPath,
                                  name: Option[EntityPath] = None,
                                  skip: Int,
                                  since: Option[Instant] = None,
                                  upto: Option[Instant] = None,
                                  context: UserContext)(implicit transid: TransactionId): Future[JsObject] = {
    WhiskActivation.countCollectionInNamespace(
      artifactStore,
      name.map(p => namespace.addPath(p)).getOrElse(namespace),
      skip,
      since,
      upto,
      StaleParameter.UpdateAfter,
      name.map(_ => WhiskActivation.filtersView).getOrElse(WhiskActivation.view))
  }

  def listActivationsMatchingName(
    namespace: EntityPath,
    name: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {
    WhiskActivation.listActivationsMatchingName(
      artifactStore,
      namespace,
      name,
      skip,
      limit,
      includeDocs,
      since,
      upto,
      StaleParameter.UpdateAfter)
  }

  def listActivationsInNamespace(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {
    WhiskActivation.listCollectionInNamespace(
      artifactStore,
      namespace,
      skip,
      limit,
      includeDocs,
      since,
      upto,
      StaleParameter.UpdateAfter)
  }

}

object ArtifactActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, logging: Logging) =
    new ArtifactActivationStore(actorSystem, logging)
}
