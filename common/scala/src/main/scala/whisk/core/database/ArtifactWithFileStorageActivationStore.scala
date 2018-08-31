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

package whisk.core.database

import akka.actor.ActorSystem
import akka.stream._

import spray.json._

import whisk.common.{Logging, TransactionId}
import whisk.core.entity._
import whisk.core.ConfigKeys

import scala.concurrent.Future

import pureconfig.loadConfigOrThrow

import java.nio.file.Paths

case class ArtifactWithFileStorageActivationStoreConfig(logFilePrefix: String,
                                                        logPath: String,
                                                        userIdField: String,
                                                        writeLogsToArtifact: Boolean,
                                                        writeResultToArtifact: Boolean)

class ArtifactWithFileStorageActivationStore(
  actorSystem: ActorSystem,
  actorMaterializer: ActorMaterializer,
  logging: Logging,
  config: ArtifactWithFileStorageActivationStoreConfig =
    loadConfigOrThrow[ArtifactWithFileStorageActivationStoreConfig](ConfigKeys.activationStoreWithFileStorage))
    extends ArtifactActivationStore(actorSystem, actorMaterializer, logging) {

  private val activationFileStorage =
    new ActivationFileStorage(config.logFilePrefix, Paths.get(config.logPath), actorMaterializer, logging)

  override def store(activation: WhiskActivation, context: UserContext)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] = {
    val additionalFields = Map(config.userIdField -> context.user.namespace.uuid.toJson)

    activationFileStorage.activationToFile(activation, context, additionalFields)

    if (config.writeResultToArtifact && config.writeLogsToArtifact) {
      super.store(activation, context)
    } else if (config.writeResultToArtifact) {
      super.store(activation.withoutLogs, context)
    } else if (config.writeLogsToArtifact) {
      super.store(activation.withoutResult, context)
    } else {
      super.store(activation.withoutLogsOrResult, context)
    }
  }

}

object ArtifactWithFileStorageActivationStoreProvider extends ActivationStoreProvider {
  override def instance(actorSystem: ActorSystem, actorMaterializer: ActorMaterializer, logging: Logging) =
    new ArtifactWithFileStorageActivationStore(actorSystem, actorMaterializer, logging)
}
