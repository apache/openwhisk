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
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
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
import spray.http.StatusCodes.OK
import spray.http.StatusCodes.Accepted
import spray.http.StatusCodes.RequestEntityTooLarge
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.{ JsArray, JsObject, JsString }
import spray.routing.RequestContext
import org.apache.kafka.common.errors.RecordTooLargeException

import whisk.common.LoggingMarkers
import whisk.common.StartMarker
import whisk.common.TransactionId
import whisk.common.PrintStreamEmitter
import whisk.core.database.NoDocumentException
import whisk.core.entity.ActionLimits
import whisk.core.entity.ActivationId
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.SequenceExec
import whisk.core.entity.MemoryLimit
import whisk.core.entity.EntityPath
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
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.entitlement.Resource
import whisk.core.entity.WhiskPackage
import whisk.core.entity.Binding
import whisk.core.entity.Subject
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.Identity
import whisk.core.WhiskConfig
import whisk.http.ErrorResponse.terminate
import whisk.utils.ExecutionContextFactory.FutureExtensions

/**
 * A singleton object which defines the properties that must be present in a configuration
 * in order to implement the actions API.
 */
object WhiskActionsApi {
    def requiredProperties = WhiskServices.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties ++
        Map(WhiskConfig.actionSequenceLimit -> null)

