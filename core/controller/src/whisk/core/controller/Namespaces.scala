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

package whisk.core.controller

import scala.util.Failure
import scala.util.Success
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.json.DefaultJsonProtocol._
import spray.httpx.SprayJsonSupport._
import spray.routing.Directives
import whisk.common.TransactionId
import whisk.core.entitlement.Collection
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Privilege.READ
import whisk.core.entitlement.Resource
import whisk.core.entity.DocId
import whisk.core.entity.Namespace
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.types.EntityStore
import whisk.http.ErrorResponse.terminate
import whisk.core.entity.WhiskEntityQueries.listEntitiesInNamespace

object WhiskNamespacesApi {
    def requiredProperties = WhiskEntityStore.requiredProperties

    /**
     * A top level namespace sentinel, that is never a valid user namespace, to grant
     * admin rights to managing this entire collection.
     */
    protected[controller] val rootNamespace = Namespace(Namespace.PATHSEP)
}

trait WhiskNamespacesApi
    extends Directives
    with AuthenticatedRouteProvider
    with BasicAuthorizedRouteProvider
    with ReadOps {

    protected override val collection = Collection(Collection.NAMESPACES)

    /** Database service to lookup entities in a namespace. */
    protected val entityStore: EntityStore

    /**
     * Rest API for managing namespaces. Defines all the routes handled by this API. They are:
     *
     * GET  namespaces[/] -- gets namespaces for authenticated user
     * GET  namespaces/_[/] -- gets all entities in implicit namespace
     * GET  namespaces/namespace[/] -- gets all entities in explicit namespace
     *
     * @param user the authenticated user for this route
     */
    override def routes(user: WhiskAuth)(implicit transid: TransactionId) = {
        pathPrefix(collection.path) {
            (collectionOps & requestMethod) { m =>
                getNamespaces(user.subject)
            } ~ (entityOps & entityPrefix & pathEndOrSingleSlash & requestMethod) { (segment, m) =>
                namespace(user.subject, segment) { ns =>
                    val resource = Resource(ns, collection, None)
                    authorizeAndDispatch(m, user, resource)
                }
            }
        }
    }

    /**
     * GET  / -- gets namespaces for authenticated user
     * GET  /namespace -- gets all entities in namespace
     *
     * The namespace of the resource is derived from the authenticated user. The
     * resource entity name, if it is defined, may be a different namespace.
     */
    protected override def dispatchOp(user: WhiskAuth, op: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        resource.entity match {
            case None if op == READ => getAllInNamespace(resource.namespace)
            case _                  => reject // should not get here
        }
    }

    /**
     * Gets all entities in namespace.
     *
     * Responses are one of (Code, Message)
     * - 200 Map [ String (collection name), List[EntitySummary] ] as JSON
     * - 500 Internal Server Error
     */
    private def getAllInNamespace(namespace: Namespace)(implicit transid: TransactionId) = {
        onComplete(listEntitiesInNamespace(entityStore, namespace, false)) {
            case Success(entities) => {
                complete(OK, Namespaces.emptyNamespace ++ entities - WhiskActivation.collectionName)
            }
            case Failure(t) =>
                error(this, s"[GET] namespaces failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }

    /**
     * Gets namespaces for subject from entitlement service.
     *
     * Responses are one of (Code, Message)
     * - 200 [ Namespaces (as String) ] as JSON
     * - 500 Internal Server Error
     */
    private def getNamespaces(user: Subject)(implicit transid: TransactionId) = {
        onComplete(entitlementService.namespaces(user)) {
            case Success(namespaces) =>
                info(this, s"[GET] namespaces success: $namespaces")
                complete(OK, namespaces)
            case Failure(t) =>
                error(this, s"[GET] namespaces failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }
}

object Namespaces {
    val emptyNamespace = Map(
        WhiskAction.collectionName -> List(),
        WhiskPackage.collectionName -> List(),
        WhiskRule.collectionName -> List(),
        WhiskTrigger.collectionName -> List())
}
