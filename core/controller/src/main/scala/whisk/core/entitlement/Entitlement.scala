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

package whisk.core.entitlement

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Set
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.Forbidden
import akka.http.scaladsl.model.StatusCodes.TooManyRequests

import whisk.core.entitlement.Privilege.ACTIVATE
import whisk.core.entitlement.Privilege._
import whisk.core.entitlement.Privilege.REJECT
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.controller.RejectRequest
import whisk.core.entity._
import whisk.core.loadBalancer.LoadBalancer
import whisk.http.ErrorResponse
import whisk.http.Messages
import whisk.http.Messages._

package object types {
  type Entitlements = TrieMap[(Subject, String), Set[Privilege]]
}

/**
 * Resource is a type that encapsulates details relevant to identify a specific resource.
 * It may be an entire collection, or an element in a collection.
 *
 * @param namespace the namespace the resource resides in
 * @param collection the collection (e.g., actions, triggers) identifying a resource
 * @param entity an optional entity name that identifies a specific item in the collection
 * @param env an optional environment to bind to the resource during an activation
 */
protected[core] case class Resource(namespace: EntityPath,
                                    collection: Collection,
                                    entity: Option[String],
                                    env: Option[Parameters] = None) {
  def parent = collection.path + EntityPath.PATHSEP + namespace
  def id = parent + entity.map(EntityPath.PATHSEP + _).getOrElse("")
  def fqname = namespace.asString + entity.map(EntityPath.PATHSEP + _).getOrElse("")
  override def toString = id
}

protected[core] object EntitlementProvider {

  val requiredProperties = Map(
    WhiskConfig.actionInvokePerMinuteLimit -> null,
    WhiskConfig.actionInvokeConcurrentLimit -> null,
    WhiskConfig.triggerFirePerMinuteLimit -> null,
    WhiskConfig.actionInvokeSystemOverloadLimit -> null,
    WhiskConfig.controllerInstances -> null,
    WhiskConfig.controllerHighAvailability -> null)
}

/**
 * A trait that implements entitlements to resources. It performs checks for CRUD and Acivation requests.
 * This is where enforcement of activation quotas takes place, in additional to basic authorization.
 */
