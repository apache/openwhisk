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

package whisk.core.containerpool.kubernetes

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.containerpool.logging.{LogCollectingException, LogDriverLogStore, LogStore, LogStoreProvider}
import whisk.core.entity.{ActivationLogs, ExecutableWhiskAction, Identity, WhiskActivation}

import scala.concurrent.{ExecutionContext, Future}

/**
 * A LogStore implementation for Kubernetes that delegates all log processing to a remote invokerAgent that
 * runs on the worker node where the user container is executing.  The remote invokerAgent will read container logs,
 * enrich them with the activation-specific metadata it is provided, and consolidate them into a remote
 * combined log file that can be processed asynchronously by log forwarding services.
 *
 * Logs are never processed by the invoker itself and therefore are not stored in the activation record;
 * collectLogs will return an empty ActivationLogs.
 */
class KubernetesInvokerAgentLogStore(system: ActorSystem) extends LogDriverLogStore(system) {
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  override def collectLogs(transid: TransactionId,
                           user: Identity,
                           activation: WhiskActivation,
                           container: Container,
                           action: ExecutableWhiskAction): Future[ActivationLogs] = {

    val sizeLimit = action.limits.logs.asMegaBytes
    val sentinelledLogs = action.exec.sentinelledLogs

    // Add the userId field to every written record, so any background process can properly correlate.
    val userIdField = Map("namespaceId" -> user.namespace.uuid.toJson)

    val additionalMetadata = Map(
      "activationId" -> activation.activationId.asString.toJson,
      "action" -> action.fullyQualifiedName(false).asString.toJson) ++ userIdField

    val augmentedActivation = JsObject(activation.toJson.fields ++ userIdField)

    container match {
      case kc: KubernetesContainer => {
        kc.forwardLogs(sizeLimit, sentinelledLogs, additionalMetadata, augmentedActivation)(transid)
          .map { _ =>
            ActivationLogs()
          }
      }
      case _ => Future.failed(LogCollectingException(ActivationLogs()))
    }
  }
}

object KubernetesInvokerAgentLogStoreProvider extends LogStoreProvider {
  override def instance(actorSystem: ActorSystem): LogStore = new KubernetesInvokerAgentLogStore(actorSystem)
}
