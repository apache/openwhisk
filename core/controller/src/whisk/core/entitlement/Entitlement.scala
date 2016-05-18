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
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import Privilege.REJECT
import spray.http.StatusCodes.ClientError
import spray.http.StatusCodes.TooManyRequests
import spray.json.JsBoolean
import whisk.common.ConsulKV
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.Subject
import whisk.http.ErrorResponse
import scala.language.postfixOps
import whisk.common.LoggingMarkers

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
    namespace: Namespace,
    collection: Collection,
    entity: Option[String],
    env: Option[Parameters] = None) {
    def parent = collection.path + Namespace.PATHSEP + namespace
    def id = parent + (entity map { Namespace.PATHSEP + _ } getOrElse (""))
    override def toString = id
}

protected[core] object EntitlementService {
    /**
     * Remote entitlement service requires a host:port definition. If not given,
     * i.e., the value equals ":", use a local entitlement service.
     */
    val LOCAL_ENTITLEMENT_HOST = ":"
}

/**
 * A trait for entitlement service. This is a WIP.
 */
protected[core] abstract class EntitlementService(config: WhiskConfig)(implicit ec: ExecutionContext) extends Logging {

    private var loadbalancerOverload = false
    private val invokeRateThrottler = new RateThrottler(config, 120, 3600)
    private val triggerRateThrottler = new RateThrottler(config, 60, 720)

    new Thread() {
        /** query the KV store this often */
        private val overloadCheckPeriodMillis = 10000

        private val kvStore = new ConsulKV(config.consulServer)

        /** Continously read from the KV store to get invoker status.*/
        override def run() = {
            var count = 0
            while (true) {
                val isOverload = kvStore.get(ConsulKV.LoadBalancerKeys.overloadKey)
                isOverload match {
                    case JsBoolean(v) => if ((count == 0) || loadbalancerOverload != v) {
                        loadbalancerOverload = v
                        info(this, s"EntitlementService: loadbalancerOverload = ${v}")
                        count = count + 1
                    }
                    case _ => ()
                }
                Thread.sleep(overloadCheckPeriodMillis)
            }
        }
    }.start()

    override def setVerbosity(level: Verbosity.Level) = {
        super.setVerbosity(level)
        invokeRateThrottler.setVerbosity(level)
        triggerRateThrottler.setVerbosity(level)
    }

    /**
     * Gets the list of namespaces the subject has rights to.
     *
     * @param subject the subject to lookup namespaces for
     * @return a promise that completes with list of namespaces the subject has rights to
     */
    protected[core] def namespaces(subject: Subject)(implicit transid: TransactionId): Future[List[String]]

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
    protected[core] def check(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId): Future[Boolean] = {
        checkSystemOverload(subject, right, resource) orElse {
            checkUserThrottle(subject, right, resource)
        } getOrElse {
            val promise = Promise[Boolean]

            // NOTE: explicit grants do not work with package bindings because the current model
            // for authorization does not allow for a continuation to check that both the binding
            // and the references package are both either implicitly or explicitly granted; this is
            // accepted for the time being however because there exists no external mechanism to create
            // explicit grants
            val grant = if (right != REJECT) {
                info(this, s"checking user '$subject' has privilege '$right' for '$resource'", LoggingMarkers.CONTROLLER_CHECK_ENTITLEMENT_START)
                namespaces(subject) flatMap {
                    resource.collection.implicitRights(_, right, resource) flatMap {
                        case true  => Future successful true
                        case false => entitled(subject, right, resource)
                    }
                }
            } else Future successful false

            grant onComplete {
                case Success(r) =>
                    info(this, if (r) "authorized" else "not authorized", LoggingMarkers.CONTROLLER_CHECK_ENTITLEMENT_DONE)
                    promise success r
                case Failure(t) =>
                    error(this, s"failed while checking entitlement: ${t.getMessage}", LoggingMarkers.CONTROLLER_CHECK_ENTITLEMENT_ERROR)
                    promise success false
            }

            promise future
        }
    }

    /** Limits activations if the load balancer is overloaded. */
    protected def checkSystemOverload(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        val systemOverload = right == Privilege.ACTIVATE && loadbalancerOverload
        if (systemOverload) {
            Some { Future failed ThrottleRejectRequest(TooManyRequests, Some(ErrorResponse("System is overloaded", transid))) }
        } else None
    }

    /** Limits activations if subject exceeds their own limits. */
    protected def checkUserThrottle(subject: Subject, right: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        def userThrottled = {
            val isInvocation = resource.collection.path == Collection.ACTIONS
            val isTrigger = resource.collection.path == Collection.TRIGGERS
            (isInvocation && !invokeRateThrottler.check(subject)) || (isTrigger && !triggerRateThrottler.check(subject))
        }

        if (right == Privilege.ACTIVATE && userThrottled) {
            Some { Future failed ThrottleRejectRequest(TooManyRequests, Some(ErrorResponse("Too many requests from user", transid))) }
        } else None
    }
}

/** An exception to throw signaling the request is rejected due to load reasons */
case class ThrottleRejectRequest(code: ClientError, message: Option[ErrorResponse]) extends Throwable
