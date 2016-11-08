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

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import Privilege.ACTIVATE
import Privilege.Privilege
import Privilege.REJECT
import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import spray.http.StatusCodes.ClientError
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.TooManyRequests
import whisk.common.ConsulClient
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.controller.RejectRequest
import whisk.core.entity.EntityPath
import whisk.core.entity.Identity
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.core.iam.NamespaceProvider
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.ErrorResponse
import whisk.http.Messages._

package object types {
    type Entitlements = TrieMap[(Subject, String), Set[Privilege]]
}

/**
 * Resource is a type that encapsulates details relevant to identify a specific resource.
 * It may be an entire collection, or an element in a collection.
 *
 * @param ns the namespace the resource resides in
 * @param collection the collection (e.g., actions, triggers) identifying a resource
 * @param entity an optional entity name that identifies a specific item in the collection
 * @param env an optional environment to bind to the resource during an activation
 */
protected[core] case class Resource(
    namespace: EntityPath,
    collection: Collection,
    entity: Option[String],
    env: Option[Parameters] = None) {
    def parent = collection.path + EntityPath.PATHSEP + namespace
    def id = parent + (entity map { EntityPath.PATHSEP + _ } getOrElse (""))
    override def toString = id
}

protected[core] object EntitlementService {
    val requiredProperties = WhiskConfig.consulServer ++ WhiskConfig.entitlementHost ++ Map(
        WhiskConfig.actionInvokePerMinuteDefaultLimit -> null,
        WhiskConfig.actionInvokeConcurrentDefaultLimit -> null,
        WhiskConfig.triggerFirePerMinuteDefaultLimit -> null,
        WhiskConfig.actionInvokeSystemOverloadDefaultLimit -> null)

    val optionalProperties = Set(
        WhiskConfig.actionInvokePerMinuteLimit,
        WhiskConfig.actionInvokeConcurrentLimit,
        WhiskConfig.triggerFirePerMinuteLimit,
        WhiskConfig.actionInvokeSystemOverloadLimit)
}

/**
 * A trait that implements entitlements to resources. It performs checks for CRUD and Acivation requests.
 * This is where enforcement of activation quotas takes place, in additional to basic authorization.
 */
