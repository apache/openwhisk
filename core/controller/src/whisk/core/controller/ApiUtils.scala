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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import spray.http.HttpRequest
import spray.http.StatusCode
import spray.http.StatusCodes.ClientError
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.NotFound
import spray.http.StatusCodes.OK
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.json.JsObject
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.routing.RequestContext
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import spray.routing.directives.OnCompleteFutureMagnet.apply
import spray.routing.directives.OnSuccessFutureMagnet.apply
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.controller.PostProcess.PostProcessEntity
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory
import whisk.core.database.NoDocumentException
import whisk.core.database.DocumentConflictException
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace
import whisk.core.entity.WhiskDocument
import whisk.core.connector.LoadBalancerResponse
import spray.routing.RejectionHandler
import spray.routing.Directives
import whisk.core.entity.WhiskEntity
import whisk.http.ErrorResponse
import whisk.http.ErrorResponse.{ terminate }
import spray.http.StatusCodes
import spray.json.JsBoolean

protected sealed trait Messages {
    /** Standard message for reporting resource conflicts */
    protected val conflictMessage = "Concurrent modification to resource detected"

    /**
     * Standard message for reporting resource conformance error when trying to access
     * a resource from a different collection.
     */
    protected val conformanceMessage = "Resource by this name already exists but is not in this collection"
}

/** An exception to throw inside a Predicate future. */
protected[controller] case class RejectRequest(code: ClientError, message: Option[ErrorResponse]) extends Throwable

protected[controller] object RejectRequest {
    protected[controller] def apply(code: ClientError, m: String)(implicit transid: TransactionId): RejectRequest = {
        RejectRequest(code, Some(ErrorResponse(m, transid)))
    }
    protected[controller] def apply(code: ClientError, t: Throwable)(implicit transid: TransactionId): RejectRequest = {
        val reason = t.getMessage
        RejectRequest(code, if (reason != null) reason else "Rejected")
    }
}

protected[controller] object FilterEntityList {
    import WhiskEntity.sharedFieldName

    /**
     * Filters from a list of entities serialized to JsObjects only those
     * that have the shared field ("publish") equal to true and excludes
     * all others.
     */
    protected[controller] def filter(
        resources: List[JsValue],
        excludePrivate: Boolean,
        additionalFilter: JsObject => Boolean = (_ => true)) = {
        if (excludePrivate) {
            resources filter {
                case obj: JsObject =>
                    obj.getFields(sharedFieldName) match {
                        case Seq(JsBoolean(true)) => true && additionalFilter(obj) // a shared entity
                        case _                    => false
                    }
                case _ => false // only expecting JsObject instances
            }
        } else resources
    }
}

/**
 * A convenient typedef for functions that post process an entity
 * on an operation and terminate the HTTP request.
 */
package object PostProcess {
    type PostProcessEntity[A] = A => RequestContext => Unit
}

