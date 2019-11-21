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
import scala.collection.immutable.Set
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure
import scala.util.Success
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.Forbidden
import akka.http.scaladsl.model.StatusCodes.TooManyRequests
import org.apache.openwhisk.core.entitlement.Privilege.ACTIVATE
import org.apache.openwhisk.core.entitlement.Privilege.REJECT
import org.apache.openwhisk.common.{Logging, TransactionId, UserEvents}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.{EventMessage, Metric}
import org.apache.openwhisk.core.controller.RejectRequest
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.loadBalancer.{LoadBalancer, ShardingContainerPoolBalancer}
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.core.connector.MessagingProvider
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.spi.Spi

object types {
  type Entitlements = TrieMap[(Subject, String), Set[Privilege]]
}

/**
 * Resource is a type that encapsulates details relevant to identify a specific resource.
 * It may be an entire collection, or an element in a collection.
 *
 * @param namespace  the namespace the resource resides in
 * @param collection the collection (e.g., actions, triggers) identifying a resource
 * @param entity     an optional entity name that identifies a specific item in the collection
 * @param env        an optional environment to bind to the resource during an activation
 */
protected[core] case class Resource(namespace: EntityPath,
                                    collection: Collection,
                                    entity: Option[String],
                                    env: Option[Parameters] = None) {
  def parent: String = collection.path + EntityPath.PATHSEP + namespace

  def id: String = parent + entity.map(EntityPath.PATHSEP + _).getOrElse("")

  def fqname: String = namespace.asString + entity.map(EntityPath.PATHSEP + _).getOrElse("")

  override def toString: String = id
}

trait EntitlementSpiProvider extends Spi {
  def instance(config: WhiskConfig, loadBalancer: LoadBalancer, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging): EntitlementProvider
}

protected[core] object EntitlementProvider {

  val requiredProperties = Map(
    WhiskConfig.actionInvokePerMinuteLimit -> null,
    WhiskConfig.actionInvokeConcurrentLimit -> null,
    WhiskConfig.triggerFirePerMinuteLimit -> null)
}

/**
 * A trait that implements entitlements to resources. It performs checks for CRUD and Acivation requests.
 * This is where enforcement of activation quotas takes place, in additional to basic authorization.
 */