protected[core] abstract class EntitlementService(config: WhiskConfig, loadBalancer: LoadBalancer, iam: NamespaceProvider)(
    implicit actorSystem: ActorSystem) extends Logging {

    private implicit val executionContext = actorSystem.dispatcher

    private val invokeRateThrottler = new RateThrottler(config.actionInvokePerMinuteLimit.toInt)
    private val triggerRateThrottler = new RateThrottler(config.triggerFirePerMinuteLimit.toInt)
    private val concurrentInvokeThrottler = new ActivationThrottler(config.consulServer, loadBalancer, config.actionInvokeConcurrentLimit.toInt, config.actionInvokeSystemOverloadLimit.toInt)

    private val consul = new ConsulClient(config.consulServer)

    override def setVerbosity(level: LogLevel) = {
        super.setVerbosity(level)
        invokeRateThrottler.setVerbosity(level)
        triggerRateThrottler.setVerbosity(level)
    }

    /**
     * Grants a subject the right to access a resources.
     *
     * @param subject the subject to grant right to
     * @param right the privilege to grant the subject
     * @param resource the resource to grant the subject access to
     * @return a promise that completes with true iff the subject is granted the right to access the requested resource
     */
    protected[core] def grant(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean]

    /**
     * Revokes a subject the right to access a resources.
     *
     * @param subject the subject to revoke right to
     * @param right the privilege to revoke the subject
     * @param resource the resource to revoke the subject access to
     * @return a promise that completes with true iff the subject is revoked the right to access the requested resource
     */
    protected[core] def revoke(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean]

    /**
     * Checks if a subject is entitled to a resource because it was granted the right explicitly.
     *
     * @param subject the subject to check rights for
     * @param right the privilege the subject is requesting
     * @param resource the resource the subject requests access to
     * @return a promise that completes with true iff the subject is permitted to access the request resource
     */
    protected def entitled(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean]

    /**
     * Checks if a subject has the right to access a resource. The entitlement may be implicit,
     * that is, inferred based on namespaces that a subject belongs to and the namespace of the
     * resource for example, or explicit. The implicit check is computed here. The explicit check
     * is delegated to the service implementing this interface.
     *
     * @param subject the subject to check rights for
     * @param right the privilege the subject is requesting
     * @param resource the resource the subject requests access to
     * @return a promise that completes with true iff the subject is permitted to access the request resource
     */
    protected[core] def check(user: Identity, right: Privilege, resource: Resource)(
        implicit transid: TransactionId): Future[Boolean] = {

        val subject = user.subject

        if (user.rights.contains(right)) {
            info(this, s"checking user '$subject' has privilege '$right' for '$resource'")
            checkSystemOverload(subject, right, resource) orElse {
                checkUserThrottle(subject, right, resource)
            } orElse {
                checkConcurrentUserThrottle(subject, right, resource)
            } getOrElse checkPrivilege(user, right, resource)
        } else if (right != REJECT) {
            info(this, s"supplied authkey for user '$subject' does not have privilege '$right' for '$resource'")
            Future.failed(OperationNotAllowed(Forbidden, Some(ErrorResponse(notAuthorizedtoOperateOnResource, transid))))
        } else {
            Future.successful(false)
        } andThen {
            case Success(r) =>
                info(this, if (r) "authorized" else "not authorized")
            case Failure(r: RejectRequest) =>
                info(this, s"not authorized: ${r.message}")
            case Failure(t) =>
                error(this, s"failed while checking entitlement: ${t.getMessage}")
        }
    }

    // NOTE: explicit grants do not work with package bindings because the current model
    // for authorization does not allow for a continuation to check that both the binding
    // and the references package are both either implicitly or explicitly granted; this is
    // accepted for the time being however because there exists no external mechanism to create
    // explicit grants
    protected def checkPrivilege(user: Identity, right: Privilege, resource: Resource)(
        implicit transid: TransactionId): Future[Boolean] = {
        // check the default namespace first, bypassing additional checks if permitted
        val defaultNamespaces = Set(user.namespace())
        resource.collection.implicitRights(defaultNamespaces, right, resource) flatMap {
            case true => Future successful true
            case false =>
                // currently allow subject to work across any of their namespaces
                // but this feature will be removed in future iterations, thereby removing
                // the iam entanglement with entitlement
                iam.namespaces(user.subject) flatMap {
                    additionalNamespaces =>
                        val newNamespacesToCheck = additionalNamespaces -- defaultNamespaces
                        if (newNamespacesToCheck nonEmpty) {
                            resource.collection.implicitRights(newNamespacesToCheck, right, resource) flatMap {
                                case true  => Future.successful(true)
                                case false => entitled(user.subject, right, resource)
                            }
                        } else entitled(user.subject, right, resource)
                }
        }
    }

    /** Limits activations if the load balancer is overloaded. */
    protected def checkSystemOverload(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        val systemOverload = right == ACTIVATE && concurrentInvokeThrottler.isOverloaded
        if (systemOverload) {
            error(this, "system is overloaded")
            Some {
                Future failed ThrottleRejectRequest(TooManyRequests, Some(ErrorResponse(systemOverloaded, transid)))
            }
        } else None
    }

    /** Limits activations if subject exceeds their own limits. */
    protected def checkUserThrottle(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        def userThrottled = {
            val isInvocation = resource.collection.path == Collection.ACTIONS
            val isTrigger = resource.collection.path == Collection.TRIGGERS
            (isInvocation && !invokeRateThrottler.check(subject)) || (isTrigger && !triggerRateThrottler.check(subject))
        }

        if (right == ACTIVATE && userThrottled) {
            Some {
                Future failed ThrottleRejectRequest(TooManyRequests, Some(ErrorResponse(tooManyRequests, transid)))
            }
        } else None
    }

    /** Limits activations if subject exceeds limit of concurrent invocations */
    protected def checkConcurrentUserThrottle(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        def userThrottled = {
            val isInvocation = resource.collection.path == Collection.ACTIONS
            (isInvocation && !concurrentInvokeThrottler.check(subject))
        }

        if (right == ACTIVATE && userThrottled) {
            Some {
                Future failed ThrottleRejectRequest(TooManyRequests, Some(ErrorResponse(tooManyConcurrentRequests, transid)))
            }
        } else None
    }
}

/** An exception to throw signaling the request is rejected due to load reasons. */
case class ThrottleRejectRequest(code: ClientError, message: Option[ErrorResponse]) extends Throwable

/** An exception for authkey that does not have sufficient rights. */
case class OperationNotAllowed(code: ClientError, message: Option[ErrorResponse]) extends Throwable
