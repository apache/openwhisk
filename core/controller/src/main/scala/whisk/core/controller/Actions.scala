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

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
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
import spray.http.StatusCodes.RequestEntityTooLarge
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol._
import spray.json._
import spray.routing.RequestContext
import org.apache.kafka.common.errors.RecordTooLargeException

import whisk.common.LoggingMarkers
import whisk.common.StartMarker
import whisk.common.TransactionId
import whisk.common.PrintStreamEmitter
import whisk.core.database.NoDocumentException
import whisk.core.entity._
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.EntityStore
import whisk.core.entitlement.Collection
import whisk.core.entitlement.Privilege
import whisk.core.connector.ActivationMessage
import whisk.core.entitlement.Resource
import whisk.core.WhiskConfig
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages._
import whisk.utils.ExecutionContextFactory.FutureExtensions
import whisk.http.Messages
import whisk.core.database.DocumentTypeMismatchException

import java.time.Instant


/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the actions API.
 */
object WhiskActionsApi {
    def requiredProperties = WhiskServices.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties ++
        Map(WhiskConfig.actionSequenceDefaultLimit -> null)
}

/** A trait implementing the actions API. */
trait WhiskActionsApi extends WhiskCollectionAPI {
    services: WhiskServices =>

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
    protected override def innerRoutes(user: Identity, ns: EntityPath)(implicit transid: TransactionId) = {
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
                    authorizeAndContinue(Privilege.READ, user, resource, next = () => listPackageActions(user.subject, ns, EntityName(outername)))
                } ~ (entityPrefix & pathEnd) { segment =>
                    entityname(segment) { innername =>
                        // matched /namespace/collection/package-name/action-name
                        // this is an action in a named package
                        val packageDocId = DocId(WhiskEntity.qualifiedName(ns, EntityName(outername)))
                        val packageResource = Resource(ns, Collection(Collection.PACKAGES), Some(outername))
                        m match {
                            case GET | POST =>
                                // need to merge package with action, hence authorize subject for package
                                // access (if binding, then subject must be authorized for both the binding
                                // and the referenced package)
                                //
                                // NOTE: it is an error if either the package or the action does not exist,
                                // the former manifests as unauthorized and the latter as not found
                                //
                                // a GET (READ) and POST (ACTIVATE) resolve to a READ right on the package;
                                // it may be desirable to separate these but currently the PACKAGES collection
                                // does not allow ACTIVATE since it does not make sense to activate a package
                                // but rather an action in the package
                                authorizeAndContinue(Privilege.READ, user, packageResource, next = () => {
                                    getEntity(WhiskPackage, entityStore, packageDocId, Some {
                                        mergeActionWithPackageAndDispatch(m, user, EntityName(innername)) _
                                    })
                                })
                            case PUT | DELETE =>
                                // these packaged action operations do not need merging with the package,
                                // but may not be permitted if this is a binding, or if the subject does
                                // not have PUT and DELETE rights to the package itself
                                val right = collection.determineRight(m, Some { innername })
                                authorizeAndContinue(right, user, packageResource, next = () => {
                                    getEntity(WhiskPackage, entityStore, packageDocId, Some { wp: WhiskPackage =>
                                        wp.binding map {
                                            _ => terminate(BadRequest, Messages.notAllowedOnBinding)
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
    override def create(user: Identity, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskActionPut]) { content =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                def doput = {
                    putEntity(WhiskAction, entityStore, docid, overwrite,
                        update(user, content)_, () => { make(user, namespace, content, name) })
                }

                content.exec match {
                    case Some(seq @ SequenceExec(_, components)) =>
                        info(this, "checking if sequence components are accessible")
                        val referencedEntities = resolveDefaultNamespace(seq, user).components.map {
                            c => Resource(c.path, Collection(Collection.ACTIONS), Some(c.name()))
                        }.toSet
                        authorizeAndContinue(Privilege.READ, user, referencedEntities, doput) {
                            case authorizationFailure: Throwable =>
                                val r = rewriteFailure(authorizationFailure)
                                terminate(r.code, r.message)
                        }
                    case _ => doput
                }
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
    override def activate(user: Identity, namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        parameter('blocking ? false, 'result ? false) { (blocking, result) =>
            entity(as[Option[JsObject]]) { payload =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                getEntity(WhiskAction, entityStore, docid, Some {
                    action: WhiskAction =>
                        def doActivate = {
                            transid.started(this, if (blocking) LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING else LoggingMarkers.CONTROLLER_ACTIVATION)
                            val postToLoadBalancer = {
                                action.exec match {
                                    // this is a topmost sequence
                                    case SequenceExec(_, components) =>
                                        val futureSeqTuple = invokeSequence(user, action, payload, blocking, topmost = true, components, cause = None, 0)
                                        futureSeqTuple map { case (activationId, wskActivation, _) => (activationId, wskActivation) }
                                    case _ => {
                                        val duration = action.limits.timeout()
                                        val timeout = (maxWaitForBlockingActivation min duration) + blockingInvokeGrace
                                        postInvokeRequest(user, action, env, payload, timeout, blocking)
                                    }
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
                                case Failure(t: RecordTooLargeException) =>
                                    info(this, s"[POST] action payload was too large")
                                    terminate(RequestEntityTooLarge)
                                case Failure(t: Throwable) =>
                                    error(this, s"[POST] action activation failed: ${t.getMessage}")
                                    terminate(InternalServerError, t.getMessage)
                            }
                        }

                        action.exec match {
                            case seq @ SequenceExec(_, components) =>
                                info(this, "checking if sequence components are accessible")
                                val referencedEntities = resolveDefaultNamespace(seq, user).components.map {
                                    c => Resource(c.path, Collection(Collection.ACTIONS), Some(c.name()))
                                }.toSet
                                authorizeAndContinue(Privilege.ACTIVATE, user, referencedEntities, doActivate) {
                                    case authorizationFailure: Throwable =>
                                        val r = rewriteFailure(authorizationFailure)
                                        terminate(r.code, r.message)
                                }
                            case _ => doActivate
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
    override def remove(namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
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
    override def fetch(namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        getEntity(WhiskAction, entityStore, docid, Some { action: WhiskAction =>
            val mergedAction = env map { action inherit _ } getOrElse action
            complete(OK, mergedAction)
        })
    }

    /**
     * Gets all actions in a path.
     *
     * Responses are one of (Code, Message)
     * - 200 [] or [WhiskAction as JSON]
     * - 500 Internal Server Error
     */
    override def list(namespace: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId) = {
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

    /** Replaces default namespaces in a vector of components from a sequence with appropriate namespace. */
    private def resolveDefaultNamespace(components: Vector[FullyQualifiedEntityName], user: Identity): Vector[FullyQualifiedEntityName] = {
        // if components are part of the default namespace, they contain `_`; replace it!
        val resolvedComponents = components map { c => FullyQualifiedEntityName(c.path.resolveNamespace(user.namespace), c.name) }
        resolvedComponents
    }

    /** Replaces default namespaces in an action sequence with appropriate namespace. */
    private def resolveDefaultNamespace(seq: SequenceExec, user: Identity): SequenceExec = {
        // if components are part of the default namespace, they contain `_`; replace it!
        val resolvedComponents = resolveDefaultNamespace(seq.components, user)
        new SequenceExec(seq.code, resolvedComponents)
    }

    /** Creates a WhiskAction from PUT content, generating default values where necessary. */
    private def make(user: Identity, namespace: EntityPath, content: WhiskActionPut, name: EntityName)(implicit transid: TransactionId) = {
        content.exec map {
            case seq: SequenceExec =>
                // if this is a sequence, rewrite the action names in the sequence to resolve the namespace if necessary
                // and check that the sequence conforms to max length and no recursion rules
                val fixedExec = resolveDefaultNamespace(seq, user)
                checkSequenceActionLimits(FullyQualifiedEntityName(namespace, name), fixedExec.components) map {
                    _ => makeWhiskAction(content.replace(fixedExec), namespace, name)
                }
            case _ => Future successful { makeWhiskAction(content, namespace, name) }
        } getOrElse Future.failed(RejectRequest(BadRequest, "exec undefined"))
    }

    /**
     * Creates a WhiskAction instance from the PUT request.
     */
    private def makeWhiskAction(content: WhiskActionPut, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        val exec = content.exec.get
        val limits = content.limits map { l =>
            ActionLimits(
                l.timeout getOrElse TimeLimit(),
                l.memory getOrElse MemoryLimit(),
                l.logs getOrElse LogLimit())
        } getOrElse ActionLimits()
        // This is temporary while we are making sequencing directly supported in the controller.
        // The parameter override allows this to work with Pipecode.code. Any parameters other
        // than the action sequence itself are discarded and have no effect.
        // Note: While changing the implementation of sequences, components now store the fully qualified entity names
        // (which loses the leading "/"). Adding it back while both versions of the code are in place.
        val parameters = exec match {
            case seq: SequenceExec => Parameters("_actions", JsArray(seq.components map { _.qualifiedNameWithLeadingSlash.toJson }))
            case _                 => content.parameters getOrElse Parameters()
        }

        WhiskAction(
            namespace,
            name,
            exec,
            parameters,
            limits,
            content.version getOrElse SemVer(),
            content.publish getOrElse false,
            (content.annotations getOrElse Parameters()) ++ execAnnotation(exec))
    }

    /** Updates a WhiskAction from PUT content, merging old action where necessary. */
    private def update(user: Identity, content: WhiskActionPut)(action: WhiskAction)(implicit transid: TransactionId) = {
        content.exec map {
            case seq: SequenceExec =>
                // if this is a sequence, rewrite the action names in the sequence to resolve the namespace if necessary
                // and check that the sequence conforms to max length and no recursion rules
                val fixedExec = resolveDefaultNamespace(seq, user)
                checkSequenceActionLimits(FullyQualifiedEntityName(action.namespace, action.name), fixedExec.components) map {
                    _ => updateWhiskAction(content.replace(fixedExec), action)
                }
            case _ => Future successful { updateWhiskAction(content, action) }
        } getOrElse {
            Future successful { updateWhiskAction(content, action) }
        }
    }

    /**
     * Updates a WhiskAction instance from the PUT request.
     */
    private def updateWhiskAction(content: WhiskActionPut, action: WhiskAction)(implicit transid: TransactionId) = {
        val limits = content.limits map { l =>
            ActionLimits(l.timeout getOrElse action.limits.timeout, l.memory getOrElse action.limits.memory, l.logs getOrElse action.limits.logs)
        } getOrElse action.limits

        // This is temporary while we are making sequencing directly supported in the controller.
        // Actions that are updated with a sequence will have their parameter property overridden.
        // Actions that are updated with non-sequence actions will either set the parameter property according to
        // the content provided, or if that is not defined, and iff the previous version of the action was not a
        // sequence, inherit previous parameters. This is because sequence parameters are special and should not
        // leak to non-sequence actions.
        // If updating an action but not specifying a new exec type, then preserve the previous parameters if the
        // existing type of the action is a sequence (regardless of what parameters may be defined in the content)
        // otherwise, parameters are inferred from the content or previous values.
        // Note: While changing the implementation of sequences, components now store the fully qualified entity names
        // (which loses the leading "/"). Adding it back while both versions of the code are in place. This will disappear completely
        // once the version of sequences with "pipe.js" is removed.
        val parameters = content.exec map {
            case seq: SequenceExec => Parameters("_actions", JsArray(seq.components map { c => JsString("/" + c.toString) }))
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

        val exec = content.exec getOrElse action.exec

        WhiskAction(
            action.namespace,
            action.name,
            exec,
            parameters,
            limits,
            content.version getOrElse action.version.upPatch,
            content.publish getOrElse action.publish,
            (content.annotations getOrElse action.annotations) ++ execAnnotation(exec)).
            revision[WhiskAction](action.docinfo.rev)
    }

    /**
     * Gets document from datastore to confirm a valid action activation then posts request to loadbalancer.
     * If the loadbalancer accepts the requests with an activation id, then wait for the result of the activation
     * if this is a blocking invoke, else return the activation id.
     *
     * NOTE: This is a point-in-time type of statement:
     * For activations of actions, cause is populated only for actions that were invoked as a result of a sequence activation.
     * For actions that are enclosed in a sequence and are activated as a result of the sequence activation, the cause
     * contains the activation id of the immediately enclosing sequence.
     * e.g.,: s -> a, x, c    and   x -> c  (x and s are sequences, a, b, c atomic actions)
     * cause for a, x, c is the activation id of s
     * cause for c is the activation id of x
     * cause for s is not defined
     *
     * @param subject the subject invoking the action
     * @param docid the action document id
     * @param env the merged parameters from the package/reference if any
     * @param payload the dynamic arguments for the activation
     * @param timeout the timeout used for polling for result if the invoke is blocking
     * @param blocking true iff this is a blocking invoke
     * @param cause the activation id that is responsible for this invoke/activation
     * @param transid a transaction id for logging
     * @return a promise that completes with (ActivationId, Some(WhiskActivation)) if blocking else (ActivationId, None)
     */
    private def postInvokeRequest(
        user: Identity,
        action: WhiskAction,
        env: Option[Parameters],
        payload: Option[JsObject],
        timeout: FiniteDuration,
        blocking: Boolean,
        cause: Option[ActivationId] = None)(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        // merge package parameters with action (action parameters supersede), then merge in payload
        val args = { env map { _ ++ action.parameters } getOrElse action.parameters } merge payload
        val message = ActivationMessage(
            transid,
            FullyQualifiedEntityName(action.namespace, action.name, Some(action.version)),
            action.rev,
            user.subject,
            user.authkey,
            activationIdFactory.make(),  // activation id created here
            activationNamespace = user.namespace.toPath,
            args,
            cause = cause)

        val start = transid.started(this, LoggingMarkers.CONTROLLER_LOADBALANCER, s"[POST] action activation id: ${message.activationId}")
        val (postedFuture, activationResponse) = loadBalancer.publish(message, activeAckTimeout)
        postedFuture flatMap { _ =>
            transid.finished(this, start)
            if (blocking) {
//                val duration = action.limits.timeout()
//                val timeout = (maxWaitForBlockingActivation min duration) + blockingInvokeGrace
                waitForActivationResponse(user, message.activationId, timeout, activationResponse) map {
                    whiskActivation => (whiskActivation.activationId, Some(whiskActivation))
                }
            } else {
                // Duration of the non-blocking activation in Controller.
                // We use the start time of the tid instead of a startMarker to avoid passing the start marker around.
                transid.finished(this, StartMarker(transid.meta.start, LoggingMarkers.CONTROLLER_ACTIVATION))
                Future.successful { (message.activationId, None) }
            }
        }
    }

    /**
     * This is a fast path used for blocking calls in which we do not need the full WhiskActivation record from the DB.
     * Polls for the activation response from an underlying data structure populated from Kafka active acknowledgements.
     * If this mechanism fails to produce an answer quickly, the future will switch to polling the database for the response
     * record.
     */
    private def waitForActivationResponse(user: Identity, activationId: ActivationId, totalWaitTime: FiniteDuration, activationResponse: Future[WhiskActivation])(implicit transid: TransactionId) = {
        // this is the promise which active ack or db polling will try to complete in one of four ways:
        // 1. active ack response
        // 2. failing active ack (due to active ack timeout), fall over to db polling
        // 3. timeout on db polling => converts activation to non-blocking (returns activation id only)
        // 4. internal error
        val promise = Promise[WhiskActivation]
        val docid = DocId(WhiskEntity.qualifiedName(user.namespace.toPath, activationId))

        info(this, s"[POST] action activation will block on result up to $totalWaitTime")

        // the active ack will timeout after specified duration, causing the db polling to kick in
        activationResponse map {
            activation => promise.trySuccess(activation)
        } onFailure {
            case t: TimeoutException =>
                info(this, s"[POST] switching to poll db, active ack expired")
                pollDbForResult(docid, activationId, promise)
            case t: Throwable =>
                error(this, s"[POST] switching to poll db, active ack exception: ${t.getMessage}")
                pollDbForResult(docid, activationId, promise)
        }

        val response = promise.future withTimeout (totalWaitTime, new BlockingInvokeTimeout(activationId))

        response onComplete {
            case Success(_) =>
                // Duration of the blocking activation in Controller.
                // We use the start time of the tid instead of a startMarker to avoid passing the start marker around.
                transid.finished(this, StartMarker(transid.meta.start, LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING))
            case Failure(t) =>
                // short circuits db polling
                promise.tryFailure(t)
        }

        response // will either complete with activation or fail with timeout
    }

    /**
     * Polls for activation record. It is assumed that an activation record is created atomically and never updated.
     * Fetch the activation record by its id. If it exists, complete the promise. Otherwise recursively poll until
     * either there is an error in the get, or the promise has completed because it timed out. The promise MUST
     * complete in the caller to terminate the polling.
     */
    private def pollDbForResult(
        docid: DocId,
        activationId: ActivationId,
        promise: Promise[WhiskActivation])(
            implicit transid: TransactionId): Unit = {
        // check if promise already completed due to timeout expiration (abort polling if so)
        if (!promise.isCompleted) {
            WhiskActivation.get(activationStore, docid) map {
                activation => promise.trySuccess(activation.withoutLogs) // Logs always not provided on blocking call
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
    private def listPackageActions(subject: Subject, ns: EntityPath, pkgname: EntityName)(implicit transid: TransactionId) = {
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
    private def mergeActionWithPackageAndDispatch(method: HttpMethod, user: Identity, action: EntityName, ref: Option[WhiskPackage] = None)(wp: WhiskPackage)(
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

    /**
     * Checks that the sequence is not cyclic and that the number of atomic actions in the "inlined" sequence is lower than max allowed.
     *
     * @param sequenceAction is the action sequence to check
     * @param components the components of the sequence
     */
    private def checkSequenceActionLimits(sequenceAction: FullyQualifiedEntityName, components: Vector[FullyQualifiedEntityName])(
        implicit transid: TransactionId): Future[Unit] = {
        // first checks that current sequence length is allowed
        // then traverses all actions in the sequence, inlining any that are sequences
        val future = if (components.size > actionSequenceLimit) {
            Future.failed(TooManyActionsInSequence())
        } else {
            // resolve the action document id (if it's in a package/binding);
            // this assumes that entityStore is the same for actions and packages
            WhiskAction.resolveAction(entityStore, sequenceAction) flatMap { resolvedSeq =>
                val atomicActionCnt = countAtomicActionsAndCheckCycle(resolvedSeq, components)
                atomicActionCnt map { count =>
                    debug(this, s"sequence '$sequenceAction' atomic action count $count")
                    if (count > actionSequenceLimit) {
                        throw TooManyActionsInSequence()
                    }
                }
            }
        }

        future recoverWith {
            case _: TooManyActionsInSequence => Future failed RejectRequest(BadRequest, sequenceIsTooLong)
            case _: SequenceWithCycle        => Future failed RejectRequest(BadRequest, sequenceIsCyclic)
            case _: NoDocumentException      => Future failed RejectRequest(BadRequest, sequenceComponentNotFound)
        }
    }

    /**
     * Counts the number of atomic actions in a sequence and checks for potential cycles. The latter is done
     * by inlining any sequence components that are themselves sequences and checking if there if a reference to
     * the given original sequence.
     *
     * @param origSequence the original sequence that is updated/created which generated the checks
     * @param the components of the a sequence to check if they reference the original sequence
     * @return Future with the number of atomic actions in the current sequence or an appropriate error if there is a cycle or a non-existent action reference
     */
    private def countAtomicActionsAndCheckCycle(origSequence: FullyQualifiedEntityName, components: Vector[FullyQualifiedEntityName])(
        implicit transid: TransactionId): Future[Int] = {
        if (components.size > actionSequenceLimit) {
            Future.failed(TooManyActionsInSequence())
        } else {
            // resolve components wrt any package bindings
            val resolvedComponentsFutures = components map { c => WhiskAction.resolveAction(entityStore, c) }
            // traverse the sequence structure by checking each of its components and do the following:
            // 1. check whether any action (sequence or not) referred by the sequence (directly or indirectly)
            //    is the same as the original sequence (aka origSequence)
            // 2. count the atomic actions each component has (by "inlining" all sequences)
            val actionCountsFutures = resolvedComponentsFutures map {
                _ flatMap { resolvedComponent =>
                    // check whether this component is the same as origSequence
                    // this can happen when updating an atomic action to become a sequence
                    if (origSequence == resolvedComponent) {
                        Future failed SequenceWithCycle()
                    } else {
                        // check whether component is a sequence or an atomic action
                        // if the component does not exist, the future will fail with appropriate error
                        WhiskAction.get(entityStore, resolvedComponent.toDocId) flatMap { wskComponent =>
                            wskComponent.exec match {
                                case SequenceExec(_, seqComponents) =>
                                    // sequence action, count the number of atomic actions in this sequence
                                    countAtomicActionsAndCheckCycle(origSequence, seqComponents)
                                case _ => Future successful 1 // atomic action count is one
                            }
                        }
                    }
                }
            }
            // collapse the futures in one future
            val actionCountsFuture = Future.sequence(actionCountsFutures)
            // sum up all individual action counts per component
            val totalActionCount = actionCountsFuture map { actionCounts => actionCounts.foldLeft(0)(_ + _) }
            totalActionCount
        }
    }

    /**
     * Constructs an "exec" annotation. This is redundant with the exec kind
     * information available in WhiskAction but necessary for some clients which
     * fetch action lists but cannot determine action kinds without fetching them.
     * An alternative is to include the exec in the action list "view" but this
     * will require an API change. So using an annotation instead.
     */
    private def execAnnotation(exec: Exec): Parameters = {
        Parameters(WhiskAction.execFieldName, exec.kind)
    }

    private def rewriteFailure(failure: Throwable)(implicit transid: TransactionId) = {
        info(this, s"rewriting failure $failure")
        failure match {
            case RejectRequest(NotFound, _)       => RejectRequest(NotFound)
            case RejectRequest(c, m)              => RejectRequest(c, m)
            case _: NoDocumentException           => RejectRequest(BadRequest)
            case _: DocumentTypeMismatchException => RejectRequest(BadRequest)
            case _                                => RejectRequest(BadRequest, failure)
        }
    }

    /** Begin Sequence invocation/activation code */

    /**
     * Executes a sequence by invoking in a blocking fashion each of its components.
     *
     * @param user the user invoking the action
     * @param action the sequence action to be invoked
     * @param env the merged parameters from the package/reference if any TODO: check ignoring these params
     * @param payload the dynamic arguments for the activation
     * @param blocking true iff this is a blocking invoke
     * @param topmost true iff this is the topmost sequence invoked directly through the api (not indirectly through a sequence)
     * @param components the actions in the sequence
     * @param cause the id of the activation that caused this sequence (defined only for inner sequences and None for topmost sequences)
     * @param atomicActionsCount the dynamic atomic action count observed so far since the start of invocation of the topmost sequence(0 if topmost)
     * @param transid a transaction id for logging
     * @return a future of type (ActivationId, Some(WhiskActivation), atomicActionsCount) if blocking; else (ActivationId, None, 0)
     */
    private def invokeSequence(
            user: Identity,
            action: WhiskAction,
            //env: Option[Parameters],
            payload: Option[JsObject],
            blocking: Boolean,
            topmost: Boolean,
            components: Vector[FullyQualifiedEntityName],
            cause: Option[ActivationId],
            atomicActionsCount: Int)(implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation], Int)] = {
        // create new activation id that corresponds to the sequence
        val seqActivationId = activationIdFactory.make()
        info(this, s"Invoking sequence $action topmost $topmost activationid '$seqActivationId'")
        val start = Instant.now(Clock.systemUTC())
        var seqActivation: Option[WhiskActivation] = None
        // the cause for the component activations is the current sequence
        val futureWskActivations = invokeSequenceComponents(user, action, seqActivationId, payload, components, cause = Some(seqActivationId), atomicActionsCount)
        val futureSeqResult = Future.sequence(futureWskActivations)
        val response: Future[(ActivationId, Option[WhiskActivation], Int)] =
            if (topmost) { // need to deal with blocking and closing connection
                if (blocking) {
                    val timeout = maxWaitForBlockingActivation + blockingInvokeGrace
                    val futureSeqResultTimeout = futureSeqResult withTimeout(timeout, new BlockingInvokeTimeout(seqActivationId))
                    // if the future fails with a timeout, the failure is dealt with at the caller level
                    futureSeqResultTimeout map { wskActivationTuples =>
                        val wskActivationEithers = wskActivationTuples.map(_._1)
                        // the execution of the sequence was successful, return the result
                        val end = Instant.now(Clock.systemUTC())
                        seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end))
                        val atomicActionCnt = wskActivationTuples.last._2
                        (seqActivationId, seqActivation, atomicActionCnt)
                    }
                } else {
                    // non-blocking sequence execution, return activation id
                    Future.successful((seqActivationId, None, 0))
                }
            } else {
                // not topmost, no need to worry about terminating incoming request
                futureSeqResult map { wskActivationTuples =>
                     val wskActivationEithers = wskActivationTuples.map(_._1)
                    // all activations are successful, the result of the sequence is the result of the last activation
                    val end = Instant.now(Clock.systemUTC())
                    seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end))
                    val atomicActionCnt = wskActivationTuples.last._2
                    (seqActivationId, seqActivation, atomicActionCnt)
                }
            }

        // store result of sequence execution
        // if seqActivation is defined, use it; otherwise create it (e.g., for non-blocking activations)
        // the execution can reach here without a seqActivation due to non-blocking activations OR blocking activations that reach the blocking invoke timeout
        // futureSeqResult should always be successful, if failed, there is an error
        futureSeqResult onComplete {
            case Success(wskActivationTuples) =>
                // all activations were successful
                val activation = seqActivation getOrElse {
                    val wskActivationEithers = wskActivationTuples.map(_._1)
                    val end = Instant.now(Clock.systemUTC())
                    // the response of the sequence is the response of the very last activation
                    makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end)
                }
                storeSequenceActivation(activation)
            case Failure(t: Throwable) =>
                // consider this whisk error
                error(this, s"Sequence activation 'seqActivationId' failed: ${t.getMessage}")
                // seqActivation should not be defined
                if (seqActivation.isDefined)
                    error(this, s"Sequence activation defined $seqActivation although activation failed with unexpected error")
                // TODO shall we attempt storing the activation if it exists or even inspect the futures? this should be a pretty serious whisk errror if it gets here
        }

        response
    }

    /**
     * store sequence activation to database
     */
    private def storeSequenceActivation(activation: WhiskActivation)(implicit transid: TransactionId): Unit = {
        WhiskActivation.put(activationStore, activation) onComplete {
            case Success(id) => info(this, s"recorded activation")
            case Failure(t)  => error(this, s"failed to record activation")
        }
    }

    /**
     * create an activation for a sequence
     */
    private def makeSequenceActivation(
            user: Identity,
            action: WhiskAction,
            activationId: ActivationId,
            wskActivationEithers: Vector[Either[ActivationResponse, WhiskActivation]],
            topmost: Boolean,
            cause: Option[ActivationId],
            start: Instant,
            end: Instant) : WhiskActivation = {
        // extract all successful activations from the vector of activation eithers
        val leftIndex = wskActivationEithers.indexWhere {
            case Left(_) => true
            case _ => false
        }

        val wskActivations = if (leftIndex == -1) {
            // all the eithers are right
            wskActivationEithers.map(_.right.get)
        } else {
            // slice the vector to get all the rights
            wskActivationEithers.slice(0, leftIndex).map(_.right.get)
        }

        // the activation response is either the first left if it exists or the response of the last successful activation
        val activationResponse = if (leftIndex == -1) wskActivations.last.response else wskActivationEithers(leftIndex).left.get

        // compose logs
        val logs = ActivationLogs(wskActivations map { activation => activation.activationId.toString })
        // compute duration
        val duration = (wskActivations map { activation =>
            if (activation.duration.isEmpty) {
               error(this, s"duration for $activation is not defined")
               activation.end.toEpochMilli - activation.start.toEpochMilli
            } else
                activation.duration.get
        }).sum

        // compute max memory
        val maxMemoryOption = Try {
            val memoryLimits = wskActivations map { activation =>
                activation.annotations("limits").get.asJsObject.getFields("memory")(0).convertTo[Long]
            }
            memoryLimits.max
        } toOption
        val maxMemory = maxMemoryOption getOrElse 0L
        // set causedBy if not topmost sequence
        val causedBy = if (!topmost) Parameters("causedBy", JsString("sequence")) else Parameters()
        // create the whisk activation
        val activation = WhiskActivation(
                namespace = user.namespace.toPath,  // TODO: double-check on this
                name = action.name,
                user.subject,
                activationId = activationId,
                start = start,
                end = end,
                cause = if (topmost) None else cause,  // propagate the cause for inner sequences, but undefined for topmost
                response = activationResponse,
                logs = logs,
                version = action.version,
                publish = false,
                annotations = Parameters("topmost", JsBoolean(topmost)) ++
                              Parameters("kind", "sequence") ++
                              causedBy ++
                              Parameters("limits", JsObject("memory" -> JsNumber(maxMemory))),
                duration = Some(duration))
        activation
    }

    /**
     * Invokes the components of a sequence in a blocking fashion.
     * Returns a vector of successful futures containing the results of the invocation of all components in the sequence.
     * Unexpected behavior is modeled through an Either with activation(right) or activation response in case of error (left).
     *
     * Keeps track of the dynamic atomic action count.
     * @param user the user invoking the sequence
     * @param seqAction the sequence invoked
     * @param seqActivationId the id of the sequence
     * @param payload the payload passed to the first component in the sequence
     * @param components the components in the sequence
     * @param cause the activation id of the sequence that lead to invoking this sequence or None if this sequence is topmost
     * @param atomicActionCnt the dynamic atomic action count observed so far since the start of the execution of the topmost sequence
     * @return a vector of successful futures; each element contains a tuple with
     *         1. an either with activation(right) or activation response in case of error (left)
     *         2. the dynamic atomic action count after executing the components
     */
    private def invokeSequenceComponents(
            user: Identity,
            seqAction: WhiskAction,
            seqActivationId: ActivationId,
            // env: Option[Parameters],  // env are the parameters for the package that the sequence is in; throw them away, not used in the sequence execution
            payload: Option[JsObject],
            components: Vector[FullyQualifiedEntityName],
            cause: Option[ActivationId],
            atomicActionCnt: Int)(
                    implicit transid: TransactionId): Vector[Future[(Either[ActivationResponse, WhiskActivation], Int)]] = {
        info(this, s"invoke sequence $seqAction with components $components")
        // first retrieve the information/entities on all actions
        // do not wait to successfully retrieve all the actions before starting the execution
        // start execution of the first action while potentially still retrieving entities
        // Note: the execution starts even if one of the futures retrieving an entity may fail
        // first components need to be resolved given any package bindings and the params need to be merged
        // NOTE: OLD-STYLE sequences may have default namespace in the names of the components, resolve default namespace first
        val resolvedFutureActions = resolveDefaultNamespace(components, user) map { c => WhiskAction.resolveActionAndMergeParameters(entityStore, c) }
        // "scan" the wskActions to execute them in blocking fashion
        // use scanLeft instead of foldLeft as we need the intermediate results
        // TODO: double-check the package param policy
        // env are the parameters for the package that the sequence is in; throw them away, not used in the sequence execution
        // create a "fake" WhiskActivation to hold the payload of the sequence to init the scanLeft
        val fakeStart = Instant.now()
        val fakeEnd = Instant.now()
        val fakeResponse = ActivationResponse.payloadPlaceholder(payload)
        // NOTE: the init value is a fake activation to bootstrap the invocations of actions; in case of error, the previous activation response is used; for this reason,
        // the fake init activation has as activation response application error - useful in the case the payload itself contains an error field, unused otherwise
        val initFakeWhiskActivation: Future[(Either[ActivationResponse, WhiskActivation], Int)] = Future successful {
            (Right(WhiskActivation(seqAction.namespace, seqAction.name, user.subject, seqActivationId, fakeStart, fakeEnd, response = fakeResponse, duration = None)), atomicActionCnt)
        }
        // seqComponentWskActivationFutures contains a fake activation on the first position in the vector; the rest of the vector is the result of each component execution/activation
        val seqComponentWskActivationFutures = resolvedFutureActions.scanLeft(initFakeWhiskActivation){ (futureActivationAtomicCntPair, futureAction) =>
            futureAction flatMap { action =>
                futureActivationAtomicCntPair flatMap { case (activationEither, atomicActionCount) =>
                    activationEither match {
                        case Right(activation) =>
                            val payload = activation.response.result.map(_.asJsObject)
                            // first check conditions on payload that may lead to interrupting the execution of the sequence
                            val payloadContent = payload getOrElse JsObject.empty
                            val errorFields = payloadContent.getFields(ActivationResponse.ERROR_FIELD)
                            if (errorFields.isEmpty) {
                                // second check the atomic action count for sequence action limit)
                                if (atomicActionCount >= actionSequenceLimit) {
                                    val activationResponse = ActivationResponse.applicationError(s"$sequenceIsTooLong")
                                    Future.successful(Left(activationResponse), atomicActionCount) // dynamic action count doesn't matter anymore
                                } else {
                                    invokeSeqOneComponent(user, action, payload, cause, atomicActionCount)
                                }
                            } else {
                                // there is an error field, terminate sequence early
                                // propagate the activation response
                                Future.successful(Left(activation.response), atomicActionCount) // dynamic action count doesn't matter anymore
                            }
                        case Left(activationResponse) =>
                            // the sequence is interrupted, no more processing
                            Future.successful(Left(activationResponse), 0) // dynamic action count does not matter from now on
                    }
                }
            } recover {
                // check any failure here and generate an activation response such that this method always returns a vector of successful futures
                case t: Throwable =>
                    // consider this failure a whisk error
                    val activationResponse = ActivationResponse.whiskError(s"Sequence activation error ${t.getMessage}")
                    (Left(activationResponse), 0)
            }
        }
        seqComponentWskActivationFutures.drop(1) // drop the first future which contains the init value from scanLeft
    }

    /**
     * Invokes one component from a sequence action. Unless an unexpected whisk failure happens, the future returned is always successful.
     * The return is a tuple of
     *       1. either an activation (right) or an activation response (left) in case the activation could not be retrieved
     *       2. the dynamic count of atomic actions observed so far since the start of the topmost sequence on behalf which this action is executing
     *
     * The method distinguishes between invoking a sequence or an atomic action.
     * @param user the user executing the sequence
     * @param action the action to be invoked
     * @param payload the payload for the action
     * @param cause the activation id of the first sequence containing this action
     * @param atomicActionCount the number of activations
     * @return future with the result of the invocation and the dynamic atomic action count so far
     */
    private def invokeSeqOneComponent(user: Identity, action: WhiskAction, payload: Option[JsObject], cause: Option[ActivationId], atomicActionCount: Int)(
            implicit transid: TransactionId): Future[(Either[ActivationResponse, WhiskActivation], Int)] = {
        // invoke the action by calling the right method depending on whether it's an atomic action or a sequence
        // the tuple contains activationId, wskActivation, atomicActionCount (up till this point in execution)
        val futureWhiskActivationTuple = action.exec match {
            case SequenceExec(_, components) =>
                // invoke a sequence
                info(this, s"sequence invoking an enclosing sequence $action")
                // call invokeSequence to invoke the inner sequence
                // true for blocking; false for topmost
                invokeSequence(user, action, payload, blocking = true, topmost = false, components, cause, atomicActionCount) map {
                    case (activationId, wskActivation, seqAtomicActionCnt) =>
                        (activationId, wskActivation, seqAtomicActionCnt + atomicActionCount)
                }
            case _ =>
                // this is an invoke for an atomic action --- blocking
                info(this, s"sequence invoking an enclosing atomic action $action")
                val timeout = action.limits.timeout() + blockingInvokeGrace  // TODO: shall we have a different grace since this is used for sequence components?
                // None is for env -- TODO double-check this
                postInvokeRequest(user, action, None, payload, timeout, true, cause) map {
                    case (activationId, wskActivation) => (activationId, wskActivation, atomicActionCount + 1)
                }
        }

        futureWhiskActivationTuple map {
            case (activationId, wskActivation, atomicActionCountSoFar) =>
                // the activation is None only if the activation could not be retrieved either from active ack or from db
                wskActivation match {
                    case Some(activation) => (Right(activation), atomicActionCountSoFar)
                    case None => {
                        val activationResponse = ActivationResponse.whiskError(s"$sequenceRetrieveActivationTimeout activation id '$activationId'")
                        (Left(activationResponse), atomicActionCountSoFar) // dynamic count doesn't matter, sequence will be interrupted
                    }
                }
        }
    }

    /** End Sequence invocation/activation code */

    /** Grace period after action timeout limit to poll for result. */
    private val blockingInvokeGrace = 5 seconds

    /** Max duration to wait for a blocking activation. */
    private val maxWaitForBlockingActivation = 60 seconds

    /** Max duration for active ack. */
    private val activeAckTimeout = 30 seconds

    /** Max atomic action count allowed for sequences */
    private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt
}

private case class BlockingInvokeTimeout(activationId: ActivationId) extends TimeoutException
private case class TooManyActionsInSequence() extends RuntimeException
private case class SequenceWithCycle() extends RuntimeException

