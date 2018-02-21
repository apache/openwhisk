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
import akka.stream.ActorMaterializer
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.entity._
import spray.json._

import whisk.core.containerpool.kubernetes.KubernetesContainer

import scala.concurrent.{ExecutionContext, Future}

/**
 * An implementation of a LogDriverForwarder for Kubernetes that delegates
 * to the InvokerAgent running on the container's worker node to forward
 * the logs appropriately to an external logging service.
 * Logs are not brought back to the invoker and thus are not available
 * except via the external logging service.
 */
class InvokerAgentLogStore(system: ActorSystem) extends LogDriverForwarderLogStore(system) {

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  override def forwardLogs(transid: TransactionId,
                           container: Container,
                           sizeLimit: ByteSize,
                           sentinelledLogs: Boolean,
                           additionalMetadata: Map[String, JsValue],
                           augmentedActivation: JsObject): Future[ActivationLogs] = {

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

object InvokerAgentLogStoreProvider extends LogStoreProvider {
  override def logStore(actorSystem: ActorSystem): LogStore = new InvokerAgentLogStore(actorSystem)
}
