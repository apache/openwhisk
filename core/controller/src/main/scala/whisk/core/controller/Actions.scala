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

import java.time.Clock
import java.time.Instant

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.language.postfixOps
import akka.actor.ActorSystem
import spray.http.HttpMethod
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import spray.http.StatusCodes.BadGateway
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.TooManyRequests
import spray.http.StatusCodes.RequestEntityTooLarge
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.{ JsArray, JsObject, JsString }
import spray.routing.RequestContext
import whisk.common.LoggingMarkers
import whisk.common.StartMarker
import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ActivationResponse
import whisk.core.entity.ActivationId
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.EntityName
import whisk.core.entity.SequenceExec
import whisk.core.entity.MemoryLimit
import whisk.core.entity.Namespace
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.TimeLimit
import whisk.core.entity.LogLimit
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActionPut
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.EntityStore
import whisk.core.entitlement.Collection
import whisk.core.entitlement.Privilege
import whisk.core.entity.WhiskAuth
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.ActivationMessage.INVOKER
import whisk.core.entitlement.Resource
import whisk.core.entity.WhiskPackage
import whisk.core.entity.Binding
import whisk.core.entity.Subject
import whisk.http.ErrorResponse.terminate
import whisk.common.PrintStreamEmitter
import org.apache.kafka.common.errors.RecordTooLargeException
import whisk.utils.ExecutionContextFactory.FutureExtensions
import whisk.http.ErrorResponse

/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the actions API.
 */
object WhiskActionsApi {
    def requiredProperties = WhiskServices.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties

    val sequenceHackFlag = false   // a temporary flag that disables the old hack that runs sequences using Pipe.js
}

/** A trait implementing the actions API. */
trait WhiskActionsApi extends WhiskCollectionAPI {
    services: WhiskServices =>

    // special collection to deal with potential sequences
    protected override val collection = Collection(Collection.ACTIONS)

    /** An actor system for timed based futures. */
    protected implicit val actorSystem: ActorSystem

    /** Database service to CRUD actions. */
    protected val entityStore: EntityStore

    /** Database service to get activations. */
    protected val activationStore: ActivationStore

    private implicit val emitter: PrintStreamEmitter = this

