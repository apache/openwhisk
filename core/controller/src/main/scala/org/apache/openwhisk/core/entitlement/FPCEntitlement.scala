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

package org.apache.openwhisk.core.entitlement

import scala.concurrent.Future
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.TooManyRequests
import org.apache.openwhisk.common.{Logging, TransactionId, UserEvents}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.{EventMessage, Metric}
import org.apache.openwhisk.core.controller.RejectRequest
import org.apache.openwhisk.core.entitlement.Privilege.ACTIVATE
import org.apache.openwhisk.core.entity.{ControllerInstanceId, Identity}
import org.apache.openwhisk.core.loadBalancer.LoadBalancer

protected[core] class FPCEntitlementProvider(
  private val config: WhiskConfig,
  private val loadBalancer: LoadBalancer,
  private val controllerInstance: ControllerInstanceId)(implicit actorSystem: ActorSystem, logging: Logging)
    extends LocalEntitlementProvider(config, loadBalancer, controllerInstance) {

  override protected[core] def checkThrottles(user: Identity, right: Privilege, resources: Set[Resource])(
    implicit transid: TransactionId): Future[Unit] = {
    if (right == ACTIVATE) {
      val checks = resources.filter(_.collection.path == Collection.ACTIONS).map { res =>
        loadBalancer.checkThrottle(user.namespace.name.toPath, res.fqname)
      }
      if (checks.contains(true)) {
        val metric = Metric("ConcurrentRateLimit", 1)
        UserEvents.send(
          eventProducer,
          EventMessage(
            s"controller${controllerInstance.asString}",
            metric,
            user.subject,
            user.namespace.name.toString,
            user.namespace.uuid,
            metric.typeName))
        Future.failed(RejectRequest(TooManyRequests, "Too many requests"))
      } else Future.successful(())
    } else Future.successful(())
  }

}

private object FPCEntitlementProvider extends EntitlementSpiProvider {

  override def instance(config: WhiskConfig, loadBalancer: LoadBalancer, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging) =
    new FPCEntitlementProvider(config: WhiskConfig, loadBalancer: LoadBalancer, instance)
}