protected[core] abstract class EntitlementProvider(config: WhiskConfig, loadBalancer: LoadBalancer)(
  implicit actorSystem: ActorSystem,
  logging: Logging) {

  private implicit val executionContext = actorSystem.dispatcher

  /**
   * The number of controllers if HA is enabled, 1 otherwise
   */
  private val diviser = if (config.controllerHighAvailability) config.controllerInstances.toInt else 1

  /**
   * Allows 20% of additional requests on top of the limit to mitigate possible unfair round-robin loadbalancing between
   * controllers
   */
  private val overcommit = if (config.controllerHighAvailability) 1.2 else 1

  /**
   * Adjust the throttles for a single controller with the diviser and the overcommit.
   *
   * @param originalThrottle The throttle that needs to be adjusted for this controller.
   */
  private def dilateThrottle(originalThrottle: Int): Int = {
    Math.ceil((originalThrottle.toDouble / diviser.toDouble) * overcommit).toInt
  }

  private val invokeRateThrottler =
    new RateThrottler(
      "actions per minute",
      dilateThrottle(config.actionInvokePerMinuteLimit.toInt),
      _.limits.invocationsPerMinute.map(dilateThrottle))
  private val triggerRateThrottler =
    new RateThrottler(
      "triggers per minute",
      dilateThrottle(config.triggerFirePerMinuteLimit.toInt),
      _.limits.firesPerMinute.map(dilateThrottle))
  private val concurrentInvokeThrottler = new ActivationThrottler(
    loadBalancer,
    config.actionInvokeConcurrentLimit.toInt,
    config.actionInvokeSystemOverloadLimit.toInt)

  /**
   * Grants a subject the right to access a resources.
   *
   * @param subject the subject to grant right to
   * @param right the privilege to grant the subject
   * @param resource the resource to grant the subject access to
   * @return a promise that completes with true iff the subject is granted the right to access the requested resource
   */
  protected[core] def grant(subject: Subject, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Revokes a subject the right to access a resources.
   *
   * @param subject the subject to revoke right to
   * @param right the privilege to revoke the subject
   * @param resource the resource to revoke the subject access to
   * @return a promise that completes with true iff the subject is revoked the right to access the requested resource
   */
  protected[core] def revoke(subject: Subject, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Checks if a subject is entitled to a resource because it was granted the right explicitly.
   *
   * @param subject the subject to check rights for
   * @param right the privilege the subject is requesting
   * @param resource the resource the subject requests access to
   * @return a promise that completes with true iff the subject is permitted to access the request resource
   */
  protected def entitled(subject: Subject, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Checks action activation rate throttles for an identity.
   *
   * @param user the identity to check rate throttles for
   * @return a promise that completes with success iff the user is within their activation quota
   */
  protected[core] def checkThrottles(user: Identity)(implicit transid: TransactionId): Future[Unit] = {

    logging.debug(this, s"checking user '${user.subject}' has not exceeded activation quota")
    checkSystemOverload(ACTIVATE)
      .flatMap(_ => checkThrottleOverload(Future.successful(invokeRateThrottler.check(user)), user))
      .flatMap(_ => checkThrottleOverload(concurrentInvokeThrottler.check(user), user))
  }

  /**
   * Checks if a subject has the right to access a specific resource. The entitlement may be implicit,
   * that is, inferred based on namespaces that a subject belongs to and the namespace of the
   * resource for example, or explicit. The implicit check is computed here. The explicit check
   * is delegated to the service implementing this interface.
   *
   * NOTE: do not use this method to check a package binding because this method does not allow
   * for a continuation to check that both the binding and the references package are both either
   * implicitly or explicitly granted. Instead, resolve the package binding first and use the alternate
   * method which authorizes a set of resources.
   *
   * @param user the subject to check rights for
   * @param right the privilege the subject is requesting (applies to the entire set of resources)
   * @param resource the resource the subject requests access to
   * @return a promise that completes with success iff the subject is permitted to access the requested resource
   */
  protected[core] def check(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Unit] = check(user, right, Set(resource))

  /**
   * Constructs a RejectRequest containing the forbidden resources.
   *
   * @param resources resources forbidden to access
   * @return a RejectRequest with the appropriate message
   */
  private def unauthorizedOn(resources: Set[Resource])(implicit transid: TransactionId) = {
    RejectRequest(
      Forbidden,
      Some(
        ErrorResponse(
          Messages.notAuthorizedtoAccessResource(resources.map(_.fqname).toSeq.sorted.toSet.mkString(", ")),
          transid)))
  }

  /**
   * Checks if a subject has the right to access a set of resources. The entitlement may be implicit,
   * that is, inferred based on namespaces that a subject belongs to and the namespace of the
   * resource for example, or explicit. The implicit check is computed here. The explicit check
   * is delegated to the service implementing this interface.
   *
   * @param user the subject identity to check rights for
   * @param right the privilege the subject is requesting (applies to the entire set of resources)
   * @param resources the set of resources the subject requests access to
   * @param noThrottle ignore throttle limits
   * @return a promise that completes with success iff the subject is permitted to access all of the requested resources
   */
  protected[core] def check(user: Identity, right: Privilege, resources: Set[Resource], noThrottle: Boolean = false)(
    implicit transid: TransactionId): Future[Unit] = {
    val subject = user.subject

    val entitlementCheck: Future[Unit] = if (user.rights.contains(right)) {
      if (resources.nonEmpty) {
        logging.debug(this, s"checking user '$subject' has privilege '$right' for '${resources.mkString(", ")}'")
        val throttleCheck =
          if (noThrottle) Future.successful(())
          else
            checkSystemOverload(right)
              .flatMap(_ => checkUserThrottle(user, right, resources))
              .flatMap(_ => checkConcurrentUserThrottle(user, right, resources))
        throttleCheck
          .flatMap(_ => checkPrivilege(user, right, resources))
          .flatMap(checkedResources => {
            val failedResources = checkedResources.filterNot(_._2)
            if (failedResources.isEmpty) Future.successful(())
            else Future.failed(unauthorizedOn(failedResources.map(_._1)))
          })
      } else Future.successful(())
    } else if (right != REJECT) {
      logging.debug(
        this,
        s"supplied authkey for user '$subject' does not have privilege '$right' for '${resources.mkString(", ")}'")
      Future.failed(unauthorizedOn(resources))
    } else {
      Future.failed(unauthorizedOn(resources))
    }

    entitlementCheck andThen {
      case Success(rs) =>
        logging.debug(this, "authorized")
      case Failure(r: RejectRequest) =>
        logging.debug(this, s"not authorized: $r")
      case Failure(t) =>
        logging.error(this, s"failed while checking entitlement: ${t.getMessage}")
    }
  }

  /**
   * NOTE: explicit grants do not work with package bindings because this method does not allow
   * for a continuation to check that both the binding and the references package are both either
   * implicitly or explicitly granted. Instead, the given resource set should include both the binding
   * and the referenced package.
   */
  protected def checkPrivilege(user: Identity, right: Privilege, resources: Set[Resource])(
    implicit transid: TransactionId): Future[Set[(Resource, Boolean)]] = {
    // check the default namespace first, bypassing additional checks if permitted
    val defaultNamespaces = Set(user.namespace.asString)
    implicit val es = this

    Future.sequence {
      resources.map { resource =>
        resource.collection.implicitRights(user, defaultNamespaces, right, resource) flatMap {
          case true => Future.successful(resource -> true)
          case false =>
            logging.debug(this, "checking explicit grants")
            entitled(user.subject, right, resource).flatMap(b => Future.successful(resource -> b))
        }
      }
    }
  }

  /**
   * Limits activations if the system is overloaded.
   *
   * @param right the privilege, if ACTIVATE then check quota else return None
   * @return future completing successfully if system is not overloaded else failing with a rejection
   */
  protected def checkSystemOverload(right: Privilege)(implicit transid: TransactionId): Future[Unit] = {
    concurrentInvokeThrottler.isOverloaded.flatMap { isOverloaded =>
      val systemOverload = right == ACTIVATE && isOverloaded
      if (systemOverload) {
        logging.error(this, "system is overloaded")
        Future.failed(RejectRequest(TooManyRequests, systemOverloaded))
      } else Future.successful(())
    }
  }

  /**
   * Limits activations if subject exceeds their own limits.
   * If the requested right is an activation, the set of resources must contain an activation of an action or filter to be throttled.
   * While it is possible for the set of resources to contain more than one action or trigger, the plurality is ignored and treated
   * as one activation since these should originate from a single macro resources (e.g., a sequence).
   *
   * @param user the subject identity to check rights for
   * @param right the privilege, if ACTIVATE then check quota else return None
   * @param resources the set of resources must contain at least one resource that can be activated else return None
   * @return future completing successfully if user is below limits else failing with a rejection
   */
  private def checkUserThrottle(user: Identity, right: Privilege, resources: Set[Resource])(
    implicit transid: TransactionId): Future[Unit] = {
    if (right == ACTIVATE) {
      if (resources.exists(_.collection.path == Collection.ACTIONS)) {
        checkThrottleOverload(Future.successful(invokeRateThrottler.check(user)), user)
      } else if (resources.exists(_.collection.path == Collection.TRIGGERS)) {
        checkThrottleOverload(Future.successful(triggerRateThrottler.check(user)), user)
      } else Future.successful(())
    } else Future.successful(())
  }

  /**
   * Limits activations if subject exceeds limit of concurrent invocations.
   * If the requested right is an activation, the set of resources must contain an activation of an action to be throttled.
   * While it is possible for the set of resources to contain more than one action, the plurality is ignored and treated
   * as one activation since these should originate from a single macro resources (e.g., a sequence).
   *
   * @param user the subject identity to check rights for
   * @param right the privilege, if ACTIVATE then check quota else return None
   * @param resources the set of resources must contain at least one resource that can be activated else return None
   * @return future completing successfully if user is below limits else failing with a rejection
   */
  private def checkConcurrentUserThrottle(user: Identity, right: Privilege, resources: Set[Resource])(
    implicit transid: TransactionId): Future[Unit] = {
    if (right == ACTIVATE && resources.exists(_.collection.path == Collection.ACTIONS)) {
      checkThrottleOverload(concurrentInvokeThrottler.check(user), user)
    } else Future.successful(())
  }

  private def checkThrottleOverload(throttle: Future[RateLimit], user: Identity)(
    implicit transid: TransactionId): Future[Unit] = {
    throttle.flatMap { limit =>
      if (limit.ok) {
        Future.successful(())
      } else {
        logging.info(this, s"'${user.namespace}' has exceeded its throttle limit, ${limit.errorMsg}")
        Future.failed(RejectRequest(TooManyRequests, limit.errorMsg))
      }
    }
  }
}

/**
 * A trait to consolidate gathering of referenced entities for various types.
 * Current entities that refer to others: action sequences, rules, and package bindings.
 */
trait ReferencedEntities {

  /**
   * Gathers referenced resources for types knows to refer to others.
   * This is usually done on a PUT request, hence the types are not one of the
   * canonical datastore types. Hence this method accepts Any reference but is
   * only defined for WhiskPackagePut, WhiskRulePut, and SequenceExec.
   *
   * It is plausible to lift these disambiguation below to a new trait which is
   * implemented by these types - however this will require exposing the Resource
   * type outside of the controller which is not yet desirable (although this could
   * cause further consolidation of the WhiskEntity and Resource types).
   *
   * @return Set of Resource instances if there are referenced entities.
   */
  def referencedEntities(reference: Any): Set[Resource] = {
    reference match {
      case WhiskPackagePut(Some(binding), _, _, _, _) =>
        Set(Resource(binding.namespace.toPath, Collection(Collection.PACKAGES), Some(binding.name.asString)))
      case r: WhiskRulePut =>
        val triggerResource = r.trigger.map { t =>
          Resource(t.path, Collection(Collection.TRIGGERS), Some(t.name.asString))
        }
        val actionResource = r.action map { a =>
          Resource(a.path, Collection(Collection.ACTIONS), Some(a.name.asString))
        }
        Set(triggerResource, actionResource).flatten
      case e: SequenceExec =>
        e.components.map { c =>
          Resource(c.path, Collection(Collection.ACTIONS), Some(c.name.asString))
        }.toSet
      case e: SequenceExecMetaData =>
        e.components.map { c =>
          Resource(c.path, Collection(Collection.ACTIONS), Some(c.name.asString))
        }.toSet
      case _ => Set()
    }
  }
}