    /**
     * Handles operations on action resources, which encompass these cases:
     *
     * 1. ns/foo     -> subject must be authorized for one of { action(ns, *), action(ns, foo) },
     *                  resource resolves to { action(ns, foo) }
     *
     * 2. ns/bar/foo -> where bar is a package
     *                  subject must be authorized for one of { package(ns, *), package(ns, bar), action(ns.bar, foo) }
     *                  resource resolves to { action(ns.bar, foo) }
     *
     * 3. ns/baz/foo -> where baz is a binding to ns'.bar
     *                  subject must be authorized for one of { package(ns, *), package(ns, baz) }
     *                  *and* one of { package(ns', *), package(ns', bar), action(ns'.bar, foo) }
     *                  resource resolves to { action(ns'.bar, foo) }
     *
     * Note that package(ns, xyz) == action(ns.xyz, *) and if subject has rights to package(ns, xyz)
     * then they also have rights to action(ns.xyz, *) since sharing is done at the package level and
     * is not more granular; hence a check on action(ns.xyz, abc) is eschewed.
     *
     * Only list is supported for these resources:
     *
     * 4. ns/bar/    -> where bar is a package
     *                  subject must be authorized for one of { package(ns, *), package(ns, bar) }
     *                  resource resolves to { action(ns.bar, *) }
     *
     * 5. ns/baz/    -> where baz is a binding to ns'.bar
     *                  subject must be authorized for one of { package(ns, *), package(ns, baz) }
     *                  *and* one of { package(ns', *), package(ns', bar) }
     *                  resource resolves to { action(ns.bar, *) }
     */
    protected override def innerRoutes(user: WhiskAuth, ns: Namespace)(implicit transid: TransactionId) = {
        (entityPrefix & entityOps & requestMethod) { (segment, m) =>
            entityname(segment) { outername =>
                pathEnd {
                    // matched /namespace/collection/name
                    // this is an action in default package, authorize and dispatch
                    authorizeAndDispatch(m, user, Resource(ns, collection, Some(outername)))
                } ~ (get & pathSingleSlash) {
                    // matched GET /namespace/collection/package-name/
                    // list all actions in package iff subject is entitled to READ package
                    val resource = Resource(ns, Collection(Collection.PACKAGES), Some(outername))
                    authorizeAndContinue(Privilege.READ, user.subject, resource, next = () => {
                        listPackageActions(user.subject, ns, EntityName(outername))
                    })
                } ~ (entityPrefix & pathEnd) { segment =>
                    entityname(segment) { innername =>
                        // matched /namespace/collection/package-name/action-name
                        // this is an action in a named package
                        val packageDocId = DocId(WhiskEntity.qualifiedName(ns, EntityName(outername)))
                        val packageResource = Resource(ns, Collection(Collection.PACKAGES), Some(outername))
                        m match {
                            case GET =>
                                // need to merge package with action, hence authorize subject for package
                                // access (if binding, then subject must be authorized for both the binding
                                // and the referenced package)
                                //
                                // NOTE: it is an error if either the package or the action does not exist,
                                // the former manifests as unauthorized and the latter as not found
                                //
                                // it used to be that a GET (READ) and POST (ACTIVATE) resolve to
                                // a READ right on the package before sequences were introduced as first class
                                authorizeAndContinue(Privilege.READ, user.subject, packageResource, next = () => {
                                    getEntity(WhiskPackage, entityStore, packageDocId, Some {
                                        mergeActionWithPackageAndDispatch(m, user, EntityName(innername)) _
                                    })
                                })
                            case POST =>
                                // this is an activate on an action that contains a package
                                // two sets of rights need to be checked:
                                // read rights for the package and activate rights for the action
                                authorizeAndContinue(Privilege.READ, user.subject, packageResource, next = () => { // package
                                    // the action resource needs to include the package in the namespace
                                    val actionNamespace = ns.addpath(EntityName(outername))
                                    val actionResource = Resource(actionNamespace, Collection(Collection.ACTIONS), Some(innername))
                                    authorizeAndContinue(Privilege.ACTIVATE, user.subject, actionResource, next = () => { // action
                                        getEntity(WhiskPackage, entityStore, packageDocId, Some {
                                            mergeActionWithPackageAndDispatch(m, user, EntityName(innername)) _
                                        })
                                    })
                                })
                            case PUT | DELETE =>
                                // these packaged action operations do not need merging with the package,
                                // but may not be permitted if this is a binding, or if the subject does
                                // not have PUT and DELETE rights to the package itself
                                val right = collection.determineRight(m, Some { innername })
                                authorizeAndContinue(right, user.subject, packageResource, next = () => {
                                    getEntity(WhiskPackage, entityStore, packageDocId, Some { wp: WhiskPackage =>
                                        wp.binding map {
                                            _ => terminate(BadRequest, "Operation not permitted on package binding")
                                        } getOrElse {
                                            val actionResource = Resource(wp.path, collection, Some { innername })
                                            dispatchOp(user, right, actionResource)
                                        }
                                    })
                                })
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates or updates action if it already exists. The PUT content is deserialized into a WhiskActionPut
     * which is a subset of WhiskAction (it eschews the namespace and entity name since the former is derived
     * from the authenticated user and the latter is derived from the URI). The WhiskActionPut is merged with
     * the existing WhiskAction in the datastore, overriding old values with new values that are defined.
     * Any values not defined in the PUT content are replaced with old values.
     *
     * Responses are one of (Code, Message)
     * - 200 WhiskAction as JSON
     * - 400 Bad Request
     * - 409 Conflict
     * - 500 Internal Server Error
     */
    override def create(namespace: Namespace, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskActionPut]) { content =>
                info(this, s"NAMESPACE $namespace")
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                putEntity(WhiskAction, entityStore, docid, overwrite, update(content)_, () => { make(content, namespace, name) })
            }
        }
    }

    /**
     * Invokes action if it exists. The POST content is deserialized into a Payload and posted
     * to the loadbalancer.
     *
     * Responses are one of (Code, Message)
     * - 200 Activation as JSON if blocking or just the result JSON iff '&result=true'
     * - 202 ActivationId as JSON (this is issued on non-blocking activation or blocking activation that times out)
     * - 404 Not Found
     * - 502 Bad Gateway
     * - 500 Internal Server Error
     */
    override def activate(user: WhiskAuth, namespace: Namespace, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        parameter('blocking ? false, 'result ? false) { (blocking, result) =>
            entity(as[Option[JsObject]]) { payload =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                getEntity(WhiskAction, entityStore, docid, Some {
                    action: WhiskAction =>
                        transid.started(this, if (blocking) LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING else LoggingMarkers.CONTROLLER_ACTIVATION)
                        // check whether activating a sequence
                        val postToLoadBalancer = {
                            action.exec match {
                                case SequenceExec(_, components) => invokeSequence(user.subject, action, env, payload, blocking, components)
                                case _ => postInvokeRequest(user.subject, action, env, payload, blocking)
                            }
                        }
                        onComplete(postToLoadBalancer) {
                            case Success((activationId, None)) =>
                                // non-blocking invoke or blocking invoke which got queued instead
                                complete(Accepted, activationId.toJsObject)
                            case Success((activationId, Some(activation))) =>
                                val response = if (result) activation.resultAsJson else activation.toExtendedJson

                                if (activation.response.isSuccess) {
                                    complete(OK, response)
                                } else if (activation.response.isApplicationError) {
                                    // actions that result is ApplicationError status are considered a 'success'
                                    // and will have an 'error' property in the result - the HTTP status is OK
                                    // and clients must check the response status if it exists
                                    // NOTE: response status will not exist in the JSON object if ?result == true
                                    // and instead clients must check if 'error' is in the JSON
                                    // PRESERVING OLD BEHAVIOR and will address defect in separate change
                                    complete(BadGateway, response)
                                } else if (activation.response.isContainerError) {
                                    complete(BadGateway, response)
                                } else {
                                    complete(InternalServerError, response)
                                }
                            case Failure(t: BlockingInvokeTimeout) =>
                                info(this, s"[POST] action activation waiting period expired")
                                complete(Accepted, t.activationId.toJsObject)
                            case Failure(t: TooManyActivationException) =>
                                info(this, s"[POST] max activation limit has exceeded")
                                terminate(TooManyRequests)
                            case Failure(t: RecordTooLargeException) =>
                                info(this, s"[POST] action payload was too large")
                                terminate(RequestEntityTooLarge)
                            case Failure(SequenceIntermediateActionError(component)) =>
                                // an action within a sequence produced a json with a field 'error'
                                info(this, s"[POST] sequence execution intermediate action error for $component")
                                terminate(BadGateway,
                                          Some(ErrorResponse(s"sequence execution intermediate action error for $component",
                                                        transid)))
                            case Failure(SequenceComponentRetrieveException(component)) =>
                                // retrieving the action entity for one of the actions failed
                                info(this, s"[POST] sequence action retrieve entity failed for $component")
                                terminate(NotFound,
                                          Some(ErrorResponse(s"sequence action retrieve entity failed for $component", transid)))
                            case Failure(SequenceActionTimeout(component)) =>
                                info(this, s"[POST] sequence action timeout for $component")
                                terminate(BadGateway,
                                          Some(ErrorResponse(s"sequence action timeout for $component",
                                                        transid)))
                            case Failure(t: Throwable) =>
                                error(this, s"[POST] action activation failed: ${t.getMessage}")
                                terminate(InternalServerError, t.getMessage)
                        }
                })
            }
        }
    }

    /**
     * Deletes action.
     *
     * Responses are one of (Code, Message)
     * - 200 WhiskAction as JSON
     * - 404 Not Found
     * - 409 Conflict
     * - 500 Internal Server Error
     */
    override def remove(namespace: Namespace, name: EntityName)(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        deleteEntity(WhiskAction, entityStore, docid, (a: WhiskAction) => Future successful true)
    }

    /**
     * Gets action. The action name is prefixed with the namespace to create the primary index key.
     *
     * Responses are one of (Code, Message)
     * - 200 WhiskAction has JSON
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    override def fetch(namespace: Namespace, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        getEntity(WhiskAction, entityStore, docid, Some { action: WhiskAction =>
            val mergedAction = env map { action inherit _ } getOrElse action
            complete(OK, mergedAction)
        })
    }

    /**
     * Gets all action in namespace.
     *
     * Responses are one of (Code, Message)
     * - 200 [] or [WhiskAction as JSON]
     * - 500 Internal Server Error
     */
    override def list(namespace: Namespace, excludePrivate: Boolean)(implicit transid: TransactionId) = {
        // for consistency, all the collections should support the same list API
        // but because supporting docs on actions is difficult, the API does not
        // offer an option to fetch entities with full docs yet.
        //
        // the complication with actions is that providing docs on actions in
        // package bindings is complicated; it cannot be do readily with a cloudant
        // (couchdb) view and would require finding all bindings in namespace and
        // joining the actions explicitly here.
        val docs = false
        parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false) {
            (skip, limit, count) =>
                listEntities {
                    WhiskAction.listCollectionInNamespace(entityStore, namespace, skip, limit, docs) map {
                        list =>
                            val actions = if (docs) {
                                list.right.get map { WhiskAction.serdes.write(_) }
                            } else list.left.get
                            FilterEntityList.filter(actions, excludePrivate)
                    }
                }
        }
    }

    /** Creates a WhiskAction from PUT content, generating default values where necessary. */
    private def make(content: WhiskActionPut, namespace: Namespace, name: EntityName)(implicit transid: TransactionId) = {
        if (content.exec.isDefined) Future successful {
            val exec = content.exec.get
            val limits = content.limits map { l =>
                ActionLimits(
                    l.timeout getOrElse TimeLimit(),
                    l.memory getOrElse MemoryLimit(),
                    l.logs getOrElse LogLimit())
            } getOrElse ActionLimits()

            info(this, s"namespace $namespace name $name")
            // This is temporary while we are making sequencing directly supported in the controller.
            // The parameter override allows this to work with Pipecode.code. Any parameters other
            // than the action sequence itself are discarded and have no effect.
            val parameters = if (WhiskActionsApi.sequenceHackFlag) {
                                exec match {
                                    case seq: SequenceExec => Parameters("_actions", JsArray(seq.components map { JsString(_) }))
                                    case _                 => content.parameters getOrElse Parameters()
                                }
                            } else content.parameters getOrElse Parameters()
            // This is temporary, while transitioning to new seq implementation: fix the names of the components

            val fixedExec = if (WhiskActionsApi.sequenceHackFlag) exec
                            else exec match {
                                case seq: SequenceExec =>
                                    // super hack for now; FIXME!!!!
                                    val components = seq.components map { _.replace("/_", namespace.root.namespace) }
                                    info(this, s"COMPONENTS $components")
                                    new SequenceExec(seq.code, components)
                                case _ => exec
                            }
            WhiskAction(
                namespace,
                name,
                fixedExec,
                parameters,
                limits,
                content.version getOrElse SemVer(),
                content.publish getOrElse false,
                content.annotations getOrElse Parameters())
        }
        else Future failed RejectRequest(BadRequest, "exec undefined")
    }

    /** Updates a WhiskAction from PUT content, merging old action where necessary. */
    private def update(content: WhiskActionPut)(action: WhiskAction) = Future successful {
        val limits = content.limits map { l =>
            ActionLimits(l.timeout getOrElse action.limits.timeout, l.memory getOrElse action.limits.memory, l.logs getOrElse action.limits.logs)
        } getOrElse action.limits

        // This is temporary while we are making sequencing directly supported in the controller.
        // Actions that are updated with a sequence will have their parameter property overriden.
        // Actions that are updated with non-sequence actions will either set the parameter property according to
        // the content provided, or if that is not defined, and iff the previous version of the action was not a
        // sequence, inherit previous parameters. This is because sequence parameters are special and should not
        // leak to non-sequence actions.
        // If updating an action but not specifying a new exec type, then preserve the previous parameters if the
        // existing type of the action is a sequence (regardless of what parameters may be defined in the content)
        // otherwise, parameters are inferred from the content or previous values.
        // TODO: fix sequence
        val parameters = if (WhiskActionsApi.sequenceHackFlag) {
                            content.exec map {
                                    case seq: SequenceExec => Parameters("_actions", JsArray(seq.components map { JsString(_) }))
                                    case _ => content.parameters getOrElse {
                                        action.exec match {
                                            case seq: SequenceExec => Parameters()
                                            case _                 => action.parameters
                                        }
                                    }
                                } getOrElse {
                                    action.exec match {
                                        case seq: SequenceExec => action.parameters // discard content.parameters
                                        case _                 => content.parameters getOrElse action.parameters
                                    }
                                }
                         } else content.parameters getOrElse action.parameters

        WhiskAction(
            action.namespace,
            action.name,
            content.exec getOrElse action.exec,
            parameters,
            limits,
            content.version getOrElse action.version.upPatch,
            content.publish getOrElse action.publish,
            content.annotations getOrElse action.annotations).
            revision[WhiskAction](action.docinfo.rev)
    }

    /**
     * Gets document from datastore to confirm a valid action activation then posts request to loadbalancer.
     * If the loadblancer accepts the requests with an activation id, then wait for the result of the activation
     * if this is a blocking invoke, else return the activation id.
     *
     * @param subject the subject invoking the action
     * @param docid the action document id
     * @param env the merged parameters from the package/reference if any
     * @param payload the dynamic arguments for the activation
     * @param blocking true iff this is a blocking invoke
     * @param transid a transaction id for logging
     * @return a promise that completes with (ActivationId, Some(WhiskActivation)) if blocking else (ActivationId, None)
     */
    private def postInvokeRequest(
        user: Subject,
        action: WhiskAction,
        env: Option[Parameters],
        payload: Option[JsObject],
        blocking: Boolean)(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        // merge package parameters with action (action parameters supersede), then merge in payload
        val args = { env map { _ ++ action.parameters } getOrElse action.parameters } merge payload
        val message = Message(transid, s"/actions/invoke/${action.namespace}/${action.name}/${action.rev}", user, ActivationId(), args)

        info(this, s"[POST] action activation id: ${message.activationId}")
        performLoadBalancerRequest(INVOKER, message, transid) map {
            (action.limits.timeout(), _)
        } flatMap {
            case (duration, response) =>
                response.id match {
                    case Some(activationId) =>
                        Future successful (duration, activationId)
                    case None =>
                        if (response.error.getOrElse("??").equals("too many concurrent activations")) {
                            // DoS throttle
                            warn(this, s"[POST] action activation rejected: ${response.error.getOrElse("??")}")
                            Future failed new TooManyActivationException("too many concurrent activations")
                        } else {
                            error(this, s"[POST] action activation failed: ${response.error.getOrElse("??")}")
                            Future failed new IllegalStateException(s"activation failed with error: ${response.error.getOrElse("??")}")
                        }
                }
        } flatMap {
            case (duration, activationId) =>
                if (blocking) {
                    val docid = DocId(WhiskEntity.qualifiedName(user.namespace, activationId))
                    val timeout = duration + blockingInvokeGrace
                    val promise = Promise[Option[WhiskActivation]]
                    info(this, s"[POST] action activation will block on result up to $timeout ($duration + $blockingInvokeGrace grace)")
                    pollLocalForResult(docid.asDocInfo, activationId, promise)
                    val response = promise.future map {
                        (activationId, _)
                    } withTimeout (timeout, new BlockingInvokeTimeout(activationId))
                    response onComplete {
                        case Success(_) =>
                            // Duration of the blocking activation in Controller.
                            // We use the start time of the tid instead of a startMarker to avoid passing the start marker around.
                            transid.finished(this, StartMarker(transid.meta.start, LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING))
                        case Failure(t) =>
                            // short circuits polling on result
                            promise.tryFailure(t)
                    }
                    response // will either complete with activation or fail with timeout
                } else Future {
                    // Duration of the non-blocking activation in Controller.
                    // We use the start time of the tid instead of a startMarker to avoid passing the start marker around.
                    transid.finished(this, StartMarker(transid.meta.start, LoggingMarkers.CONTROLLER_ACTIVATION))
                    (activationId, None)
                }
        }
    }


    /**
     * Executes a sequence by invoking in a blocking function each of its components.
     *
     * @param subject the subject invoking the action
     * @param docid the action document id
     * @param env the merged parameters from the package/reference if any
     * @param payload the dynamic arguments for the activation
     * @param blocking true iff this is a blocking invoke
     * @param components the actions in the sequence
     * @param transid a transaction id for logging
     * @return a promise that completes with (ActivationId, Some(WhiskActivation)) if blocking else (ActivationId, None)
     */
    private def invokeSequence(
        user: Subject,
        action: WhiskAction,
        env: Option[Parameters],
        payload: Option[JsObject],
        blocking: Boolean,
        components: Seq[String])(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        val promise = Promise[(ActivationId, Option[WhiskActivation])]
        info(this, s"Invoking sequence for action ${action.name}")
        invokeSequenceComponents(promise, action, user, env, payload, components)
        return promise.future
    }

    /**
     * Invoke one component action from a sequence iff the payload does not contain a field 'error'.
     * The component action can be a sequence or an atomic action.
     * @param action the action to be invoked
     * @param user the user on behalf the action is executing
     * @param env
     * @param payload the payload for the executing action
     * @param activationId the id of the activation that executed previously in the sequence
     * @return the activation id and the result of the action
     */
    private def invokeOneComponent(
            action: WhiskAction,
            user: Subject,
            env: Option[Parameters],
            payload: Option[JsObject],
            activationId: Option[ActivationId])(
                    implicit transid: TransactionId): Future[(Option[ActivationId], Option[JsObject])] = {
        // check if there is any payload; if payload is None wait for the result of the previous activation
        info(this, s"Invoking sequence component ${action.name}")
        val paramsFuture: Future[Option[JsObject]] = if (payload == None) {
            if (activationId == None) {
                // this should never happen: the first action in a sequence with no payload
                Future failed { new RuntimeException("Error: trying to execute a sequence with no payload")}
            }
            val activationIdVal = activationId.get
            // wait for result for previous activation
            val docid = DocId(WhiskEntity.qualifiedName(user.namespace, activationIdVal))
            val timeout = action.limits.timeout() + blockingInvokeGrace
            val promise = Promise[Option[WhiskActivation]]
            info(this, s"Sequence execution: action activation will block on result up to $timeout")
            pollLocalForResult(docid.asDocInfo, activationIdVal, promise)
            val result =  promise.future withTimeout (timeout, new BlockingInvokeTimeout(activationIdVal))
            result map {
                wskActivation => {
                    wskActivation flatMap {act => act.response.result map {_.asJsObject}}
                }
            }
        } else Future successful {payload}

        paramsFuture flatMap {
            params =>
                if (params == None)
                    Future failed { new RuntimeException("Error: previous action in sequence produced no result")}
                // if the payload contains error, don't execute anything, return nothing
                if (params.get.getFields("error").size > 0)
                    Future failed SequenceIntermediateActionError(action.name.toString)
                (action.exec match {
                    case SequenceExec(_, components) =>
                        // invoke of a sequence
                        info(this, s"sequence invoking an enclosing sequence $action")
                        invokeSequence(user, action, env, params, true, components)
                    case _ =>
                        // this is a simple invoke --- blocking
                        info(this, s"sequence invoking an enclosing atomic action $action")
                        postInvokeRequest(user, action, env, params, true)
                }) map {res => (Some(res._1), res._2 flatMap {_.response.result map {_.asJsObject}})}
        }
    }

    private def invokeSequenceComponents(
            promise: Promise[(ActivationId, Option[WhiskActivation])],
            seqAction: WhiskAction,
            user: Subject,
            env: Option[Parameters],
            payload: Option[JsObject],
            components: Seq[String])(
                implicit transid: TransactionId) = {
        // create new activation id that corresponds to the sequence
        val seqActivationId = ActivationId()
        // TODO: shall I replace (start, end) with (start-of-first-activation, end-of-last-activation)?
        val start = Instant.now(Clock.systemUTC())
        // first retrieve the information/entities on all actions
        // do not wait to successfully retrieve all the actions before starting the execution
        // start execution of the first action while potentially still retrieving entities
        // Note: the execution starts even if one of the futures retrieving an entity may fail
        val futureActions = components map { c => WhiskAction.get(entityStore, DocInfo(c)) }
        // "fold" the wskActions to execute them in blocking fashion
        // the params are the payload/params and the list of the previous activation ids
        val init = Future successful {(payload, Seq.empty[Option[ActivationId]])}
        // use scanLeft instead of foldLeft as I need the intermediate results in case of failure
        val seqRes = futureActions.scanLeft(init) {
            (futurePair, futureWskAction)  =>
                  for(
                      wskAction <- futureWskAction;
                      pair <- futurePair;
                      (params, seq0) = pair;
                      activationId = if (seq0.isEmpty) None else seq0.last;
                      result <- invokeOneComponent(wskAction, user, env, params, activationId);
                      seq = seq0 :+ result._1
                  ) yield (result._2, seq)
        }
        // check all futures were successful
        val futureRes = Future.sequence(seqRes)
        futureRes onComplete {
            case Success(seq) =>
                // all went well, get the last result (remember this was the seq produced by a scanLeft)
                val result = seq.last._1.get
                val ids = seq.last._2 map {_.get}
                val pair = storeSequenceResults(user, seqAction, seqActivationId, ids, result, start, Instant.now(Clock.systemUTC()))
                // complete the promise
                val futureDoc = pair._2
                val activation = pair._1
                futureDoc onComplete {
                    case Success(_) => promise.success((seqActivationId, Some(activation)))
                    case Failure(_) => promise.failure(new RuntimeException("Sequence error: storing activation failed"))
                }

            case Failure(SequenceIntermediateActionError(component)) =>
                val component = storeIntermediateResults(user, seqAction, components, seqActivationId, seqRes, start) getOrElse seqAction.name.name
                info(this, s"The execution of a sequence failed with an error for action $component")
                promise.failure(SequenceIntermediateActionError(component))
            case Failure(x) if x.isInstanceOf[NoDocumentException] |
                               x.isInstanceOf[IllegalArgumentException] =>
                // retrieve document failure
                val component = storeIntermediateResults(user, seqAction, components, seqActivationId, seqRes, start)
                // storing intermediate results could fail --- propagate that instead?
                info(this, s"Sequence error while retrieving action entity for component $component")
                promise.failure(SequenceComponentRetrieveException(component.toString))
            case Failure(BlockingInvokeTimeout(_)) =>
                // one of the actions took too long to execute
                val component = storeIntermediateResults(user, seqAction, components, seqActivationId, seqRes, start)
                info(this, s"Sequence error action took too long to execute $component")
                // storing intermediate results could fail --- propagate that instead?
                promise.failure(SequenceActionTimeout(component.toString))
            case Failure(x: Throwable) =>
                // who knows what happened...
                info(this, s"Error while executing sequence $seqAction")
                promise.failure(x)
        }
    }

    /**
     * store intermediate results (result, logs) for a sequence that failed execution
     * @param user the user who run the activation/action
     * @param action the sequence action
     * @param components the components that form the sequence
     * @param activationId the activation id for the sequence
     * @param seq a sequence with the results of the (partial) execution of the sequence
     * @param start the start timestamp for this activation
     * @return the component for which the failure happened
     */
    def storeIntermediateResults(
            user: Subject,
            action: WhiskAction,
            components: Seq[String],
            activationId: ActivationId,
            seq: Seq[Future[(Option[JsObject], Seq[Option[ActivationId]])]],
            start: Instant)(
                implicit transid: TransactionId): Option[String] = {
        // the end could make it tighter: TODO where to move this?
        val end = Instant.now(Clock.systemUTC())
        // find the last activation that succeeded
        info(this, s"partial results: $seq")
        // check for the first completed Future that is a Failure
        val index = seq.indexWhere( {
            // check for the first failure
            _.value match {
                case Some(Failure(_)) => true
                case _ => false
            }
        })
        index match {
            case 0 =>
                // this shouldn't happen; the first result in the scanLeft is the initial parameter
                error(this, "Storing intermediate results found unsuccesful initial parameter")
                None
            case -1 =>
                // it should always find a failed one, this is an error
                error(this, "Storing intermediate results for a sequence did not find a failed activation")
                None
            case idx =>
                // the execution of activation idx-1 failed
                // the result of the execution is just the previous entry
                // this future should be done and Successful (the last successful future before the first failed one)
                val result = seq(idx - 1)
                // store the sequence results
                result onSuccess {
                    case tuple =>
                        val result = tuple._1.get
                        val ids = tuple._2 map {_.get}
                        storeSequenceResults(user, action, activationId, ids, result, start, end)
                }
                // return the component that failed
                Some(components(idx - 1))
        }
    }

    /**
     * store the activation of a sequence
     */
    def storeSequenceResults(
            user: Subject,
            action: WhiskAction,
            activationId: ActivationId,
            ids: Seq[ActivationId],
            result: JsObject,
            start: Instant,
            end: Instant,
            error: Boolean = false,
            errorRes: Option[ActivationResponse] = None)(
                implicit transid: TransactionId): (WhiskActivation, Future[DocInfo]) = {
        // compose logs
        val logs = ActivationLogs((ids map { _.toString}).toVector)
        // create the whisk activation
        val activation = WhiskActivation(
                action.namespace,
                action.name,
                user,
                activationId,
                start,
                end,
                None, // how do I populate the cause???
                if (error) errorRes getOrElse ActivationResponse.whiskError("Sequence error") else ActivationResponse.success(Some(result)),
                logs,
                action.version,
                action.publish,
                action.parameters ++ Parameters("kind", "sequence"));
        val docInfo = WhiskActivation.put(activationStore, activation)
        (activation, docInfo)
    }

    /**
     * This is a fast path used for blocking calls in which we do not need the full WhiskActivation record from the DB.
     * Polls for the activation response from an underlying data structure populated from Kakfa active acknowledgements.
     * If this mechanism fails to produce an answer quickly, the future will fail and we back off into using
     * a database operation to obtain the canonical answer.
     */
    private def pollLocalForResult(
        docid: DocInfo,
        activationId: ActivationId,
        promise: Promise[Option[WhiskActivation]])(
            implicit transid: TransactionId): Unit = {
        queryActivationResponse(activationId, transid) map {
            activation => promise.trySuccess { Some(activation) }
        } onFailure {
            case t: TimeoutException =>
                info(this, s"[POST] switching to poll db, active ack expired")
                pollDbForResult(docid, activationId, promise)
            case t: Throwable =>
                error(this, s"[POST] switching to poll db, active ack exception: ${t.getMessage}")
                pollDbForResult(docid, activationId, promise)
        }
    }

    /**
     * Polls for activation record. It is assumed that an activation record is created atomically and never updated.
     * Fetch the activation record by its id. If it exists, complete the promise. Otherwise recursively poll until
     * either there is an error in the get, or the promise has completed because it timed out. The promise MUST
     * complete in the caller to terminate the polling.
     */
    private def pollDbForResult(
        docid: DocInfo,
        activationId: ActivationId,
        promise: Promise[Option[WhiskActivation]])(
            implicit transid: TransactionId): Unit = {
        if (!promise.isCompleted) {
            WhiskActivation.get(activationStore, docid) map {
                activation => promise.trySuccess { Some(activation) } // activation may have logs, do not strip them
            } onFailure {
                case e: NoDocumentException =>
                    Thread.sleep(500)
                    debug(this, s"[POST] action activation not yet timed out, will poll for result")
                    pollDbForResult(docid, activationId, promise)
                case t: Throwable =>
                    error(this, s"[POST] action activation failed while waiting on result: ${t.getMessage}")
                    promise.tryFailure(t)
            }
        } else {
            error(this, s"[POST] action activation timed out, terminated polling for result")
        }
    }

    /**
     * Lists actions in package or binding. The router authorized the subject for the package
     * (if binding, then authorized subject for both the binding and the references package)
     * and iff authorized, this method is reached to lists actions.
     *
     * Note that when listing actions in a binding, the namespace on the actions will be that
     * of the referenced packaged, not the binding.
     */
    private def listPackageActions(subject: Subject, ns: Namespace, pkgname: EntityName)(implicit transid: TransactionId) = {
        // get the package to determine if it is a package or reference
        // (this will set the appropriate namespace), and then list actions
        // NOTE: these fetches are redundant with those from the authorization
        // and should hit the cache to ameliorate the cost; this can be improved
        // but requires communicating back from the authorization service the
        // resolved namespace
        val docid = DocId(WhiskEntity.qualifiedName(ns, pkgname))
        getEntity(WhiskPackage, entityStore, docid, Some { (wp: WhiskPackage) =>
            val pkgns = wp.binding map { b =>
                info(this, s"list actions in package binding '${wp.name}' -> '$b'")
                b.namespace.addpath(b.name)
            } getOrElse {
                info(this, s"list actions in package '${wp.name}'")
                ns.addpath(wp.name)
            }

            // list actions in resolved namespace
            // NOTE: excludePrivate is false since the subject is authorize to access
            // the package; in the future, may wish to exclude private actions in a
            // public package instead
            list(pkgns, excludePrivate = false)
        })
    }

    /**
     * Constructs a WhiskPackage that is a merger of a package with its packing binding (if any).
     * This resolves a reference versus an actual package and merge parameters as needed.
     * Once the package is resolved, the operation is dispatched to the action in the package
     * namespace.
     */
    private def mergeActionWithPackageAndDispatch(method: HttpMethod, user: WhiskAuth, action: EntityName, ref: Option[WhiskPackage] = None)(wp: WhiskPackage)(
        implicit transid: TransactionId): RequestContext => Unit = {
        wp.binding map {
            case Binding(ns, n) =>
                val docid = DocId(WhiskEntity.qualifiedName(ns, n))
                info(this, s"fetching package '$docid' for reference")
                // already checked that subject is authorized for package and binding;
                // this fetch is redundant but should hit the cache to ameliorate cost
                getEntity(WhiskPackage, entityStore, docid, Some {
                    mergeActionWithPackageAndDispatch(method, user, action, Some { wp }) _
                })
        } getOrElse {
            // a subject has implied rights to all resources in a package, so dispatch
            // operation without further entitlement checks
            val params = { ref map { _ inherit wp.parameters } getOrElse wp } parameters
            val ns = wp.namespace.addpath(wp.name) // the package namespace
            val resource = Resource(ns, collection, Some { action() }, Some { params })
            val right = collection.determineRight(method, resource.entity)
            info(this, s"merged package parameters and rebased action to '$ns")
            dispatchOp(user, right, resource)
        }
    }

    /** Grace period after action timeout limit to poll for result. */
    private val blockingInvokeGrace = 5 seconds
}

// exceptions that can arise in Futures dealing with action invocation
private case class BlockingInvokeTimeout(activationId: ActivationId) extends TimeoutException
protected[controller] case class TooManyActivationException(subject: String) extends Exception
// exceptions that can arise when dealing with the execution of a sequence
// retrieving one of the actions within a sequence fails
private case class SequenceComponentRetrieveException(component: String) extends Exception
// the execution of an action within a sequence leads to producing a dictionary that contains an "error" field
private case class SequenceIntermediateActionError(component: String) extends Exception
// the execution of an action within a sequence took too long
private case class SequenceActionTimeout(component: String) extends Exception
