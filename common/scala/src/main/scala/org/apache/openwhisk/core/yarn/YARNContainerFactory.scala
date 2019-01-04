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

package org.apache.openwhisk.core.yarn

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.{ByteSize, ExecManifest, InvokerInstanceId}
import org.apache.openwhisk.core.yarn.YARNServiceActor.{CreateContainer, CreateService, RemoveService}
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import pureconfig.loadConfigOrThrow

import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class YARNConfig(masterUrl: String,
                      yarnLinkLogMessage: Boolean,
                      serviceName: String,
                      authType: String,
                      kerberosPrincipal: String,
                      kerberosKeytab: String,
                      queue: String,
                      memory: String,
                      cpus: Int)

object YARNContainerFactoryProvider extends ContainerFactoryProvider {
  override def instance(actorSystem: ActorSystem,
                        logging: Logging,
                        config: WhiskConfig,
                        instance: InvokerInstanceId,
                        parameters: Map[String, Set[String]]): ContainerFactory =
    new YARNContainerFactory(actorSystem, logging, config, instance, parameters)
}
class YARNContainerFactory(actorSystem: ActorSystem,
                           logging: Logging,
                           config: WhiskConfig,
                           instance: InvokerInstanceId,
                           parameters: Map[String, Set[String]],
                           containerArgs: ContainerArgsConfig =
                             loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs),
                           yarnConfig: YARNConfig = loadConfigOrThrow[YARNConfig](ConfigKeys.yarn))
    extends ContainerFactory {

  private val yarnServiceActor =
    actorSystem.actorOf(
      Props(new YARNServiceActor(actorSystem, logging, yarnConfig, instance)),
      name = "YARNServiceActor")

  val serviceStartTimeoutMS = 60000
  val containerStartTimeoutMS = 60000

  override def init(): Unit = {
    blocking {
      implicit val timeout: Timeout = Timeout(serviceStartTimeoutMS.milliseconds)
      val future = yarnServiceActor ? CreateService
      Await.result(future, timeout.duration)
    }
  }
  override def createContainer(
    unusedtid: TransactionId,
    unusedname: String,
    actionImage: ExecManifest.ImageName,
    unuseduserProvidedImage: Boolean,
    unusedmemory: ByteSize,
    unusedcpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    implicit val timeout: Timeout = Timeout(containerStartTimeoutMS.milliseconds)
    ask(yarnServiceActor, CreateContainer(actionImage)).mapTo[Container]
  }
  override def cleanup(): Unit = {
    yarnServiceActor ! RemoveService
    implicit val timeout: Timeout = Timeout(serviceStartTimeoutMS.milliseconds)
    val future = ask(yarnServiceActor, RemoveService)
    Await.result(future, timeout.duration)
    actorSystem.stop(yarnServiceActor)
  }
}
