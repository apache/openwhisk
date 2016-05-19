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

import scala.Left
import scala.Right
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import org.lightcouch.NoDocumentException
import org.lightcouch.DocumentConflictException
import akka.actor.ActorSystem
import spray.client.pipelining.Post
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.httpx.unmarshalling.Deserializer
import spray.httpx.unmarshalling.MalformedContent
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.RootJsonFormat
import spray.json.JsString
import spray.json.JsObject
import spray.json.pimpString
import spray.json.pimpAny
import whisk.common.TransactionId
import whisk.core.entitlement.Collection
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.types.EntityStore
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskRulePut
import whisk.core.entity.WhiskTrigger
import whisk.utils.ExecutionContextFactory.FutureExtensions
import whisk.core.entity.Subject
import whisk.core.connector.LoadbalancerRequest
import whisk.core.entity.ActivationId
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.ActivationMessage.{ publish, ACTIVATOR }
import whisk.http.ErrorResponse.{ terminate }
import scala.language.postfixOps

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

    /** Duration to complete state change before timing out. */
    protected val ruleChangeTimeout = 30 seconds

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
    override def create(namespace: Namespace, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskRulePut]) { content =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                putEntity(WhiskRule, entityStore, docid, overwrite, update(content) _, () => { create(content, namespace, name) })
            }
        }
    }

    /**
     * Toggles rule status from enabled -> disabled and vice versa. The trigger or action are not confirmed
     * to still exist here. Instead, defer to a activator resource manager to deactivate rules that use an
     * invalid trigger or action.
     *
     * Responses are one of (Code, Message)
     * - 200 OK rule in desired state
     * - 202 Accepted rule state change accepted
     * - 404 Not Found
     * - 409 Conflict
     * - 500 Internal Server Error
     */
    override def activate(user: Subject, namespace: Namespace, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        extractStatusRequest { requestedState =>
            val docid = DocId(WhiskEntity.qualifiedName(namespace, name))

            getEntity(WhiskRule, entityStore, docid, Some {
                rule: WhiskRule =>
                    val changeStateInDatastore = {
                        if (rule.status != Status.ACTIVATING && rule.status != Status.DEACTIVATING && requestedState != rule.status) {
                            val nextState = Status.next(rule.status)
                            val newRule = rule.toggle(nextState)
                            info(this, s"[POST] rule state change initiated: ${rule.status} -> $nextState -> $requestedState")
                            Future successful (rule.status, newRule)
                        } else {
                            info(this, s"[POST] rule state change ignored, cannot change from: ${rule.status} -> $requestedState")
                            Future failed { IgnoredRuleActivation(requestedState == rule.status) }
                        }
                    } flatMap {
                        case (prevState, newRule) =>
                            info(this, s"[POST] attempting to set rule state to: ${newRule.status}")
                            WhiskRule.put(entityStore, newRule) map { docid =>
                                postToActivator(user, namespace, name, prevState, newRule, docid)
                            }
                    }

                    onComplete(changeStateInDatastore) {
                        case Success(response) =>
                            complete(Accepted)
                        case Failure(t) => t match {
                            case _: DocumentConflictException =>
                                info(this, s"[POST] rule update conflict")
                                terminate(Conflict, conflictMessage)
                            case IgnoredRuleActivation(ok) =>
                                info(this, s"[POST] rule update ignored")
                                if (ok) complete(OK) else terminate(Conflict)
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
    override def remove(namespace: Namespace, name: EntityName)(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        deleteEntity(WhiskRule, entityStore, docid, (r: WhiskRule) =>
            if (r.status == Status.INACTIVE) {
                Future successful true
            } else Future failed {
                RejectRequest(Conflict, s"rule status is '${r.status}', must be '${Status.INACTIVE}' to delete")
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
    override def fetch(namespace: Namespace, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        getEntity(WhiskRule, entityStore, docid)
    }

    /**
     * Gets all rules in namespace.
     *
     * Responses are one of (Code, Message)
     * - 200 [] or [WhiskRule as JSON]
     * - 500 Internal Server Error
     */
    override def list(namespace: Namespace, excludePrivate: Boolean)(implicit transid: TransactionId) = {
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
    private def create(content: WhiskRulePut, namespace: Namespace, name: EntityName)(implicit transid: TransactionId): Future[WhiskRule] = {
        val predicate = Promise[WhiskRule]

        if (content.trigger.isDefined && content.action.isDefined) {
            val tid = DocId(WhiskEntity.qualifiedName(namespace, content.trigger.get))
            val aid = DocId(WhiskEntity.qualifiedName(namespace, content.action.get))

            checkTriggerAndActionExist(tid, aid) onComplete {
                case Success(p) =>
                    val rule = WhiskRule(
                        namespace,
                        name,
                        Status.INACTIVE,
                        content.trigger.get,
                        content.action.get,
                        content.version getOrElse SemVer(),
                        content.publish getOrElse false,
                        content.annotations getOrElse Parameters())
                    predicate.success(rule)
                case Failure(t) => predicate.failure { RejectRequest(BadRequest, t) }
            }
        } else predicate.failure { RejectRequest(BadRequest, "rule requires a valid trigger and a valid action") }

        predicate.future
    }

    /** Updates a WhiskTrigger from PUT content, merging old trigger where necessary. */
    private def update(content: WhiskRulePut)(rule: WhiskRule)(implicit transid: TransactionId): Future[WhiskRule] = {
        val predicate = Promise[WhiskRule]

        if (rule.status == Status.INACTIVE) {
            val trigger = content.trigger getOrElse rule.trigger
            val action = content.action getOrElse rule.action
            val tid = DocId(WhiskEntity.qualifiedName(rule.namespace, trigger))
            val aid = DocId(WhiskEntity.qualifiedName(rule.namespace, action))

            checkTriggerAndActionExist(tid, aid) onComplete {
                case Success(p) =>
                    val r = WhiskRule(
                        rule.namespace,
                        rule.name,
                        Status.INACTIVE,
                        trigger,
                        action,
                        content.version getOrElse rule.version.upPatch,
                        content.publish getOrElse rule.publish,
                        content.annotations getOrElse rule.annotations).
                        revision[WhiskRule](rule.docinfo.rev)
                    predicate.success(r)
                case Failure(t) => predicate.failure { RejectRequest(BadRequest, t) }
            }
        } else predicate.failure { RejectRequest(Conflict, s"rule may not be updated while status is ${rule.status}") }

        predicate.future
    }

    /**
     * Posts state change request to activator. If the post fails, reverts the rule state. If activator times out
     * or fails, unconditionally sets rule state to INACTIVE.
     */
    private def postToActivator(user: Subject, namespace: Namespace, name: EntityName, prevState: Status, newRule: WhiskRule, docid: DocInfo)(implicit transid: TransactionId) = {
        val message = Message(transid, s"/rules/${newRule.status}/${newRule.docid}", user, ActivationId(), None)
        val post = performLoadBalancerRequest(ACTIVATOR, message, transid) flatMap { response =>
            response.id match {
                case Some(_) =>
                    info(this, s"[POST] rule status set to: ${newRule.status}")
                    Future successful docid
                case None =>
                    error(this, s"[POST] rule status change cannot be completed: ${response.error.getOrElse("??")}")
                    Future failed {
                        new IllegalStateException(s"activation failed with error: ${response.error.getOrElse("??")}")
                    }
            }
        }

        post onSuccess {
            case docid =>
                val promise = Promise[Boolean]
                val monitor = promise.future withTimeout (ruleChangeTimeout, new TimeoutException)
                monitor.onFailure {
                    case t =>
                        // set the state back to inactive unconditionally, if successful then activator failed
                        // or timed out and this update will cause it to abort; or this update will fail in which
                        // case activator succeeded and nothing else is required
                        // TODO: have to retry if put fails with error other than conflict
                        WhiskRule.put(entityStore, newRule.toggle(Status.INACTIVE).revision[WhiskRule](docid.rev)) onComplete {
                            case Success(_) => info(this, s"[POST] rule state change timed out, reset rule to ${Status.INACTIVE}")
                            case Failure(t) => t match {
                                case _: DocumentConflictException =>
                                    info(this, s"[POST] rule state change completed")
                                case _ =>
                                    error(this, s"[POST] rule state reset failed, rule may be stuck in bad state: ${t.getMessage}")
                            }
                        }
                }
        }

        post onFailure {
            case t: Throwable =>
                info(this, s"[POST] rule state change rejected by activator, reverting rule to $prevState: ${t.getMessage}")
                // TODO: have to retry until one succeeds
                WhiskRule.put(entityStore, newRule.toggle(prevState).revision[WhiskRule](docid.rev)) onComplete {
                    case Success(_) => info(this, s"[POST] rule state reverted to '$prevState'")
                    case Failure(t) => error(this, s"[POST] rule state not reverted to '$prevState', rule may be stuck in bad state: ${t.getMessage}")
                }
        }

        post
    }

    /**
     * Checks if trigger and action are valid documents (that is, they exist) in the datastore.
     *
     * @param trigger the trigger id
     * @param action the action id
     * @return promise that completes to true iff both documents exist
     */
    private def checkTriggerAndActionExist(trigger: DocId, action: DocId)(implicit transid: TransactionId): Future[Boolean] = {
        val validTrigger = Promise[Boolean]
        val validAction = Promise[Boolean]

        val validateTrigger = WhiskTrigger.get(entityStore, trigger.asDocInfo) onComplete {
            case Success(_) => validTrigger.success(true)
            case Failure(t) => t match {
                case _: NoDocumentException => validTrigger.failure(new NoDocumentException(s"$trigger does not exist"))
                case _                      => validTrigger.failure(t)
            }
        }

        val validateAction = WhiskAction.get(entityStore, action.asDocInfo) onComplete {
            case Success(_) => validAction.success(true)
            case Failure(t) => t match {
                case _: NoDocumentException => validAction.failure(new NoDocumentException(s"$action does not exist"))
                case _                      => validAction.failure(t)
            }
        }

        val valid = for {
            triggerExists <- validTrigger.future
            actionExists <- validAction.future
        } yield (triggerExists & actionExists)
        valid
    }

    /** Extracts status request subject to allowed values. */
    private def extractStatusRequest = {
        implicit val statusSerdes = Status.serdesRestricted
        entity(as[Status])
    }
}

private case class IgnoredRuleActivation(noop: Boolean) extends Throwable
private case class RuleChangeTimeout(docid: DocInfo) extends TimeoutException
