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

package org.apache.openwhisk.core.containerpool.kubernetes

import org.apache.openwhisk.common.{Logging, TransactionId}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, MessageEntity, Uri}
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import pureconfig.loadConfigOrThrow
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.core.ConfigKeys

import collection.JavaConverters._
import scala.concurrent.{blocking, ExecutionContext, Future}

/**
 * An extended kubernetes client that works in tandem with an invokerAgent DaemonSet with
 * instances running on every worker node that runs user containers to provide
 * suspend/resume capability.
 */
class KubernetesClientWithInvokerAgent(config: KubernetesClientConfig =
                                         loadConfigOrThrow[KubernetesClientConfig](ConfigKeys.kubernetes))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends KubernetesClient(config)(executionContext)
    with KubernetesApiWithInvokerAgent {

  override def rm(key: String, value: String, ensureUnpaused: Boolean = false)(
    implicit transid: TransactionId): Future[Unit] = {
    if (ensureUnpaused) {
      // The caller can't guarantee that every container with the label key=value is already unpaused.
      // Therefore we must enumerate them and ensure they are unpaused before we attempt to delete them.
      Future {
        blocking {
          kubeRestClient
            .inNamespace(kubeRestClient.getNamespace)
            .pods()
            .withLabel(key, value)
            .list()
            .getItems
            .asScala
            .map { pod =>
              val container = toContainer(pod)
              container
                .resume()
                .recover { case _ => () } // Ignore errors; it is possible the container was not actually suspended.
                .map(_ => rm(container))
            }
        }
      }.flatMap(futures =>
        Future
          .sequence(futures)
          .map(_ => ()))
    } else {
      super.rm(key, value, ensureUnpaused)
    }
  }

  override def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    agentCommand("suspend", container)
      .map(_.discardEntityBytes())
  }

  override def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    agentCommand("resume", container)
      .map(_.discardEntityBytes())
  }

  override def agentCommand(command: String,
                            container: KubernetesContainer,
                            payload: Option[Map[String, JsValue]] = None): Future[HttpResponse] = {
    val uri = Uri()
      .withScheme("http")
      .withHost(container.workerIP)
      .withPort(config.invokerAgent.port)
      .withPath(Path / command / container.nativeContainerId)

    Marshal(payload).to[MessageEntity].flatMap { entity =>
      Http().singleRequest(HttpRequest(uri = uri, entity = entity))
    }
  }

  private def fieldsString(fields: Map[String, JsValue]) =
    fields
      .map {
        case (key, value) => s""""$key":${value.compactPrint}"""
      }
      .mkString(",")
}

trait KubernetesApiWithInvokerAgent extends KubernetesApi {

  /**
   * Request the invokerAgent running on the container's worker node to execute the given command
   * @param command The command verb to execute
   * @param container The container to which the command should be applied
   * @param payload The additional data needed to execute the command.
   * @return The HTTPResponse from the remote agent.
   */
  def agentCommand(command: String,
                   container: KubernetesContainer,
                   payload: Option[Map[String, JsValue]] = None): Future[HttpResponse]

}
