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

package org.apache.openwhisk.core.controller

import scala.concurrent.ExecutionContext
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.concurrent.Future

import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.StatusCodes.Forbidden
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.Directive1

import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entitlement.Resource
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages

/** A trait for routes that require entitlement checks. */
trait BasicAuthorizedRouteProvider extends Directives {

  /** An execution context for futures */
  protected implicit val executionContext: ExecutionContext

  /** An entitlement service to check access rights. */
  protected val entitlementProvider: EntitlementProvider

  /** The collection type for this trait. */
  protected val collection: Collection

  /** Route directives for API. The methods that are supported on the collection. */
  protected lazy val collectionOps = pathEndOrSingleSlash & get

  /** Route directives for API. The path prefix that identifies entity handlers. */
  protected lazy val entityPrefix = pathPrefix(Segment)

  /** Route directives for API. The methods that are supported on entities. */
  protected lazy val entityOps = get

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /** Checks entitlement and dispatches to handler if authorized. */
  protected def authorizeAndDispatch(method: HttpMethod, user: Identity, resource: Resource)(
    implicit transid: TransactionId): RequestContext => Future[RouteResult] = {
    val right = collection.determineRight(method, resource.entity)

    onComplete(entitlementProvider.check(user, right, resource)) {
      case Success(_) => dispatchOp(user, right, resource)
      case Failure(t) =>
        t match {
          case (r: RejectRequest) =>
            r.code match {
              case Forbidden =>
                handleEntitlementFailure(
                  RejectRequest(
                    Forbidden,
                    Some(ErrorResponse(Messages.notAuthorizedtoAccessResource(resource.fqname), transid))))
              case NotFound =>
                handleEntitlementFailure(
                  RejectRequest(NotFound, Some(ErrorResponse(Messages.resourceDoesntExist(resource.fqname), transid))))
              case _ => handleEntitlementFailure(t)
            }
        }
    }
  }

  protected def handleEntitlementFailure(failure: Throwable)(
    implicit transid: TransactionId): RequestContext => Future[RouteResult] = {
    failure match {
      case (r: RejectRequest) => terminate(r.code, r.message)
      case t                  => terminate(InternalServerError)
    }
  }

  /** Dispatches resource to the proper handler depending on context. */
  protected def dispatchOp(user: Identity, op: Privilege, resource: Resource)(
    implicit transid: TransactionId): RequestContext => Future[RouteResult]

  /** Extracts namespace for user from the matched path segment. */
  protected def namespace(user: Identity, ns: String) = {
    validate(
      isNamespace(ns), {
        if (ns.length > EntityName.ENTITY_NAME_MAX_LENGTH) {
          Messages.entityNameTooLong(
            SizeError(namespaceDescriptionForSizeError, ns.length.B, EntityName.ENTITY_NAME_MAX_LENGTH.B))
        } else {
          Messages.namespaceIllegal
        }
      }) & extract(_ => EntityPath(if (EntityPath(ns) == EntityPath.DEFAULT) user.namespace.name.asString else ns))
  }

  /** Validates entity name from the matched path segment. */
  protected val namespaceDescriptionForSizeError = "Namespace"

  /** Extracts the HTTP method which is used to determine privilege for resource. */
  protected val requestMethod = extract(_.request.method)

  /** Confirms that a path segment is a valid namespace. Used to reject invalid namespaces. */
  protected def isNamespace(n: String) = Try { EntityPath(n) } isSuccess
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
  protected lazy val collectionPrefix = pathPrefix((EntityPath.DEFAULT.toString.r | Segment) / collection.path)

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
  def routes(user: Identity)(implicit transid: TransactionId) = {
    collectionPrefix { segment =>
      namespace(user, segment) { ns =>
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
  protected def innerRoutes(user: Identity, ns: EntityPath)(implicit transid: TransactionId) = {
    (entityPrefix & entityOps & requestMethod) { (segment, m) =>
      // matched /namespace/collection/entity
      (entityname(segment) & pathEnd) { name =>
        authorizeAndDispatch(m, user, Resource(ns, collection, Some(name)))
      }
    }
  }

  /** Extracts and validates entity name from the matched path segment. */
  protected def entityname(segment: String): Directive1[String]
}
