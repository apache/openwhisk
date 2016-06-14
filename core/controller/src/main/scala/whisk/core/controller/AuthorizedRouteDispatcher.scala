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

import scala.concurrent.ExecutionContext
import scala.util.Try
import spray.http.HttpRequest
import spray.routing.RequestContext
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.InternalServerError
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import spray.routing.directives.OnCompleteFutureMagnet.apply
import spray.routing.directives.OnSuccessFutureMagnet.apply
import spray.routing.RejectionHandler
import spray.routing.Directives
import spray.routing.Directive1
import spray.http.HttpMethod
import spray.http.HttpMethods.{ GET, PUT, POST, DELETE }
import spray.routing.Route
import whisk.common.TransactionId
import whisk.core.entity.Namespace
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAuth
import whisk.core.entitlement.EntitlementService
import whisk.core.entitlement.Collection
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Resource
import whisk.core.entitlement.ThrottleRejectRequest
import whisk.common.Logging
import scala.util.Failure
import scala.util.Success
import whisk.http.ErrorResponse.{ terminate }
import whisk.http.ErrorResponse
import scala.language.postfixOps

/** A trait for routes that require entitlement checks. */
trait BasicAuthorizedRouteProvider extends Directives with Logging {
    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** An entitlement service to check access rights. */
    protected val entitlementService: EntitlementService

    /** The collection type for this trait. */
    protected val collection: Collection

    /** Route directives for API. The methods that are supported on the collection. */
    protected lazy val collectionOps = pathEndOrSingleSlash & get

    /** Route directives for API. The path prefix that identifies entity handlers. */
    protected lazy val entityPrefix = pathPrefix(Segment)

    /** Route directives for API. The methods that are supported on entities. */
    protected lazy val entityOps = get

    /** Checks entitlement and dispatches to handler if authorized. */
    protected def authorizeAndDispatch(
        method: HttpMethod,
        user: WhiskAuth,
        resource: Resource)(
            implicit transid: TransactionId): RequestContext => Unit = {
        val right = collection.determineRight(method, resource.entity)
        authorizeAndContinue(right, user.subject, resource, () => dispatchOp(user, right, resource))
    }

    /** Checks entitlement and if authorized, continues with next handler. */
    protected def authorizeAndContinue(
        right: Privilege,
        user: Subject,
        resource: Resource,
        next: () => RequestContext => Unit)(
            implicit transid: TransactionId): RequestContext => Unit = {
        onComplete(entitlementService.check(user, right, resource)) {
            case Success(entitment) =>
                authorize(entitment) {
                    next()
                }
            case Failure(r: RejectRequest) =>
                terminate(r.code, r.message)
            case Failure(r: ThrottleRejectRequest) =>
                terminate(r.code, r.message)
            case Failure(t) =>
                terminate(InternalServerError, t.getMessage)
        }
    }

    /** Dispatches resource to the proper handler depending on context. */
    protected def dispatchOp(
        user: WhiskAuth,
        op: Privilege,
        resource: Resource)(
            implicit transid: TransactionId): RequestContext => Unit

    /** Extracts namespace for user from the matched path segment. */
    protected def namespace(user: Subject, ns: String) = {
        validate(isNamespace(ns), "namespace contains invalid characters") &
            extract(_ => Namespace(if (Namespace(ns) == Namespace.DEFAULT) user() else ns))
    }

    /** Extracts the HTTP method which is used to determine privilege for resource. */
    protected val requestMethod = extract(_.request.method)

    /** Confirms that a path segment is a valid namespace. Used to reject invalid namespaces. */
    protected def isNamespace(n: String) = Try { Namespace(n) } isSuccess
}

/**
 * A common trait for entity routes that require entitlement checks,
 * which share common collectionPrefix and entity operations.
 */
trait AuthorizedRouteProvider extends BasicAuthorizedRouteProvider {

    /**
     * Route directives for API.
     * The default path prefix for the collection is one of
     * '_/collection-path' matching an implicit namespace, or
     * 'explicit-namespace/collection-path'.
     */
    protected lazy val collectionPrefix = pathPrefix((Namespace.DEFAULT.toString.r | Segment) / collection.path)

    /** Route directives for API. The methods that are supported on entities. */
    override protected lazy val entityOps = put | get | delete | post

    /**
     * Common REST API for Whisk Entities. Defines all the routes handled by this API. They are:
     *
     * GET  namespace/entities[/]   -- list all entities in namespace
     * GET  namespace/entities/name -- fetch entity by name from namespace
     * PUT  namespace/entities/name -- create or update entity by name from namespace with content
     * DEL  namespace/entities/name -- remove entity by name form namespace
     * POST namespace/entities/name -- "activate" entity by name from namespace with content
     *
     * @param user the authenticated user for this route
     */
    def routes(user: WhiskAuth)(implicit transid: TransactionId) = {
        collectionPrefix { segment =>
            namespace(user.subject, segment) { ns =>
                (collectionOps & requestMethod) {
                    // matched /namespace/collection
                    authorizeAndDispatch(_, user, Resource(ns, collection, None))
                } ~ innerRoutes(user, ns)
            }
        }
    }

    /**
     * Handles the inner routes of the collection. This allows customizing nested resources.
     */
    protected def innerRoutes(user: WhiskAuth, ns: Namespace)(implicit transid: TransactionId) = {
        (entityPrefix & entityOps & requestMethod) { (segment, m) =>
            // matched /namespace/collection/entity
            (entityname(segment) & pathEnd) {
                name => authorizeAndDispatch(m, user, Resource(ns, collection, Some(name)))
            }
        }
    }

    /** Extracts and validates entity name from the matched path segment. */
    protected def entityname(segment: String): Directive1[String]
}
