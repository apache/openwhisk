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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Identity
import org.apache.openwhisk.core.entity.types.EntityStore

class ActionCollection(entityStore: EntityStore) extends Collection(Collection.ACTIONS) {

  protected override val allowedEntityRights = Privilege.ALL

  /**
   * Computes implicit rights on an action (sequence, in package, or primitive).
   */
  protected[core] override def implicitRights(
    user: Identity,
    namespaces: Set[String],
    right: Privilege,
    resource: Resource)(implicit ep: EntitlementProvider, ec: ExecutionContext, transid: TransactionId) = {
    val isOwner = namespaces.contains(resource.namespace.root.asString)
    resource.entity map { name =>
      right match {
        // if action is in a package, check that the user is entitled to package [binding]
        case (Privilege.READ | Privilege.ACTIVATE) if !resource.namespace.defaultPackage =>
          val packageNamespace = resource.namespace.root.toPath
          val packageName = Some(resource.namespace.last.name)
          val packageResource = Resource(packageNamespace, Collection(Collection.PACKAGES), packageName)
          ep.check(user, Privilege.READ, packageResource) map { _ =>
            true
          }
        case _ => Future.successful(isOwner && allowedEntityRights.contains(right))
      }
    } getOrElse {
      // only a READ on the action collection is permitted if this is the owner of the collection
      Future.successful(isOwner && right == Privilege.READ)
    }
  }

}
