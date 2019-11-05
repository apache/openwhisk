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

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import akka.actor.ActorSystem
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.{ControllerInstanceId, Identity, Subject}
import org.apache.openwhisk.core.loadBalancer.LoadBalancer

protected[core] class LocalEntitlementProvider(
  private val config: WhiskConfig,
  private val loadBalancer: LoadBalancer,
  private val controllerInstance: ControllerInstanceId)(implicit actorSystem: ActorSystem, logging: Logging)
    extends EntitlementProvider(config, loadBalancer, controllerInstance) {

  private implicit val executionContext = actorSystem.dispatcher

  private val matrix = LocalEntitlementProvider.matrix

  /** Grants subject right to resource by adding them to the entitlement matrix. */
  protected[core] override def grant(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId) = Future {
    val subject = user.subject
    synchronized {
      val key = (subject, resource.id)
      matrix.put(key, matrix.get(key) map { _ + right } getOrElse Set(right))
      logging.debug(this, s"granted user '$subject' privilege '$right' for '$resource'")
      true
    }
  }

  /** Revokes subject right to resource by removing them from the entitlement matrix. */
  protected[core] override def revoke(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId) = Future {
    val subject = user.subject
    synchronized {
      val key = (subject, resource.id)
      val newrights = matrix.get(key) map { _ - right } map { matrix.put(key, _) }
      logging.debug(this, s"revoked user '$subject' privilege '$right' for '$resource'")
      true
    }
  }

  /** Checks if subject has explicit grant for a resource. */
  protected override def entitled(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId) = Future.successful {
    val subject = user.subject
    lazy val one = matrix.get((subject, resource.id)) map { _ contains right } getOrElse false
    lazy val any = matrix.get((subject, resource.parent)) map { _ contains right } getOrElse false
    one || any
  }
}

private object LocalEntitlementProvider extends EntitlementSpiProvider {

  /** Poor mans entitlement matrix. Must persist to datastore eventually. */
  private val matrix = TrieMap[(Subject, String), Set[Privilege]]()
  override def instance(config: WhiskConfig, loadBalancer: LoadBalancer, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging) =
    new LocalEntitlementProvider(config: WhiskConfig, loadBalancer: LoadBalancer, instance)
}
