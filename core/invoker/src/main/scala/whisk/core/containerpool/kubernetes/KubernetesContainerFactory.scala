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
import pureconfig.loadConfigOrThrow

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerFactory
import whisk.core.containerpool.ContainerFactoryProvider
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest.ImageName
import whisk.core.entity.InvokerInstanceId
import whisk.core.{ConfigKeys, WhiskConfig}

class KubernetesContainerFactory(label: String, config: WhiskConfig)(implicit actorSystem: ActorSystem,
                                                                     ec: ExecutionContext,
                                                                     logging: Logging)
    extends ContainerFactory {

  implicit val kubernetes = initializeKubeClient()

  private def initializeKubeClient(): KubernetesClient = {
    val config = loadConfigOrThrow[KubernetesClientConfig](ConfigKeys.kubernetes)
    if (config.invokerAgent.enabled) {
      new KubernetesClientWithInvokerAgent(config)(ec)
    } else {
      new KubernetesClient(config)(ec)
    }
  }

  /** Perform cleanup on init */
  override def init(): Unit = cleanup()

  override def cleanup() = {
    logging.info(this, "Cleaning up function runtimes")
    val cleaning = kubernetes.rm("invoker", label, true)(TransactionId.invokerNanny)
    Await.ready(cleaning, 30.seconds)
  }

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    val image = if (userProvidedImage) {
      actionImage.publicImageName
    } else {
      actionImage.localImageName(config.runtimesRegistry)
    }

    KubernetesContainer.create(
      tid,
      name,
      image,
      userProvidedImage,
      memory,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      labels = Map("invoker" -> label))
  }
}

object KubernetesContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instance: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory =
    new KubernetesContainerFactory(s"invoker${instance.toInt}", config)(actorSystem, actorSystem.dispatcher, logging)
}
