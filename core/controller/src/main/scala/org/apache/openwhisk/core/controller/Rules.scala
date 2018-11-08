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

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.StandardRoute
import akka.http.scaladsl.unmarshalling.Unmarshaller
import spray.json.DeserializationException
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.RestApiCommons.{ListLimit, ListSkip}
import org.apache.openwhisk.core.database.{CacheChangeNotification, DocumentConflictException, NoDocumentException}
import org.apache.openwhisk.core.entitlement.{Collection, Privilege, ReferencedEntities}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages._

import scala.concurrent.Future
import scala.util.{Failure, Success}

/** A trait implementing the rules API */
trait WhiskRulesApi extends WhiskCollectionAPI with ReferencedEntities {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.RULES)

  /** An actor system for timed based futures. */
  protected implicit val actorSystem: ActorSystem

  /** Database service to CRUD rules. */
  protected val entityStore: EntityStore

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Path to Rules REST API. */
  protected val rulesPath = "rules"

  /**
   * Creates or updates rule if it already exists. The PUT content is deserialized into a WhiskRulePut
   * which is a subset of WhiskRule (it eschews the namespace, entity name and status since the former
   * are derived from the authenticated user and the URI and the status is managed automatically).
   * The WhiskRulePut is merged with the existing WhiskRule in the datastore, overriding old values
   * with new values that are defined. Any values not defined in the PUT content are replaced with
   * old values.
   *
   * The rule will not update if the status of the entity in the datastore is not INACTIVE. It rejects
   * such requests with Conflict.
   *
   * The create/update is also guarded by a predicate that confirm the trigger and action are valid.
   * Otherwise rejects the request with Bad Request and an appropriate message. It is true that the
   * trigger/action may be deleted after creation but at the very least confirming dependences here
   * prevents use errors where a rule is created with an invalid trigger/action which then fails
   * testing (fire a trigger and expect an action activation to occur).
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskRule as JSON
   * - 400 Bad Request
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def create(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    parameter('overwrite ? false) { overwrite =>
      entity(as[WhiskRulePut]) { content =>
        val request = content.resolve(entityName.namespace)
        onComplete(entitlementProvider.check(user, Privilege.READ, referencedEntities(request))) {
          case Success(_) =>
            putEntity(
              WhiskRule,
              entityStore,
              entityName.toDocId,
              overwrite,
              update(request) _,
              () => {
                create(request, entityName)
              },
              postProcess = Some { rule: WhiskRule =>
                if (overwrite == true) {
                  val getRuleWithStatus = getTrigger(rule.trigger) map { trigger =>
                    getStatus(trigger, FullyQualifiedEntityName(rule.namespace, rule.name))
                  } map { status =>
                    rule.withStatus(status)
                  }

                  onComplete(getRuleWithStatus) {
                    case Success(r) => completeAsRuleResponse(rule, r.status)
                    case Failure(t) => terminate(InternalServerError)
                  }
                } else {
                  completeAsRuleResponse(rule, Status.ACTIVE)
                }
              })
          case Failure(f) =>
            handleEntitlementFailure(f)
        }
      }
    }
  }

  /**
   * Toggles rule status from enabled -> disabled and vice versa. The action are not confirmed
   * to still exist. This is deferred to trigger activation which will fail to post activations
   * for non-existent actions.
   *
   * Responses are one of (Code, Message)
   * - 200 OK rule in desired state
   * - 202 Accepted rule state change accepted
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def activate(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    extractStatusRequest { requestedState =>
      val docid = entityName.toDocId

      getEntity(WhiskRule.get(entityStore, docid), Some {
        rule: WhiskRule =>
          val ruleName = rule.fullyQualifiedName(false)

          val changeStatus = getTrigger(rule.trigger) map { trigger =>
            getStatus(trigger, ruleName)
          } flatMap {
            oldStatus =>
              if (requestedState != oldStatus) {
                logging.debug(this, s"[POST] rule state change initiated: ${oldStatus} -> $requestedState")
                Future successful requestedState
              } else {
                logging.debug(
                  this,
                  s"[POST] rule state will not be changed, the requested state is the same as the old state: ${oldStatus} -> $requestedState")
                Future failed { IgnoredRuleActivation(requestedState == oldStatus) }
              }
          } flatMap {
            case (newStatus) =>
              logging.debug(this, s"[POST] attempting to set rule state to: ${newStatus}")
              WhiskTrigger.get(entityStore, rule.trigger.toDocId) flatMap { trigger =>
                val newTrigger = trigger.removeRule(ruleName)
                val triggerLink = ReducedRule(rule.action, newStatus)
                WhiskTrigger.put(entityStore, newTrigger.addRule(ruleName, triggerLink), Some(trigger))
              }
          }

          onComplete(changeStatus) {
            case Success(response) =>
              complete(OK)
            case Failure(t) =>
              t match {
                case _: DocumentConflictException =>
                  logging.debug(this, s"[POST] rule update conflict")
                  terminate(Conflict, conflictMessage)
                case IgnoredRuleActivation(ok) =>
                  logging.debug(this, s"[POST] rule update ignored")
                  if (ok) complete(OK) else terminate(Conflict)
                case _: NoDocumentException =>
                  logging.debug(this, s"[POST] the trigger attached to the rule doesn't exist")
                  terminate(NotFound, "Only rules with existing triggers can be activated")
                case _: DeserializationException =>
                  logging.error(this, s"[POST] rule update failed: ${t.getMessage}")
                  terminate(InternalServerError, corruptedEntity)
                case _: Throwable =>
                  logging.error(this, s"[POST] rule update failed: ${t.getMessage}")
                  terminate(InternalServerError)
              }
          }
      })
    }
  }

  /**
   * Deletes rule iff rule is inactive.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskRule as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    deleteEntity(
      WhiskRule,
      entityStore,
      entityName.toDocId,
      (r: WhiskRule) => {
        val ruleName = FullyQualifiedEntityName(r.namespace, r.name)
        getTrigger(r.trigger) map { trigger =>
          (getStatus(trigger, ruleName), trigger)
        } flatMap {
          case (status, triggerOpt) =>
            triggerOpt map { trigger =>
              WhiskTrigger.put(entityStore, trigger.removeRule(ruleName), triggerOpt) map { _ =>
                {}
              }
            } getOrElse Future.successful({})
        }
      },
      postProcess = Some { rule: WhiskRule =>
        completeAsRuleResponse(rule, Status.INACTIVE)
      })
  }

  /**
   * Gets rule. The rule name is prefixed with the namespace to create the primary index key.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskRule has JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  override def fetch(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    getEntity(
      WhiskRule.get(entityStore, entityName.toDocId),
      Some { rule: WhiskRule =>
        val getRuleWithStatus = getTrigger(rule.trigger) map { trigger =>
          getStatus(trigger, entityName)
        } map { status =>
          rule.withStatus(status)
        }

        onComplete(getRuleWithStatus) {
          case Success(r) => complete(OK, r)
          case Failure(t) => terminate(InternalServerError)
        }
      })
  }

  /**
   * Gets all rules in namespace.
   *
   * Responses are one of (Code, Message)
   * - 200 [] or [WhiskRule as JSON]
   * - 500 Internal Server Error
   */
  override def list(user: Identity, namespace: EntityPath)(implicit transid: TransactionId) = {
    parameter(
      'skip.as[ListSkip] ? ListSkip(collection.defaultListSkip),
      'limit.as[ListLimit] ? ListLimit(collection.defaultListLimit),
      'count ? false) { (skip, limit, count) =>
      if (!count) {
        listEntities {
          WhiskRule.listCollectionInNamespace(entityStore, namespace, skip.n, limit.n, includeDocs = true) map { list =>
            list.fold((js) => js, (rls) => rls.map(WhiskRule.serdes.write(_)))
          }
        }
      } else {
        countEntities {
          WhiskRule.countCollectionInNamespace(entityStore, namespace, skip.n)
        }
      }
    }
  }

  /** Creates a WhiskRule from PUT content, generating default values where necessary. */
  private def create(content: WhiskRulePut, ruleName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskRule] = {
    if (content.trigger.isDefined && content.action.isDefined) {
      val triggerName = content.trigger.get
      val actionName = content.action.get

      checkTriggerAndActionExist(triggerName, actionName) recoverWith {
        case t => Future.failed(RejectRequest(BadRequest, t))
      } flatMap {
        case (trigger, action) =>
          val rule = WhiskRule(
            ruleName.path,
            ruleName.name,
            content.trigger.get,
            content.action.get,
            content.version getOrElse SemVer(),
            content.publish getOrElse false,
            content.annotations getOrElse Parameters())

          val triggerLink = ReducedRule(actionName, Status.ACTIVE)
          logging.debug(this, s"about to put ${trigger.addRule(ruleName, triggerLink)}")
          WhiskTrigger.put(entityStore, trigger.addRule(ruleName, triggerLink), old = None) map { _ =>
            rule
          }
      }
    } else Future.failed(RejectRequest(BadRequest, "rule requires a valid trigger and a valid action"))
  }

  /** Updates a WhiskTrigger from PUT content, merging old trigger where necessary. */
  private def update(content: WhiskRulePut)(rule: WhiskRule)(implicit transid: TransactionId): Future[WhiskRule] = {
    val ruleName = FullyQualifiedEntityName(rule.namespace, rule.name)
    val oldTriggerName = rule.trigger

    getTrigger(oldTriggerName) flatMap { oldTriggerOpt =>
      val status = getStatus(oldTriggerOpt, ruleName)
      val newTriggerEntity = content.trigger getOrElse rule.trigger
      val newTriggerName = newTriggerEntity

      val actionEntity = content.action getOrElse rule.action
      val actionName = actionEntity

      checkTriggerAndActionExist(newTriggerName, actionName) recoverWith {
        case t => Future.failed(RejectRequest(BadRequest, t))
      } flatMap {
        case (newTrigger, newAction) =>
          val r = WhiskRule(
            rule.namespace,
            rule.name,
            newTriggerEntity,
            actionEntity,
            content.version getOrElse rule.version.upPatch,
            content.publish getOrElse rule.publish,
            content.annotations getOrElse rule.annotations).revision[WhiskRule](rule.docinfo.rev)

          // Deletes reference from the old trigger iff it is different from the new one
          val deleteOldLink = for {
            isDifferentTrigger <- content.trigger.filter(_ => newTriggerName != oldTriggerName)
            oldTrigger <- oldTriggerOpt
          } yield {
            WhiskTrigger.put(entityStore, oldTrigger.removeRule(ruleName), oldTriggerOpt)
          }

          val triggerLink = ReducedRule(actionName, status)
          val update = WhiskTrigger.put(entityStore, newTrigger.addRule(ruleName, triggerLink), oldTriggerOpt)
          Future.sequence(Seq(deleteOldLink.getOrElse(Future.successful(true)), update)).map(_ => r)
      }
    }
  }

  /**
   * Gets a WhiskTrigger defined by the given DocInfo. Gracefully falls back to None iff the trigger is not found.
   *
   * @param tid DocInfo defining the trigger to get
   * @return a WhiskTrigger iff found, else None
   */
  private def getTrigger(t: FullyQualifiedEntityName)(implicit transid: TransactionId): Future[Option[WhiskTrigger]] = {
    WhiskTrigger.get(entityStore, t.toDocId) map { trigger =>
      Some(trigger)
    } recover {
      case _: NoDocumentException | DeserializationException(_, _, _) => None
    }
  }

  /**
   * Extracts the Status for the rule out of a WhiskTrigger that may be there. Falls back to INACTIVE if the trigger
   * could not be found or the rule being worked on has not yet been written into the trigger record.
   *
   * @param triggerOpt Option containing a WhiskTrigger
   * @param ruleName Namespace the name of the rule being worked on
   * @return Status of the rule
   */
  private def getStatus(triggerOpt: Option[WhiskTrigger], ruleName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Status = {
    val statusFromTrigger = for {
      trigger <- triggerOpt
      rules <- trigger.rules
      rule <- rules.get(ruleName)
    } yield {
      rule.status
    }
    statusFromTrigger getOrElse Status.INACTIVE
  }

  /**
   * Completes an HTTP request with a WhiskRule including the computed Status
   *
   * @param rule the rule to send
   * @param status the status to include in the response
   */
  private def completeAsRuleResponse(rule: WhiskRule, status: Status = Status.INACTIVE): StandardRoute = {
    complete(OK, rule.withStatus(status))
  }

  /**
   * Checks if trigger and action are valid documents (that is, they exist) in the datastore.
   *
   * @param trigger the trigger id
   * @param action the action id
   * @return future that completes with references trigger and action if they exist
   */
  private def checkTriggerAndActionExist(trigger: FullyQualifiedEntityName, action: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[(WhiskTrigger, WhiskActionMetaData)] = {

    for {
      triggerExists <- WhiskTrigger.get(entityStore, trigger.toDocId) recoverWith {
        case _: NoDocumentException =>
          Future.failed {
            new NoDocumentException(s"trigger ${trigger.qualifiedNameWithLeadingSlash} does not exist")
          }
        case _: DeserializationException =>
          Future.failed {
            new DeserializationException(s"trigger ${trigger.qualifiedNameWithLeadingSlash} is corrupted")
          }
      }

      actionExists <- WhiskAction.resolveAction(entityStore, action) flatMap { resolvedName =>
        WhiskActionMetaData.get(entityStore, resolvedName.toDocId)
      } recoverWith {
        case _: NoDocumentException =>
          Future.failed {
            new NoDocumentException(s"action ${action.qualifiedNameWithLeadingSlash} does not exist")
          }
        case _: DeserializationException =>
          Future.failed {
            new DeserializationException(s"action ${action.qualifiedNameWithLeadingSlash} is corrupted")
          }
      }
    } yield (triggerExists, actionExists)
  }

  /** Extracts status request subject to allowed values. */
  private def extractStatusRequest = {
    implicit val statusSerdes = Status.serdesRestricted
    entity(as[Status])
  }

  /** Custom unmarshaller for query parameters "limit" for "list" operations. */
  private implicit val stringToListLimit: Unmarshaller[String, ListLimit] = RestApiCommons.stringToListLimit(collection)

  /** Custom unmarshaller for query parameters "skip" for "list" operations. */
  private implicit val stringToListSkip: Unmarshaller[String, ListSkip] = RestApiCommons.stringToListSkip(collection)

}

private case class IgnoredRuleActivation(noop: Boolean) extends Throwable