        val sequenceHackFlag = true   // a temporary flag that disables the old hack that runs sequences using Pipe.js
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
                    authorizeAndContinue(Privilege.READ, user, resource, next = () => {
                        listPackageActions(user.subject, ns, EntityName(outername))
                    })
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
    override def create(user: Identity, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskActionPut]) { content =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                putEntity(WhiskAction, entityStore, docid, overwrite, update(user, content)_, () => { make(user, namespace, content, name) })
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
                        transid.started(this, if (blocking) LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING else LoggingMarkers.CONTROLLER_ACTIVATION)
                        val postToLoadBalancer = postInvokeRequest(user, action, env, payload, blocking)
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

    /**
     * helper method that fixes the sequence components that are in the default namespace
     * replace default namespace witht the user namespace
     */
    private def resolveDefaultNamespace(seq: SequenceExec, user: Identity)(implicit transid: TransactionId): SequenceExec = {
        // if components are part of the default namespace, they contain `_`; replace it!
        val resolvedComponents = seq.components map { c => FullyQualifiedEntityName(c.path.resolveNamespace(user.namespace), c.name) }
        new SequenceExec(seq.code, resolvedComponents)
    }

    /** Creates a WhiskAction from PUT content, generating default values where necessary. */
    private def make(user: Identity, namespace: EntityPath, content: WhiskActionPut, name: EntityName)(implicit transid: TransactionId) = {
        content.exec map {
             // fix content if sequence and check for sequence limits
            case seq: SequenceExec =>
                val fixedExec = resolveDefaultNamespace(seq, user)
                val fixedContent = WhiskActionPut(Some(fixedExec), content.parameters, content.limits, content.version, content.publish, content.annotations)
                // also check for sequence limits
                checkSequenceActionLimits(FullyQualifiedEntityName(namespace, name), fixedExec.components)  map { _ =>
                    makeWhiskAction(fixedContent, namespace, name)
                }
            case _ => Future successful {makeWhiskAction(content, namespace, name)}
        } getOrElse Future.failed(RejectRequest(BadRequest, "exec undefined"))
    }

    /**
     * helper function that creates a whisk action
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
        // (which loses the leading "/"). Adding it back while both versions of the code are in place. This will disappear completely
        // once the version of sequences with "pipe.js" is removed.
        val parameters = exec match {
            case seq: SequenceExec => Parameters("_actions", JsArray(seq.components map {c => JsString("/" + c.toString) }))
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
            content.annotations getOrElse Parameters())
    }

    /** Updates a WhiskAction from PUT content, merging old action where necessary. */
    private def update(user: Identity, content: WhiskActionPut)(action: WhiskAction)(implicit transid: TransactionId) = {
        if (content.exec.isDefined) {
            // the exec is being updated, new checks in place
            content.exec.get match {
                case seq: SequenceExec =>
                    val fixedExec = resolveDefaultNamespace(seq, user)
                    val fixedContent = WhiskActionPut(Some(fixedExec), content.parameters, content.limits, content.version, content.publish, content.annotations)
                    checkSequenceActionLimits(FullyQualifiedEntityName(action.namespace, action.name), fixedExec.components) map { _ =>
                        updateWhiskAction(fixedContent, action)
                    }
                case _ => Future successful { updateWhiskAction(content, action) }
            }
        } else {
            // the content is not being updated, no checks for potential sequences
            Future successful { updateWhiskAction(content, action) }
        }
    }

    /**
     * helper method that updates a whisk action
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
     * If the loadbalancer accepts the requests with an activation id, then wait for the result of the activation
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
        user: Identity,
        action: WhiskAction,
        env: Option[Parameters],
        payload: Option[JsObject],
        blocking: Boolean)(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        // merge package parameters with action (action parameters supersede), then merge in payload
        val args = { env map { _ ++ action.parameters } getOrElse action.parameters } merge payload
        val message = Message(
            transid,
            FullyQualifiedEntityName(action.namespace, action.name, Some(action.version)),
            action.rev,
            user.subject,
            user.authkey,
            activationId.make(),
            activationNamespace = user.namespace.toPath,
            args)

        val start = transid.started(this, LoggingMarkers.CONTROLLER_LOADBALANCER, s"[POST] action activation id: ${message.activationId}")
        val (postedFuture, activationResponse) = loadBalancer.publish(message, activeAckTimeout)
        postedFuture flatMap { _ =>
            transid.finished(this, start)
            if (blocking) {
                val duration = action.limits.timeout()
                val timeout = (maxWaitForBlockingActivation min duration) + blockingInvokeGrace
                waitForActivationResponse(user, message, timeout, activationResponse)
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
    private def waitForActivationResponse(user: Identity, message: Message, totalWaitTime: FiniteDuration, activationResponse: Future[WhiskActivation])(implicit transid: TransactionId) = {
        // this is the promise which active ack or db polling will try to complete in one of four ways:
        // 1. active ack response
        // 2. failing active ack (due to active ack timeout), fall over to db polling
        // 3. timeout on db polling => converts activation to non-blocking (returns activation id only)
        // 4. internal error
        val promise = Promise[WhiskActivation]
        val docid = DocId(WhiskEntity.qualifiedName(user.namespace.toPath, message.activationId))
        val activationId = message.activationId

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

        val response = promise.future map {
            result => (activationId, Some(result))
        } withTimeout (totalWaitTime, new BlockingInvokeTimeout(message.activationId))

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
     * helper method that checks the number of 'inlined' actions for a sequence is less than a threshold and checks recursion
     * @param sequence the sequence to check
     * @param components the components of the sequence
     */
    private def checkSequenceActionLimits(sequence: FullyQualifiedEntityName, components: Vector[FullyQualifiedEntityName]) (
        implicit transid: TransactionId): Future[Boolean] = {
        // traverse all actions and "inline" all actions that are sequences
        // keep track of all sequences to detect recursion
        // first check that out of the box no more components than necessary
        val future = if (components.size > whiskConfig.actionSequenceLimit.toInt) {
            Future.failed(new TooManyActionsInSequence())
        } else {
            val resolvedSequence = WhiskAction.resolveAction(entityStore, sequence) // this assumes that entityStore is the same for actions and packages
            val resolvedComponents = components map { c => WhiskAction.resolveAction(entityStore, c) }
            val futureResComponents = Future.sequence(resolvedComponents)
            futureResComponents flatMap { resComponents =>
                // start inlining while keeping track of all sequences to check for recursion
                resolvedSequence flatMap { sequence =>
                    inlineComponentsAndCountAtomicActions(0, resComponents.toList, List(sequence))
                }
            }
        }

        future recoverWith {
            case _: TooManyActionsInSequence =>
                Future failed RejectRequest(BadRequest, "too many actions in sequence")
            case _: SequenceWithRecursion =>
                Future failed RejectRequest(BadRequest, "recursion detected in sequence")
            case _: NoDocumentException =>
                Future failed RejectRequest(BadRequest, "action not found")
        }
    }

    /*
     * helper function that traverses the actions to inline and counts atomic actions while keeping track of sequences
     * @param atomicActionsCnt the atomic actions seen so far
     * @param actionsToInline the actions to inline
     * @param sequences the sequences encountered during inlining
     * @return a successful future in case the conditions were met or a failed fugure with either too many actions or recursion found
     */
    private def inlineComponentsAndCountAtomicActions(atomicActionsCnt: Int, actionsToInline: List[FullyQualifiedEntityName], sequences: List[FullyQualifiedEntityName])(
        implicit transid: TransactionId): Future[Boolean] = {
        if (atomicActionsCnt > whiskConfig.actionSequenceLimit.toInt)
            Future.failed(new TooManyActionsInSequence())
        else {
            actionsToInline match {
                case action :: restActions =>
                    val docid = action.toDocId
                    WhiskAction.get(entityStore, docid) flatMap { act =>
                        act.exec match {
                            case seq: SequenceExec =>
                                // this is actually a sequence, check already traversed sequences
                                if (sequences.contains(action)) {
                                   Future.failed(new SequenceWithRecursion())
                                } else {
                                    // resolve the components first
                                    // need to inline each of its components
                                    val resolvedComponents = seq.components map { c => WhiskAction.resolveAction(entityStore, c) }
                                    val futureResComponents = Future.sequence(resolvedComponents)
                                    futureResComponents flatMap { components =>  // these are resolved components
                                        // check that these components don't overlap with the sequences found so far
                                        val overlap = components.intersect(sequences).nonEmpty
                                        if (overlap){
                                            Future.failed(new SequenceWithRecursion())
                                        }
                                        else {
                                            inlineComponentsAndCountAtomicActions(atomicActionsCnt, restActions ++ components, action :: sequences)
                                        }
                                    }
                                }
                            case _ =>
                                // this is an atomic action
                                inlineComponentsAndCountAtomicActions(atomicActionsCnt + 1, restActions, sequences)
                        }
                    }
                case Nil =>
                    // no more actions to inline, no exceptions, finished successfully
                    Future.successful(true)
            }
        }
    }

    /** Grace period after action timeout limit to poll for result. */
    private val blockingInvokeGrace = 5 seconds

    /** Max duration to wait for a blocking activation. */
    private val maxWaitForBlockingActivation = 60 seconds

    /** Max duration for active ack. */
    private val activeAckTimeout = 30 seconds
}

private case class BlockingInvokeTimeout(activationId: ActivationId) extends TimeoutException
private case class TooManyActionsInSequence() extends RuntimeException
private case class SequenceWithRecursion() extends RuntimeException