/** A trait for REST APIs that read entities from a datastore */
trait ReadOps extends Directives with Messages with Logging {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /**
     * Get all entities of type A from datastore that match key. Terminates HTTP request.
     *
     * @param factory the factory that can fetch entities of type A from datastore
     * @param datastore the client to the database
     * @param key the key to use to match records in the view, optional, if not defined, use namespace
     * @param view the view to query
     * @param filter a function List[A] => List[A] that filters the results
     *
     * Responses are one of (Code, Message)
     * - 200 entity A [] as JSON []
     * - 500 Internal Server Error
     */
    protected def listEntities(list: Future[List[JsValue]])(implicit transid: TransactionId) = {
        onComplete(list) {
            case Success(entities) =>
                info(this, s"[LIST] entity success")
                complete(OK, entities)
            case Failure(t: Throwable) =>
                error(this, s"[LIST] entity failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }

    /**
     * Gets an entity of type A from datastore. Terminates HTTP request.
     *
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param docid the document id to get
     * @param postProcess an optional continuation to post process the result of the
     * get and terminate the HTTP request directly
     *
     * Responses are one of (Code, Message)
     * - 200 entity A as JSON
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    protected def getEntity[A, Au >: A](
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        docid: DocId,
        postProcess: Option[PostProcessEntity[A]] = None)(
            implicit transid: TransactionId,
            format: RootJsonFormat[A],
            ma: Manifest[A]) = {
        onComplete(factory.get(datastore, docid.asDocInfo)) {
            case Success(entity) =>
                info(this, s"[GET] entity success")
                postProcess map { _(entity) } getOrElse complete(OK, entity)
            case Failure(t: NoDocumentException) =>
                info(this, s"[GET] entity does not exist")
                terminate(NotFound)
            case Failure(t: IllegalArgumentException) =>
                error(this, s"[GET] entity conformance check failed: ${t.getMessage}")
                terminate(Conflict, conformanceMessage)
            case Failure(t: Throwable) =>
                error(this, s"[GET] entity failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }

    /**
     * Gets an entity of type A from datastore and project fields for response. Terminates HTTP request.
     *
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param docid the document id to get
     * @param project a function A => JSON which projects fields form A
     *
     * Responses are one of (Code, Message)
     * - 200 project(A) as JSON
     * - 404 Not Found
     * - 500 Internal Server Error
     */
    protected def getEntityAndProject[A, Au >: A](
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        docid: DocId,
        project: A => JsObject)(
            implicit transid: TransactionId,
            format: RootJsonFormat[A],
            ma: Manifest[A]) = {
        onComplete(factory.get(datastore, docid.asDocInfo)) {
            case Success(entity) =>
                info(this, s"[PROJECT] entity success")
                complete(OK, project(entity))
            case Failure(t: NoDocumentException) =>
                info(this, s"[PROJECT] entity does not exist")
                terminate(NotFound)
            case Failure(t: IllegalArgumentException) =>
                info(this, s"[PROJECT] entity conformance check failed: ${t.getMessage}")
                terminate(Conflict, conformanceMessage)
            case Failure(t: Throwable) =>
                error(this, s"[PROJECT] entity failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }
}

/** A trait for REST APIs that write entities to a datastore */
trait WriteOps extends Directives with Messages with Logging {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /**
     * A predicate future that completes with true iff the entity should be
     * stored in the datastore. Future should fail otherwise with RejectPut.
     */
    protected type PutPredicate = Future[Boolean]

    /**
     * Creates or updates an entity of type A in the datastore. First, fetch the entity
     * by id from the datastore (this is required to get the document revision for an update).
     * If the entity does not exist, create it. If it does exist, and 'overwrite' is enabled,
     * update the entity.
     *
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param docid the document id to put
     * @param overwrite updates an existing entity iff overwrite == true
     * @param update a function (A) => Future[A] that updates the existing entity with PUT content
     * @param create a function () => Future[A] that creates a new entity from PUT content
     * @param treatExistsAsConflict if true and document exists but overwrite is not enabled, respond
     * with Conflict else return OK and the existing document
     *
     * Responses are one of (Code, Message)
     * - 200 entity A as JSON
     * - 400 Bad Request
     * - 409 Conflict
     * - 500 Internal Server Error
     */
    protected def putEntity[A, Au >: A](
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        docid: DocId,
        overwrite: Boolean,
        update: A => Future[A],
        create: () => Future[A],
        treatExistsAsConflict: Boolean = true,
        postProcess: Option[PostProcessEntity[A]] = None)(
            implicit transid: TransactionId,
            format: RootJsonFormat[A],
            ma: Manifest[A]) = {
        // marker to return an existing doc with status OK rather than conflict if overwrite is false
        case class IdentityPut(self: A) extends Throwable

        onComplete(factory.get(datastore, docid.asDocInfo) flatMap { doc =>
            if (overwrite) {
                info(this, s"[PUT] entity exists, will try to update '$doc'")
                update(doc)
            } else if (treatExistsAsConflict) {
                info(this, s"[PUT] entity exists, but overwrite is not enabled, aborting")
                Future failed RejectRequest(Conflict, "resource already exists")
            } else {
                Future failed IdentityPut(doc)
            }
        } recoverWith {
            case _: NoDocumentException =>
                info(this, s"[PUT] entity does not exist, will try to create it")
                create()
        } flatMap { a =>
            info(this, s"[PUT] entity created/updated, writing back to datastore")
            factory.put(datastore, a) map { _ => a }
        }) {
            case Success(entity) =>
                info(this, s"[PUT] entity success")
                postProcess map { _(entity) } getOrElse complete(OK, entity)
            case Failure(IdentityPut(a)) =>
                info(this, s"[PUT] entity exists, not overwriten")
                complete(OK, a)
            case Failure(t: DocumentConflictException) =>
                info(this, s"[PUT] entity conflict: ${t.getMessage}")
                terminate(Conflict, conflictMessage)
            case Failure(RejectRequest(code, message)) =>
                info(this, s"[PUT] entity rejected with code $code: $message")
                terminate(code, message)
            case Failure(t: IllegalArgumentException) =>
                info(this, s"[PUT] entity conformance check failed: ${t.getMessage}")
                terminate(Conflict, conformanceMessage)
            case Failure(t: Throwable) =>
                error(this, s"[PUT] entity failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }

    /**
     * Deletes an entity of type A from datastore.
     * To delete an entity, first fetch the record to identify its revision and then delete it.
     * Terminates HTTP request.
     *
     * @param factory the factory that can fetch entity of type A from datastore
     * @param datastore the client to the database
     * @param docid the document id to delete
     * @param confirm a function (A => Boolean) that confirms the entity is safe to delete (returns true),
     * or fails the future with an appropriate message
     *
     * Responses are one of (Code, Message)
     * - 200 entity A as JSON
     * - 404 Not Found
     * - 409 Conflict
     * - 500 Internal Server Error
     */
    protected def deleteEntity[A <: WhiskDocument, Au >: A](
        factory: DocumentFactory[A],
        datastore: ArtifactStore[Au],
        docid: DocId,
        confirm: A => Future[Boolean],
        postProcess: Option[PostProcessEntity[A]] = None)(
            implicit transid: TransactionId,
            format: RootJsonFormat[A],
            ma: Manifest[A]) = {
        onComplete(factory.get(datastore, docid.asDocInfo) flatMap {
            entity =>
                confirm(entity) flatMap {
                    case true => factory.del(datastore, entity.docinfo) map { _ => entity }
                    case false =>
                        error(this, "confirm delete must return true or fail the future")
                        Future.failed { new IllegalStateException("result of delete confirmation is not allowed") }
                }
        }) {
            case Success(entity) =>
                info(this, s"[DEL] entity success")
                postProcess map { _(entity) } getOrElse complete(OK, entity)
            case Failure(t: NoDocumentException) =>
                info(this, s"[DEL] entity does not exist")
                terminate(NotFound)
            case Failure(t: DocumentConflictException) =>
                info(this, s"[DEL] entity conflict: ${t.getMessage}")
                terminate(Conflict, conflictMessage)
            case Failure(RejectRequest(code, message)) =>
                info(this, s"[DEL] entity rejected with code $code: $message")
                terminate(code, message)
            case Failure(t: IllegalArgumentException) =>
                info(this, s"[DEL] entity conformance check failed: ${t.getMessage}")
                terminate(Conflict, conformanceMessage)
            case Failure(t: Throwable) =>
                error(this, s"[DEL] entity failed: ${t.getMessage}")
                terminate(InternalServerError, t.getMessage)
        }
    }
}
