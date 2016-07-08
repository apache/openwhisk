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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import spray.http.HttpMethod
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.types.EntityStore

/**
 * A collection encapsulates the name of a collection and implicit rights when subject
 * lacks explicit rights on a resource in the collection.
 *
 * @param path the name of the collection (the resource path in URI and the view name in the datastore)
 * @param activate the privilege for an activate (may be ACTIVATE or REJECT for example)
 * @param listLimit the default limit on number of entities returned from a collection on a list operation
 */
protected[core] case class Collection protected (
    val path: String,
    val listLimit: Int = 30)
    extends Logging {
    override def toString = path

    /** Determines the right to request for the resources and context. */
    protected[core] def determineRight(op: HttpMethod, resource: Option[String])(
        implicit transid: TransactionId): Privilege = {
        op match {
            case GET    => Privilege.READ
            case PUT    => resource map { _ => Privilege.PUT } getOrElse Privilege.REJECT
            case POST   => resource map { _ => activateAllowed } getOrElse Privilege.REJECT
            case DELETE => resource map { _ => Privilege.DELETE } getOrElse Privilege.REJECT
            case _      => Privilege.REJECT
        }
    }

    protected val allowedCollectionRights = Set(Privilege.READ)
    protected val allowedEntityRights = {
        Set(Privilege.READ, Privilege.PUT, Privilege.ACTIVATE, Privilege.DELETE)
    }

    private lazy val activateAllowed = {
        if (allowedEntityRights.contains(Privilege.ACTIVATE)) {
            Privilege.ACTIVATE
        } else Privilege.REJECT
    }

    /**
     * Infers implicit rights on a resource in the collection before checking explicit
     * rights in the entitlement matrix. The subject has CRUD and activate rights
     * to any resource in in any of their namespaces as long as the right (the implied operation)
     * is permitted on the resource.
     */
    protected[core] def implicitRights(namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ec: ExecutionContext, transid: TransactionId): Future[Boolean] = Future.successful {
        // if the resource root namespace is in any of the allowed namespaces
        // then this is an owner of the resource
        val self = namespaces.contains(resource.namespace.root())

        resource.entity map {
            _ => self && allowedEntityRights.contains(right)
        } getOrElse {
            self && allowedCollectionRights.contains(right)
        }
    }
}

/** An enumeration of known collections. */
protected[core] object Collection {

    protected[core] def requiredProperties = WhiskEntityStore.requiredProperties

    protected[core] val ACTIONS = WhiskAction.collectionName
    protected[core] val TRIGGERS = WhiskTrigger.collectionName
    protected[core] val RULES = WhiskRule.collectionName
    protected[core] val PACKAGES = WhiskPackage.collectionName
    protected[core] val ACTIVATIONS = WhiskActivation.collectionName
    protected[core] val NAMESPACES = "namespaces"

    private val collections = scala.collection.mutable.Map[String, Collection]()
    private def register(c: Collection) = collections += c.path -> c

    protected[core] def apply(name: String) = collections.get(name).get

    protected[core] def initialize(entityStore: EntityStore, verbosity: Verbosity.Level) = {
        register(new Collection(ACTIONS))
        register(new Collection(TRIGGERS))
        register(new Collection(RULES))
        register(new PackageCollection(entityStore))

        register(new Collection(ACTIVATIONS) {
            protected[core] override def determineRight(op: HttpMethod, resource: Option[String])(
                implicit transid: TransactionId) = {
                if (op == GET) Privilege.READ else Privilege.REJECT
            }

            protected override val allowedEntityRights = Set(Privilege.READ)
        })

        register(new Collection(NAMESPACES) {
            protected[core] override def determineRight(op: HttpMethod, resource: Option[String])(
                implicit transid: TransactionId) = {
                resource map { _ => Privilege.REJECT } getOrElse {
                    if (op == GET) Privilege.READ else Privilege.REJECT
                }
            }

            protected override val allowedEntityRights = Set(Privilege.READ)
        })

        collections foreach { case (n, c) => c.setVerbosity(verbosity) }
    }
}
