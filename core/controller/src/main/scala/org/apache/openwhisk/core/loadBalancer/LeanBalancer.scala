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

package org.apache.openwhisk.core.loadBalancer

import akka.actor.{ActorRef, ActorSystem, Props}
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.ContainerPoolConfig
import org.apache.openwhisk.core.entity.ControllerInstanceId
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.invoker.InvokerProvider
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.ExecutionContextFactory
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.core.entity.size._

import scala.concurrent.Future

/**
 * Lean loadbalancer implemetation.
 *
 * Communicates with Invoker directly without Kafka in the middle. Invoker does not exist as a separate entity, it is built together with Controller
 * Uses LeanMessagingProvider to use in-memory queue instead of Kafka
 */
class LeanBalancer(config: WhiskConfig,
                   feedFactory: FeedFactory,
                   controllerInstance: ControllerInstanceId,
                   implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
  implicit actorSystem: ActorSystem,
  logging: Logging)
    extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  /** Loadbalancer interface methods */
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(IndexedSeq.empty[InvokerHealth])
  override def clusterSize: Int = 1

  val poolConfig: ContainerPoolConfig = loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool)

  val invokerName = InvokerInstanceId(0, None, None, poolConfig.userMemory)

  /** 1. Publish a message to the loadbalancer */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    /** 2. Update local state with the activation to be executed scheduled. */
    val activationResult = setupActivation(msg, action, invokerName)
    sendActivationToInvoker(messageProducer, msg, invokerName).map(_ => activationResult)
  }

  /** Creates an invoker for executing user actions. There is only one invoker in the lean model. */
  private def makeALocalThreadedInvoker(): Unit = {
    implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
    val limitConfig: ConcurrencyLimitConfig = loadConfigOrThrow[ConcurrencyLimitConfig](ConfigKeys.concurrencyLimit)
    SpiLoader.get[InvokerProvider].instance(config, invokerName, messageProducer, poolConfig, limitConfig)
  }

  makeALocalThreadedInvoker()

  override protected val invokerPool: ActorRef = actorSystem.actorOf(Props.empty)

  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    // Currently do nothing
  }

  override protected def emitMetrics() = {
    super.emitMetrics()
  }
}

object LeanBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(implicit actorSystem: ActorSystem,
                                                                                  logging: Logging): LoadBalancer = {

    new LeanBalancer(whiskConfig, createFeedFactory(whiskConfig, instance), instance)
  }

  def requiredProperties =
    ExecManifest.requiredProperties ++
      wskApiHost
}
