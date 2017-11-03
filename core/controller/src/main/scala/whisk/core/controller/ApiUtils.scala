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

package whisk.core.controller

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.Conflict
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean
import spray.json.JsObject
import spray.json.JsValue
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.controller.PostProcess.PostProcessEntity
import whisk.core.database.ArtifactStore
import whisk.core.database.ArtifactStoreException
import whisk.core.database.DocumentConflictException
import whisk.core.database.DocumentFactory
import whisk.core.database.DocumentTypeMismatchException
import whisk.core.database.CacheChangeNotification
import whisk.core.database.NoDocumentException
import whisk.core.entity.DocId
import whisk.core.entity.WhiskDocument
import whisk.core.entity.WhiskEntity
import whisk.http.ErrorResponse
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages._

/** An exception to throw inside a Predicate future. */
protected[core] case class RejectRequest(code: StatusCode, message: Option[ErrorResponse]) extends Throwable {
  override def toString = s"RejectRequest($code)" + message.map(" " + _.error).getOrElse("")
}

protected[core] object RejectRequest {

  /** Creates rejection with default message for status code. */
  protected[core] def apply(code: StatusCode)(implicit transid: TransactionId): RejectRequest = {
    RejectRequest(code, Some(ErrorResponse.response(code)(transid)))
  }

  /** Creates rejection with custom message for status code. */
  protected[core] def apply(code: StatusCode, m: String)(implicit transid: TransactionId): RejectRequest = {
    RejectRequest(code, Some(ErrorResponse(m, transid)))
  }

  /** Creates rejection with custom message for status code derived from reason for throwable. */
  protected[core] def apply(code: StatusCode, t: Throwable)(implicit transid: TransactionId): RejectRequest = {
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
  protected[controller] def filter(resources: List[JsValue],
                                   excludePrivate: Boolean,
                                   additionalFilter: JsObject => Boolean = (_ => true)) = {
    if (excludePrivate) {
      resources filter {
        case obj: JsObject =>
          obj.fields.get(sharedFieldName) match {
            case Some(JsBoolean(true)) => additionalFilter(obj) // a shared entity
            case _                     => false
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
  type PostProcessEntity[A] = A => RequestContext => Future[RouteResult]
}

/** A trait for REST APIs that read entities from a datastore */
trait ReadOps extends Directives {

  /** An execution context for futures */
  protected implicit val executionContext: ExecutionContext

  protected implicit val logging: Logging

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

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
        logging.info(this, s"[LIST] entity success")
        complete(OK, entities)
      case Failure(t: Throwable) =>
        logging.error(this, s"[LIST] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
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
  protected def getEntity[A, Au >: A](factory: DocumentFactory[A],
                                      datastore: ArtifactStore[Au],
                                      docid: DocId,
                                      postProcess: Option[PostProcessEntity[A]] = None)(implicit transid: TransactionId,
                                                                                        format: RootJsonFormat[A],
                                                                                        ma: Manifest[A]) = {
    onComplete(factory.get(datastore, docid)) {
      case Success(entity) =>
        logging.info(this, s"[GET] entity success")
        postProcess map { _(entity) } getOrElse complete(OK, entity)
      case Failure(t: NoDocumentException) =>
        logging.info(this, s"[GET] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.info(this, s"[GET] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.info(this, s"[GET] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[GET] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
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
    project: A => Future[JsObject])(implicit transid: TransactionId, format: RootJsonFormat[A], ma: Manifest[A]) = {
    onComplete(factory.get(datastore, docid)) {
      case Success(entity) =>
        logging.info(this, s"[PROJECT] entity success")
        complete(OK, project(entity))
      case Failure(t: NoDocumentException) =>
        logging.info(this, s"[PROJECT] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.info(this, s"[PROJECT] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.info(this, s"[PROJECT] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[PROJECT] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }
}

/** A trait for REST APIs that write entities to a datastore */
trait WriteOps extends Directives {

  /** An execution context for futures */
  protected implicit val executionContext: ExecutionContext

  protected implicit val logging: Logging

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

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
  protected def putEntity[A, Au >: A](factory: DocumentFactory[A],
                                      datastore: ArtifactStore[Au],
                                      docid: DocId,
                                      overwrite: Boolean,
                                      update: A => Future[A],
                                      create: () => Future[A],
                                      treatExistsAsConflict: Boolean = true,
                                      postProcess: Option[PostProcessEntity[A]] = None)(
    implicit transid: TransactionId,
    format: RootJsonFormat[A],
    notifier: Option[CacheChangeNotification],
    ma: Manifest[A]) = {
    // marker to return an existing doc with status OK rather than conflict if overwrite is false
    case class IdentityPut(self: A) extends Throwable

    onComplete(factory.get(datastore, docid) flatMap { doc =>
      if (overwrite) {
        logging.info(this, s"[PUT] entity exists, will try to update '$doc'")
        update(doc)
      } else if (treatExistsAsConflict) {
        logging.info(this, s"[PUT] entity exists, but overwrite is not enabled, aborting")
        Future failed RejectRequest(Conflict, "resource already exists")
      } else {
        Future failed IdentityPut(doc)
      }
    } recoverWith {
      case _: NoDocumentException =>
        logging.info(this, s"[PUT] entity does not exist, will try to create it")
        create()
    } flatMap { a =>
      logging.info(this, s"[PUT] entity created/updated, writing back to datastore")
      factory.put(datastore, a) map { _ =>
        a
      }
    }) {
      case Success(entity) =>
        logging.info(this, s"[PUT] entity success")
        postProcess map { _(entity) } getOrElse complete(OK, entity)
      case Failure(IdentityPut(a)) =>
        logging.info(this, s"[PUT] entity exists, not overwritten")
        complete(OK, a)
      case Failure(t: DocumentConflictException) =>
        logging.info(this, s"[PUT] entity conflict: ${t.getMessage}")
        terminate(Conflict, conflictMessage)
      case Failure(RejectRequest(code, message)) =>
        logging.info(this, s"[PUT] entity rejected with code $code: $message")
        terminate(code, message)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.info(this, s"[PUT] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.info(this, s"[PUT] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[PUT] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
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
   * @param confirm a function (A => Future[Unit]) that confirms the entity is safe to delete (must fail future to abort)
   * or fails the future with an appropriate message
   *
   * Responses are one of (Code, Message)
   * - 200 entity A as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  protected def deleteEntity[A <: WhiskDocument, Au >: A](factory: DocumentFactory[A],
                                                          datastore: ArtifactStore[Au],
                                                          docid: DocId,
                                                          confirm: A => Future[Unit],
                                                          postProcess: Option[PostProcessEntity[A]] = None)(
    implicit transid: TransactionId,
    format: RootJsonFormat[A],
    notifier: Option[CacheChangeNotification],
    ma: Manifest[A]) = {
    onComplete(factory.get(datastore, docid) flatMap { entity =>
      confirm(entity) flatMap {
        case _ =>
          factory.del(datastore, entity.docinfo) map { _ =>
            entity
          }
      }
    }) {
      case Success(entity) =>
        logging.info(this, s"[DEL] entity success")
        postProcess map { _(entity) } getOrElse complete(OK, entity)
      case Failure(t: NoDocumentException) =>
        logging.info(this, s"[DEL] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentConflictException) =>
        logging.info(this, s"[DEL] entity conflict: ${t.getMessage}")
        terminate(Conflict, conflictMessage)
      case Failure(RejectRequest(code, message)) =>
        logging.info(this, s"[DEL] entity rejected with code $code: $message")
        terminate(code, message)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.info(this, s"[DEL] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.info(this, s"[DEL] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[DEL] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }
}
