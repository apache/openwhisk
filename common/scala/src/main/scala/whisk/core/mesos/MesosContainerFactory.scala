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

package whisk.core.mesos

import akka.actor.ActorSystem
import akka.pattern.ask
import com.adobe.api.platform.runtime.mesos.MesosClient
import com.adobe.api.platform.runtime.mesos.Subscribe
import com.adobe.api.platform.runtime.mesos.SubscribeComplete
import com.adobe.api.platform.runtime.mesos.Teardown
import pureconfig.loadConfigOrThrow
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.ConfigKeys
import whisk.core.WhiskConfig
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerFactory
import whisk.core.containerpool.ContainerFactoryProvider
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import whisk.core.entity.UUID

case class MesosConfig(masterUrl: String,
                       masterPublicUrl: Option[String] = None,
                       role: String = "*",
                       failoverTimeoutSeconds: FiniteDuration = 0.seconds)

class MesosContainerFactory(config: WhiskConfig,
                            actorSystem: ActorSystem,
                            logging: Logging,
                            parameters: Map[String, Set[String]],
                            mesosConfig: MesosConfig = loadConfigOrThrow[MesosConfig](ConfigKeys.mesos))
    extends ContainerFactory {

  val subscribeTimeout = 30.seconds

  //init mesos framework:
  implicit val as: ActorSystem = actorSystem
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  //mesos master url to subscribe the framework to
  val mesosMaster = mesosConfig.masterUrl
  //public mesos url where developers can browse logs (till there is way to delegate log retrieval to an external system)
  val mesosMasterPublic = mesosConfig.masterPublicUrl

  logging.info(this, s"subscribing to mesos master at ${mesosMaster}")

  val mesosClientActor = actorSystem.actorOf(
    MesosClient
      .props(
        "whisk-containerfactory-" + UUID(),
        "whisk-containerfactory-framework",
        mesosMaster.toString(),
        mesosConfig.role,
        mesosConfig.failoverTimeoutSeconds))

  //subscribe mesos actor to mesos event stream
  //TODO: handle subscribe failure
  mesosClientActor
    .ask(Subscribe)(subscribeTimeout)
    .mapTo[SubscribeComplete]
    .onComplete(complete => {
      logging.info(this, "subscribe completed successfully...")
    })

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    implicit val transid = tid
    val image = if (userProvidedImage) {
      actionImage.publicImageName
    } else {
      actionImage.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
    }

    logging.info(this, s"using Mesos to create a container with image ${image}...")
    MesosTask.create(
      mesosClientActor,
      mesosMasterPublic.getOrElse(mesosMaster),
      tid,
      image = image,
      userProvidedImage = userProvidedImage,
      memory = memory,
      cpuShares = config.invokerCoreShare.toInt,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      network = config.invokerContainerNetwork,
      dnsServers = config.invokerContainerDns,
      name = Some(name),
      parameters)
  }

  override def init(): Unit = Unit

  /** cleanup any remaining Containers; should block until complete; should ONLY be run at shutdown */
  override def cleanup(): Unit = {
    val complete: Future[Any] = mesosClientActor.ask(Teardown)(20.seconds)
    Await.result(complete, 25.seconds)
    logging.info(this, "cleanup completed!")
  }
}

object MesosContainerFactoryProvider extends ContainerFactoryProvider {
  override def getContainerFactory(actorSystem: ActorSystem,
                                   logging: Logging,
                                   config: WhiskConfig,
                                   instance: InstanceId,
                                   parameters: Map[String, Set[String]]): ContainerFactory =
    new MesosContainerFactory(config, actorSystem, logging, parameters)
}
