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

package org.apache.openwhisk.core.loadBalancer.test

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.entity.{
  ActivationId,
  ControllerInstanceId,
  ExecManifest,
  ExecutableWhiskActionMetaData,
  UUID,
  WhiskActivation
}
import org.apache.openwhisk.core.loadBalancer.{InvokerHealth, LoadBalancer, LoadBalancerProvider}
import org.apache.openwhisk.core.WhiskConfig._

import scala.concurrent.Future

class MockLoadBalancer(prefix: String) extends LoadBalancer {
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(IndexedSeq.empty[InvokerHealth])
  override def clusterSize: Int = 1
  override def totalActiveActivations: Future[Int] = Future.successful(1)
  override def activeActivationsFor(namespace: UUID): Future[Int] =
    Future.successful(0)
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    Future.successful(Future.successful(Left(ActivationId(prefix + "-mockLoadBalancerId0"))))
  }
}

object MockLoadBalancerCustom extends LoadBalancerProvider {
  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    new MockLoadBalancer("custom")
  }

  def requiredProperties =
    ExecManifest.requiredProperties ++
      wskApiHost
}

object MockLoadBalancerDefault extends LoadBalancerProvider {
  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = {

    new MockLoadBalancer("default")
  }

  def requiredProperties =
    ExecManifest.requiredProperties ++
      wskApiHost
}