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

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.NotFound
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.routing.Directive.pimpApply
import spray.routing.RequestContext
import spray.routing.directives.OnCompleteFutureMagnet.apply
import spray.routing.directives.ParamDefMagnet.apply
import whisk.common.TransactionId
import whisk.core.database.DocumentConflictException
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.Collection
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.ReducedRule
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskRulePut
import whisk.core.entity.WhiskTrigger
import whisk.core.entity.types.EntityStore
import whisk.core.entity.Identity
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages._

/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the rules API.
 */
object WhiskRulesApi {
    def requiredProperties = WhiskServices.requiredProperties ++
        WhiskEntityStore.requiredProperties
}

/** A trait implementing the rules API */
trait WhiskRulesApi extends WhiskCollectionAPI {
    services: WhiskServices =>

    protected override val collection = Collection(Collection.RULES)

    /** An actor system for timed based futures */
    protected implicit val actorSystem: ActorSystem

    /** Database service to CRUD rules */
    protected val entityStore: EntityStore

    /** Path to Rules REST API */
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
    override def create(user: Identity, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskRulePut]) { content =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                putEntity(WhiskRule, entityStore, docid, overwrite, update(content) _, () => { create(content, namespace, name) },
                    postProcess = Some { rule: WhiskRule =>
                        completeAsRuleResponse(rule, Status.ACTIVE)
                    })
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
    override def activate(user: Identity, namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        extractStatusRequest { requestedState =>
            val docid = DocId(WhiskEntity.qualifiedName(namespace, name))

            getEntity(WhiskRule, entityStore, docid, Some {
                rule: WhiskRule =>
                    val tid = DocId(WhiskEntity.qualifiedName(namespace, rule.trigger))
                    val ruleName = EntityPath(WhiskEntity.qualifiedName(rule.namespace, rule.name))

                    val changeStatus = getTrigger(tid) map { trigger =>
                        getStatus(trigger, ruleName)
                    } flatMap { oldStatus =>
                        if (requestedState != oldStatus) {
                            info(this, s"[POST] rule state change initiated: ${oldStatus} -> $requestedState")
                            Future successful requestedState
                        } else {
                            info(this, s"[POST] rule state will not be changed, the requested state is the same as the old state: ${oldStatus} -> $requestedState")
                            Future failed { IgnoredRuleActivation(requestedState == oldStatus) }
                        }
                    } flatMap {
                        case (newStatus) =>
                            info(this, s"[POST] attempting to set rule state to: ${newStatus}")

                            val actionName = EntityPath(WhiskEntity.qualifiedName(namespace, rule.action))

                            WhiskTrigger.get(entityStore, tid) flatMap { trigger =>
                                val newTrigger = trigger.removeRule(ruleName)
                                val triggerLink = ReducedRule(actionName, newStatus)
                                WhiskTrigger.put(entityStore, newTrigger.addRule(ruleName, triggerLink))
                            }
                    }

                    onComplete(changeStatus) {
                        case Success(response) =>
                            complete(OK)
                        case Failure(t) => t match {
                            case _: DocumentConflictException =>
                                info(this, s"[POST] rule update conflict")
                                terminate(Conflict, conflictMessage)
                            case IgnoredRuleActivation(ok) =>
                                info(this, s"[POST] rule update ignored")
                                if (ok) complete(OK) else terminate(Conflict)
                            case _: NoDocumentException =>
                                info(this, s"[POST] the trigger attached to the rule doesn't exist")
                                terminate(NotFound, "Only rules with existing triggers can be activated")
                            case _: Throwable =>
                                error(this, s"[POST] rule update failed: ${t.getMessage}")
                                terminate(InternalServerError, t.getMessage)
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
    override def remove(namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        deleteEntity(WhiskRule, entityStore, docid, (r: WhiskRule) => {
            val tid = DocId(WhiskEntity.qualifiedName(namespace, r.trigger))
            val ruleName = EntityPath(WhiskEntity.qualifiedName(r.namespace, r.name))
            getTrigger(tid) map { trigger =>
                (getStatus(trigger, ruleName), trigger)
            } flatMap {
                case (status, triggerOpt) =>
                    if (status == Status.INACTIVE) {
                        triggerOpt map { trigger =>
                            WhiskTrigger.put(entityStore, trigger.removeRule(ruleName)) map { _ => true }
                        } getOrElse Future.successful(true)
                    } else Future failed {
                        RejectRequest(Conflict, s"rule status is '${status}', must be '${Status.INACTIVE}' to delete")
                    }
            }
        }, postProcess = Some { rule: WhiskRule =>
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
    override def fetch(namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        getEntity(WhiskRule, entityStore, docid, Some { rule: WhiskRule =>
            val ruleName = WhiskEntity.qualifiedName(namespace, name)
            val triggerName = WhiskEntity.qualifiedName(namespace, rule.trigger)
            val tid = DocId(triggerName)

            val getRuleWithStatus = getTrigger(tid) map { trigger =>
                getStatus(trigger, EntityPath(ruleName))
            } map { status =>
                rule.withStatus(status)
            }

            onComplete(getRuleWithStatus) {
                case Success(r) => complete(OK, r)
                case Failure(t) => terminate(InternalServerError, t.getMessage)
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
    override def list(namespace: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId) = {
        // for consistency, all the collections should support the same list API
        // but because supporting docs on actions is difficult, the API does not
        // offer an option to fetch entities with full docs yet; see comment in
        // Actions API for more.
        val docs = false
        parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false) {
            (skip, limit, count) =>
                listEntities {
                    WhiskRule.listCollectionInNamespace(entityStore, namespace, skip, limit, docs) map {
                        list =>
                            val rules = if (docs) {
                                list.right.get map { WhiskRule.serdes.write(_) }
                            } else list.left.get
                            FilterEntityList.filter(rules, excludePrivate)
                    }
                }
        }
    }

    /** Creates a WhiskRule from PUT content, generating default values where necessary. */
    private def create(content: WhiskRulePut, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId): Future[WhiskRule] = {
        val predicate = Promise[WhiskRule]

        if (content.trigger.isDefined && content.action.isDefined) {
            val ruleName = WhiskEntity.qualifiedName(namespace, name)
            val triggerName = WhiskEntity.qualifiedName(namespace, content.trigger.get)
            val actionName = WhiskEntity.qualifiedName(namespace, content.action.get)

            val tid = DocId(triggerName)
            val aid = DocId(actionName)

            checkTriggerAndActionExist(tid, aid) onComplete {
                case Success((trigger, action)) =>
                    val rule = WhiskRule(
                        namespace,
                        name,
                        content.trigger.get,
                        content.action.get,
                        content.version getOrElse SemVer(),
                        content.publish getOrElse false,
                        content.annotations getOrElse Parameters())

                    val triggerLink = ReducedRule(EntityPath(actionName), Status.ACTIVE)
                    val saveRule = WhiskTrigger.put(entityStore, trigger.addRule(EntityPath(ruleName), triggerLink)) onComplete {
                        case Success(_) => predicate.success(rule)
                        case Failure(t) => predicate.failure(t)
                    }

                case Failure(t) => predicate.failure { RejectRequest(BadRequest, t) }
            }
        } else predicate.failure { RejectRequest(BadRequest, "rule requires a valid trigger and a valid action") }

        predicate.future
    }

    /** Updates a WhiskTrigger from PUT content, merging old trigger where necessary. */
    private def update(content: WhiskRulePut)(rule: WhiskRule)(implicit transid: TransactionId): Future[WhiskRule] = {
        val predicate = Promise[WhiskRule]
        val ruleName = WhiskEntity.qualifiedName(rule.namespace, rule.name)
        val oldTriggerName = WhiskEntity.qualifiedName(rule.namespace, rule.trigger)
        val oldTid = DocId(oldTriggerName)

        getTrigger(oldTid) map { trigger =>
            (getStatus(trigger, EntityPath(ruleName)), trigger)
        } map {
            case (status, oldTriggerOpt) =>
                if (status == Status.INACTIVE) {
                    val newTriggerEntity = content.trigger getOrElse rule.trigger
                    val newTriggerName = WhiskEntity.qualifiedName(rule.namespace, newTriggerEntity)
                    val tid = DocId(newTriggerName)

                    val actionEntity = content.action getOrElse rule.action
                    val actionName = WhiskEntity.qualifiedName(rule.namespace, actionEntity)
                    val aid = DocId(actionName)

                    checkTriggerAndActionExist(tid, aid) onComplete {
                        case Success((newTrigger, newAction)) => {
                            val r = WhiskRule(
                                rule.namespace,
                                rule.name,
                                newTriggerEntity,
                                actionEntity,
                                content.version getOrElse rule.version.upPatch,
                                content.publish getOrElse rule.publish,
                                content.annotations getOrElse rule.annotations).
                                revision[WhiskRule](rule.docinfo.rev)

                            // Deletes reference from the old trigger iff it is different from the new one
                            val deleteOldLink = for {
                                isDifferentTrigger <- content.trigger.filter(_ => newTriggerName != oldTriggerName)
                                oldTrigger <- oldTriggerOpt
                            } yield {
                                WhiskTrigger.put(entityStore, oldTrigger.removeRule(EntityPath(ruleName)))
                            }

                            val triggerLink = ReducedRule(EntityPath(actionName), Status.INACTIVE)
                            val update = WhiskTrigger.put(entityStore, newTrigger.addRule(EntityPath(ruleName), triggerLink))

                            Future.sequence(Seq(deleteOldLink.getOrElse(Future.successful(true)), update)) onComplete {
                                case Success(_) => predicate.success(r)
                                case Failure(t) => predicate.failure(t)
                            }

                        }
                        case Failure(t) => predicate.failure { RejectRequest(BadRequest, t) }
                    }
                } else predicate.failure { RejectRequest(Conflict, s"rule may not be updated while status is ${status}") }

        }
        predicate.future
    }

    /**
     * Gets a WhiskTrigger defined by the given DocInfo. Gracefully falls back to None iff the trigger is not found.
     *
     * @param tid DocInfo defining the trigger to get
     * @return a WhiskTrigger iff found, else None
     */
    private def getTrigger(tid: DocId)(implicit transid: TransactionId): Future[Option[WhiskTrigger]] = {
        WhiskTrigger.get(entityStore, tid) map {
            trigger => Some(trigger)
        } recover {
            case _: NoDocumentException => None
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
    private def getStatus(triggerOpt: Option[WhiskTrigger], ruleName: EntityPath)(implicit transid: TransactionId): Status = {
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
    private def completeAsRuleResponse(rule: WhiskRule, status: Status = Status.INACTIVE): RequestContext => Unit = {
        complete(OK, rule.withStatus(status))
    }

    /**
     * Checks if trigger and action are valid documents (that is, they exist) in the datastore.
     *
     * @param trigger the trigger id
     * @param action the action id
     * @return promise that completes to true iff both documents exist
     */
    private def checkTriggerAndActionExist(trigger: DocId, action: DocId)(implicit transid: TransactionId): Future[(WhiskTrigger, WhiskAction)] = {
        val validTrigger = Promise[WhiskTrigger]
        val validAction = Promise[WhiskAction]

        val validateTrigger = WhiskTrigger.get(entityStore, trigger) onComplete {
            case Success(trigger) => validTrigger.success(trigger)
            case Failure(t) => t match {
                case _: NoDocumentException => validTrigger.failure(new NoDocumentException(s"trigger $trigger does not exist"))
                case _                      => validTrigger.failure(t)
            }
        }

        val validateAction = WhiskAction.get(entityStore, action) onComplete {
            case Success(action) => validAction.success(action)
            case Failure(t) => t match {
                case _: NoDocumentException => validAction.failure(new NoDocumentException(s"action $action does not exist"))
                case _                      => validAction.failure(t)
            }
        }

        val entities = for {
            triggerExists <- validTrigger.future
            actionExists <- validAction.future
        } yield (triggerExists, actionExists)
        entities
    }

    /** Extracts status request subject to allowed values. */
    private def extractStatusRequest = {
        implicit val statusSerdes = Status.serdesRestricted
        entity(as[Status])
    }
}

private case class IgnoredRuleActivation(noop: Boolean) extends Throwable
