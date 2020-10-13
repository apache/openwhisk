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

import java.time.Instant

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
import akka.http.scaladsl.model.StatusCodes.NoContent
import akka.http.scaladsl.server.{Directives, RequestContext, RouteResult}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsValue, RootJsonFormat}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.FeatureFlags
import org.apache.openwhisk.core.controller.PostProcess.PostProcessEntity
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.{ActivationId, ActivationLogs, DocId, WhiskActivation, WhiskDocument}
import org.apache.openwhisk.http.ErrorResponse
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages._

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

/**
 * A convenient typedef for functions that post process an entity
 * on an operation and terminate the HTTP request.
 */
object PostProcess {
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
   * Terminates HTTP request for list requests.
   *
   * Responses are one of (Code, Message)
   * - 200 entity A [] as JSON []
   * - 500 Internal Server Error
   */
  protected def listEntities(list: Future[List[JsValue]])(implicit transid: TransactionId) = {
    onComplete(list) {
      case Success(entities) =>
        logging.debug(this, s"[LIST] entity success")
        complete(OK, entities)
      case Failure(t: Throwable) =>
        logging.error(this, s"[LIST] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Terminates HTTP request for list count requests.
   *
   * Responses are one of (Code, Message)
   * - 200 JSON object
   * - 500 Internal Server Error
   */
  protected def countEntities(count: Future[JsValue])(implicit transid: TransactionId) = {
    onComplete(count) {
      case Success(c) =>
        logging.info(this, s"[COUNT] count success")
        complete(OK, c)
      case Failure(t: Throwable) =>
        logging.error(this, s"[COUNT] count failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Waits on specified Future that returns an entity of type A from datastore. Terminates HTTP request.
   *
   * @param entity future that returns an entity of type A fetched from datastore
   * @param postProcess an optional continuation to post process the result of the
   * get and terminate the HTTP request directly
   *
   * Responses are one of (Code, Message)
   * - 200 entity A as JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  protected def getEntity[A <: DocumentRevisionProvider, Au >: A](entity: Future[A],
                                                                  postProcess: Option[PostProcessEntity[A]] = None)(
    implicit transid: TransactionId,
    format: RootJsonFormat[A],
    ma: Manifest[A]) = {
    onComplete(entity) {
      case Success(entity) =>
        logging.debug(this, s"[GET] entity success")
        postProcess map { _(entity) } getOrElse complete(OK, entity)
      case Failure(t: NoDocumentException) =>
        logging.debug(this, s"[GET] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.debug(this, s"[GET] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.debug(this, s"[GET] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[GET] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Waits on specified Future that returns an entity of type A from datastore. Terminates HTTP request.
   *
   * @param entity future that returns an entity of type A fetched from datastore
   * @param project a function A => JSON which projects fields form A
   *
   * Responses are one of (Code, Message)
   * - 200 project(A) as JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  protected def getEntityAndProject[A <: DocumentRevisionProvider, Au >: A](
    entity: Future[A],
    project: A => Future[JsObject])(implicit transid: TransactionId, format: RootJsonFormat[A], ma: Manifest[A]) = {
    onComplete(entity) {
      case Success(entity) =>
        logging.debug(this, s"[PROJECT] entity success")

        onComplete(project(entity)) {
          case Success(response: JsObject) => complete(OK, response)
          case Failure(t: Throwable) =>
            logging.error(this, s"[PROJECT] projection failed: ${t.getMessage}")
            terminate(InternalServerError, t.getMessage)
        }
      case Failure(t: NoDocumentException) =>
        logging.debug(this, s"[PROJECT] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.debug(this, s"[PROJECT] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.debug(this, s"[PROJECT] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[PROJECT] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }

  /**
   * Waits on specified Future that returns an entity of type A from datastore.
   * In case A entity is not stored, use the docId to search logstore
   * Terminates HTTP request.
   *
   * @param entity future that returns an entity of type A fetched from datastore
   * @param docId activation DocId
   * @param disableStoreResultConfig configuration
   * @param project a function A => JSON which projects fields form A
   *
   * Responses are one of (Code, Message)
   * - 200 project(A) as JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  protected def getEntityAndProjectLog[A <: DocumentRevisionProvider, Au >: A](
    entity: Future[A],
    docId: DocId,
    disableStoreResultConfig: Boolean,
    project: (String, ActivationId, Option[Instant], Option[Instant], Option[ActivationLogs]) => Future[JsObject])(
    implicit transid: TransactionId,
    format: RootJsonFormat[A],
    ma: Manifest[A]) = {
    onComplete(entity) {
      case Success(entity) =>
        logging.debug(this, s"[PROJECT] entity success")
        val activation = entity.asInstanceOf[WhiskActivation]
        onComplete(
          project(
            activation.namespace.asString,
            activation.activationId,
            Some(activation.start),
            Some(activation.end),
            Some(activation.logs))) {
          case Success(response: JsObject) =>
            complete(OK, response)
          case Failure(t: Throwable) =>
            logging.error(this, s"[PROJECT] projection failed: ${t.getMessage}")
            terminate(InternalServerError, t.getMessage)
        }
      case Failure(t: NoDocumentException) =>
        // In case disableStoreResult configuration is active, persevere
        // log might still be available even if entity was not
        if (disableStoreResultConfig) {
          val namespace = docId.asString.split("/")(0)
          val id = docId.asString.split("/")(1)
          onComplete(project(namespace, ActivationId(id), None, None, None)) {
            case Success(response: JsObject) =>
              logging.debug(this, s"[PROJECTLOG] entity success")
              complete(OK, response)
            case Failure(t: Throwable) =>
              logging.error(this, s"[PROJECTLOG] projection failed: ${t.getMessage}")
              terminate(InternalServerError, t.getMessage)
          }
        } else {
          terminate(NotFound)
        }
      case Failure(t: DocumentTypeMismatchException) =>
        logging.debug(this, s"[PROJECT] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.debug(this, s"[PROJECT] entity unreadable")
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
  protected def putEntity[A <: DocumentRevisionProvider, Au >: A](factory: DocumentFactory[A],
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
        logging.debug(this, s"[PUT] entity exists, will try to update '$doc'")
        update(doc).map(updatedDoc => (Some(doc), updatedDoc))
      } else if (treatExistsAsConflict) {
        logging.debug(this, s"[PUT] entity exists, but overwrite is not enabled, aborting")
        Future failed RejectRequest(Conflict, "resource already exists")
      } else {
        Future failed IdentityPut(doc)
      }
    } recoverWith {
      case _: NoDocumentException =>
        logging.debug(this, s"[PUT] entity does not exist, will try to create it")
        create().map(newDoc => (None, newDoc))
    } flatMap {
      case (old, a) =>
        logging.debug(this, s"[PUT] entity created/updated, writing back to datastore")
        factory.put(datastore, a, old) map { _ =>
          a
        }
    }) {
      case Success(entity) =>
        logging.debug(this, s"[PUT] entity success")
        if (FeatureFlags.requireResponsePayload) postProcess map { _(entity) } getOrElse complete(OK, entity)
        else postProcess map { _(entity) } getOrElse complete(OK)
      case Failure(IdentityPut(a)) =>
        logging.debug(this, s"[PUT] entity exists, not overwritten")
        complete(OK, a)
      case Failure(t: DocumentConflictException) =>
        logging.debug(this, s"[PUT] entity conflict: ${t.getMessage}")
        terminate(Conflict, conflictMessage)
      case Failure(RejectRequest(code, message)) =>
        logging.debug(this, s"[PUT] entity rejected with code $code: $message")
        terminate(code, message)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.debug(this, s"[PUT] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.debug(this, s"[PUT] entity unreadable")
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
        logging.debug(this, s"[DEL] entity success")
        if (FeatureFlags.requireResponsePayload) postProcess map { _(entity) } getOrElse complete(OK, entity)
        else postProcess map { _(entity) } getOrElse complete(NoContent)
      case Failure(t: NoDocumentException) =>
        logging.debug(this, s"[DEL] entity does not exist")
        terminate(NotFound)
      case Failure(t: DocumentConflictException) =>
        logging.debug(this, s"[DEL] entity conflict: ${t.getMessage}")
        terminate(Conflict, conflictMessage)
      case Failure(RejectRequest(code, message)) =>
        logging.debug(this, s"[DEL] entity rejected with code $code: $message")
        terminate(code, message)
      case Failure(t: DocumentTypeMismatchException) =>
        logging.debug(this, s"[DEL] entity conformance check failed: ${t.getMessage}")
        terminate(Conflict, conformanceMessage)
      case Failure(t: ArtifactStoreException) =>
        logging.error(this, s"[DEL] entity unreadable")
        terminate(InternalServerError, t.getMessage)
      case Failure(t: Throwable) =>
        logging.error(this, s"[DEL] entity failed: ${t.getMessage}")
        terminate(InternalServerError)
    }
  }
}
