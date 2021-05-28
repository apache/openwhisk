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

import java.nio.file.Paths

import akka.actor.ActorSystem
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.{DocInfo, _}
import pureconfig._
import pureconfig.generic.auto._
import spray.json._

import scala.concurrent.Future

case class ArtifactWithFileStorageActivationStoreConfig(logFilePrefix: String,
                                                        logPath: String,
                                                        userIdField: String,
                                                        writeResultToFile: Boolean)

class ArtifactWithFileStorageActivationStore(
  actorSystem: ActorSystem,
  logging: Logging,
  config: ArtifactWithFileStorageActivationStoreConfig =
    loadConfigOrThrow[ArtifactWithFileStorageActivationStoreConfig](ConfigKeys.activationStoreWithFileStorage))
    extends ArtifactActivationStore(actorSystem, logging) {

  private val activationFileStorage =
    new ActivationFileStorage(
      config.logFilePrefix,
      Paths.get(config.logPath),
      config.writeResultToFile,
      actorSystem,
      logging)

  def getLogFile = activationFileStorage.getLogFile

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    val additionalFieldsForLogs =
      Map(config.userIdField -> context.user.namespace.uuid.toJson, "namespace" -> context.user.namespace.name.toJson)
    val additionalFieldsForActivation = Map(config.userIdField -> context.user.namespace.uuid.toJson)

    activationFileStorage.activationToFileExtended(
      activation,
      context,
      additionalFieldsForLogs,
      additionalFieldsForActivation)
    super.store(activation, context)
  }

}

object ArtifactWithFileStorageActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, logging: Logging) =
    new ArtifactWithFileStorageActivationStore(actorSystem, logging)
}
