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

package org.apache.openwhisk.core.database.memory

import java.time.Instant

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId, WhiskInstants}
import org.apache.openwhisk.core.database.{
  ActivationStore,
  ActivationStoreProvider,
  CacheChangeNotification,
  UserContext
}
import org.apache.openwhisk.core.entity.{ActivationId, DocInfo, EntityName, EntityPath, Subject, WhiskActivation}
import spray.json.{JsNumber, JsObject}

import scala.concurrent.Future

object NoopActivationStore extends ActivationStore with WhiskInstants {
  private val emptyInfo = DocInfo("foo")
  private val emptyCount = JsObject("activations" -> JsNumber(0))
  private val dummyActivation = WhiskActivation(
    EntityPath("testnamespace"),
    EntityName("activation"),
    Subject(),
    ActivationId.generate(),
    start = Instant.now.inMills,
    end = Instant.now.inMills)

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = Future.successful(emptyInfo)

  override def get(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId): Future[WhiskActivation] = {
    val activation = dummyActivation.copy(activationId = activationId)
    Future.successful(activation)
  }

  override def delete(activationId: ActivationId, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[Boolean] = Future.successful(true)

  override def countActivationsInNamespace(namespace: EntityPath,
                                           name: Option[EntityPath],
                                           skip: Int,
                                           since: Option[Instant],
                                           upto: Option[Instant],
                                           context: UserContext)(implicit transid: TransactionId): Future[JsObject] =
    Future.successful(emptyCount)

  override def listActivationsMatchingName(
    namespace: EntityPath,
    name: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean,
    since: Option[Instant],
    upto: Option[Instant],
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] =
    Future.successful(Right(List.empty))

  override def listActivationsInNamespace(
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    includeDocs: Boolean,
    since: Option[Instant],
    upto: Option[Instant],
    context: UserContext)(implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] =
    Future.successful(Right(List.empty))
}

object NoopActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, logging: Logging) =
    NoopActivationStore
}
