/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.entitlement

import akka.actor.ActorSystem

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

import whisk.common.TransactionId
import whisk.core.entity.Subject
import whisk.core.WhiskConfig

private object LocalEntitlementService {
    /** Poor mans entitlement matrix. Must persist to datastore eventually. */
    private val matrix = TrieMap[(Subject, String), Set[Privilege]]()
}

protected[core] class LocalEntitlementService(
    private val config: WhiskConfig)(
        implicit actorSystem: ActorSystem)
    extends EntitlementService(config) {

    private implicit val executionContext = actorSystem.dispatcher

    private val matrix = LocalEntitlementService.matrix

    /** Grants subject right to resource by adding them to the entitlement matrix. */
    protected[core] override def grant(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = Future {
        synchronized {
            val key = (subject, resource.id)
            matrix.put(key, matrix.get(key) map { _ + right } getOrElse Set(right))
            info(this, s"granted user '$subject' privilege '$right' for '$resource'")
            true
        }
    }

    /** Revokes subject right to resource by removing them from the entitlement matrix. */
    protected[core] override def revoke(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = Future {
        synchronized {
            val key = (subject, resource.id)
            val newrights = matrix.get(key) map { _ - right } map { matrix.put(key, _) }
            info(this, s"revoked user '$subject' privilege '$right' for '$resource'")
            true
        }
    }

    /** Checks if subject has explicit grant for a resource. */
    protected override def entitled(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = Future.successful {
        lazy val one = matrix.get((subject, resource.id)) map { _ contains right } getOrElse false
        lazy val any = matrix.get((subject, resource.parent)) map { _ contains right } getOrElse false
        one || any
    }
}
