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
import spray.json.JsObject
import whisk.core.entity._
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future

/**
 * A LogDriverLogStore skeleton that forwards enriched logs to an external store
 * at the end of each activation execution.  Forwarding may either be done in the invoker,
 * in which case the logs may also be returned from collectLogs, or by an external agent,
 * in which case collectLogs will return an empty ActivationLogs instance.
 */
abstract class LogDriverForwarderLogStore(actorSystem: ActorSystem) extends LogDriverLogStore(actorSystem) {

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    val sizeLimit = action.limits.logs.asMegaBytes
    val sentinelledLogs = action.exec.sentinelledLogs

    // Add the userId field to every written record, so any background process can properly correlate.
    val userIdField = Map("namespaceId" -> user.authkey.uuid.toJson)

    val additionalMetadata = Map(
      "activationId" -> activation.activationId.asString.toJson,
      "action" -> action.fullyQualifiedName(false).asString.toJson) ++ userIdField

    val augmentedActivation = JsObject(activation.toJson.fields ++ userIdField)

    forwardLogs(transid, container, sizeLimit, sentinelledLogs, additionalMetadata, augmentedActivation)
  }

  def forwardLogs(transId: TransactionId,
                  container: Container,
                  sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject): Future[ActivationLogs]
}