protected[core] abstract class EntitlementProvider(
  config: WhiskConfig,
  loadBalancer: LoadBalancer,
  controllerInstance: ControllerInstanceId)(implicit actorSystem: ActorSystem, logging: Logging) {

  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  /**
   * Allows 20% of additional requests on top of the limit to mitigate possible unfair round-robin loadbalancing between
   * controllers
   */
  private def overcommit(clusterSize: Int) = if (clusterSize > 1) 1.2 else 1

  private def dilateLimit(limit: Int): Int = Math.ceil(limit.toDouble * overcommit(loadBalancer.clusterSize)).toInt

  /**
   * Calculates a possibly dilated limit relative to the current user.
   *
   * @param defaultLimit the default limit across the whole system
   * @param user         the user to apply that limit to
   * @return a calculated limit
   */
  private def calculateLimit(defaultLimit: Int, overrideLimit: Identity => Option[Int])(user: Identity): Int = {
    val absoluteLimit = overrideLimit(user).getOrElse(defaultLimit)
    dilateLimit(absoluteLimit)
  }

  /**
   * Calculates a limit which applies only to this instance individually.
   *
   * The state needed to correctly check this limit is not shared between all instances, which want to check that
   * limit, so it needs to be divided between the parties who want to perform that check.
   *
   * @param defaultLimit the default limit across the whole system
   * @param user         the user to apply that limit to
   * @return a calculated limit
   */
  private def calculateIndividualLimit(defaultLimit: Int, overrideLimit: Identity => Option[Int])(
    user: Identity): Int = {
    val limit = calculateLimit(defaultLimit, overrideLimit)(user)
    if (limit == 0) {
      0
    } else {
      // Edge case: Iff the divided limit is < 1 no loadbalancer would allow an action to be executed, thus we range
      // bound to at least 1
      (limit / loadBalancer.clusterSize).max(1)
    }
  }

  private val invokeRateThrottler =
    new RateThrottler(
      "actions per minute",
      calculateIndividualLimit(config.actionInvokePerMinuteLimit.toInt, _.limits.invocationsPerMinute))
  private val triggerRateThrottler =
    new RateThrottler(
      "triggers per minute",
      calculateIndividualLimit(config.triggerFirePerMinuteLimit.toInt, _.limits.firesPerMinute))

  private val activationThrottleCalculator = loadBalancer match {
    // This loadbalancer applies sharding and does not share any state
    case _: ShardingContainerPoolBalancer => calculateIndividualLimit _
    // Activation relevant data is shared by all other loadbalancers
    case _ => calculateLimit _
  }
  private val concurrentInvokeThrottler =
    new ActivationThrottler(
      loadBalancer,
      activationThrottleCalculator(config.actionInvokeConcurrentLimit.toInt, _.limits.concurrentInvocations))

  private val messagingProvider = SpiLoader.get[MessagingProvider]
  private val eventProducer = messagingProvider.getProducer(this.config)

  /**
   * Grants a subject the right to access a resources.
   *
   * @param user     the subject to grant right to
   * @param right    the privilege to grant the subject
   * @param resource the resource to grant the subject access to
   * @return a promise that completes with true iff the subject is granted the right to access the requested resource
   */
  protected[core] def grant(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Revokes a subject the right to access a resources.
   *
   * @param user     the subject to revoke right to
   * @param right    the privilege to revoke the subject
   * @param resource the resource to revoke the subject access to
   * @return a promise that completes with true iff the subject is revoked the right to access the requested resource
   */
  protected[core] def revoke(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Checks if a subject is entitled to a resource because it was granted the right explicitly.
   *
   * @param user     the subject to check rights for
   * @param right    the privilege the subject is requesting
   * @param resource the resource the subject requests access to
   * @return a promise that completes with true iff the subject is permitted to access the request resource
   */
  protected def entitled(user: Identity, right: Privilege, resource: Resource)(
    implicit transid: TransactionId): Future[Boolean]

  /**
   * Checks action activation rate throttles for an identity.
   *
   * @param user the identity to check rate throttles for
   * @return a promise that completes with success iff the user is within their activation quota
   */
  protected[core] def checkThrottles(user: Identity)(implicit transid: TransactionId): Future[Unit] = {

    logging.debug(this, s"checking user '${user.subject}' has not exceeded activation quota")
    checkThrottleOverload(Future.successful(invokeRateThrottler.check(user)), user)
      .flatMap(_ => checkThrottleOverload(concurrentInvokeThrottler.check(user), user))
  }

  private val kindRestrictor = {
    import pureconfig._
    import pureconfig.generic.auto._
    import org.apache.openwhisk.core.ConfigKeys
    case class AllowedKinds(whitelist: Option[Set[String]] = None)
    val allowedKinds = loadConfigOrThrow[AllowedKinds](ConfigKeys.runtimes)
    KindRestrictor(allowedKinds.whitelist)
  }

  /**
   * Checks if an action kind is allowed for a given subject.
   *
   * @param user the identity to check for restrictions
   * @param exec the action executable details
   * @return a promise that completes with success iff the user's action kind is allowed
   */
  protected[core] def check(user: Identity, exec: Option[Exec])(implicit transid: TransactionId): Future[Unit] = {
    exec
      .map {
        case e =>
          if (kindRestrictor.check(user, e.kind)) {
            Future.successful(())
          } else {
            Future.failed(
              RejectRequest(Forbidden, Some(ErrorResponse(Messages.notAuthorizedtoActionKind(e.kind), transid))))
          }
      }
      .getOrElse(Future.successful(()))
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
   * @param user     the subject to check rights for
   * @param right    the privilege the subject is requesting (applies to the entire set of resources)
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
   * @param user       the subject identity to check rights for
   * @param right      the privilege the subject is requesting (applies to the entire set of resources)
   * @param resources  the set of resources the subject requests access to
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
            checkUserThrottle(user, right, resources)
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
    val defaultNamespaces = Set(user.namespace.name.asString)
    implicit val es: EntitlementProvider = this

    Future.sequence {
      resources.map { resource =>
        resource.collection.implicitRights(user, defaultNamespaces, right, resource) flatMap {
          case true => Future.successful(resource -> true)
          case false =>
            logging.debug(this, "checking explicit grants")
            entitled(user, right, resource).flatMap(b => Future.successful(resource -> b))
        }
      }
    }
  }

  /**
   * Limits activations if subject exceeds their own limits.
   * If the requested right is an activation, the set of resources must contain an activation of an action or filter to be throttled.
   * While it is possible for the set of resources to contain more than one action or trigger, the plurality is ignored and treated
   * as one activation since these should originate from a single macro resources (e.g., a sequence).
   *
   * @param user      the subject identity to check rights for
   * @param right     the privilege, if ACTIVATE then check quota else return None
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
   * @param user      the subject identity to check rights for
   * @param right     the privilege, if ACTIVATE then check quota else return None
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
      val userId = user.namespace.uuid
      if (limit.ok) {
        limit match {
          case c: ConcurrentRateLimit => {
            val metric =
              Metric("ConcurrentInvocations", c.count + 1)
            UserEvents.send(
              eventProducer,
              EventMessage(
                s"controller${controllerInstance.asString}",
                metric,
                user.subject,
                user.namespace.name.toString,
                userId,
                metric.typeName))
          }
          case _ => // ignore
        }
        Future.successful(())
      } else {
        logging.info(this, s"'${user.namespace.name}' has exceeded its throttle limit, ${limit.errorMsg}")
        val metric = Metric(limit.limitName, 1)
        UserEvents.send(
          eventProducer,
          EventMessage(
            s"controller${controllerInstance.asString}",
            metric,
            user.subject,
            user.namespace.name.toString,
            userId,
            metric.typeName))
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
      case _ => Set.empty
    }
  }
}
