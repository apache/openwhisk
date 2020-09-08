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

import scala.concurrent.Future
import scala.util.{Failure, Success}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.RestApiCommons.{ListLimit, ListSkip}
import org.apache.openwhisk.core.database.{CacheChangeNotification, DocumentTypeMismatchException, NoDocumentException}
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.ErrorResponse.terminate
import org.apache.openwhisk.http.Messages
import org.apache.openwhisk.http.Messages._
import pureconfig._
import org.apache.openwhisk.core.ConfigKeys

trait WhiskPackagesApi extends WhiskCollectionAPI with ReferencedEntities {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.PACKAGES)

  /** Database service to CRUD packages. */
  protected val entityStore: EntityStore

  /** Config flag for Execute Only for Shared Packages */
  protected def executeOnly =
    loadConfigOrThrow[Boolean](ConfigKeys.sharedPackageExecuteOnly)

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Route directives for API. The methods that are supported on packages. */
  protected override lazy val entityOps = put | get | delete

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /** Reserved package names. */
  protected[core] val RESERVED_NAMES = Set("default")

  /**
   * Creates or updates package/binding if it already exists. The PUT content is deserialized into a
   * WhiskPackagePut which is a subset of WhiskPackage (it eschews the namespace and entity name since
   * the former is derived from the authenticated user and the latter is derived from the URI). If the
   * binding property is defined, creates or updates a package binding as long as resource is already a
   * binding.
   *
   * The WhiskPackagePut is merged with the existing WhiskPackage in the datastore, overriding old values
   * with new values that are defined. Any values not defined in the PUT content are replaced with old values.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskPackage as JSON
   * - 400 Bad Request
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def create(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    parameter('overwrite ? false) { overwrite =>
      if (!RESERVED_NAMES.contains(entityName.name.asString)) {
        entity(as[WhiskPackagePut]) { content =>
          val request = content.resolve(entityName.namespace)
          request.binding.map { b =>
            logging.debug(this, "checking if package is accessible")
          }
          val referencedentities = referencedEntities(request)

          onComplete(entitlementProvider.check(user, Privilege.READ, referencedentities)) {
            case Success(_) =>
              putEntity(
                WhiskPackage,
                entityStore,
                entityName.toDocId,
                overwrite,
                update(request) _,
                () => create(request, entityName))
            case Failure(f) =>
              rewriteEntitlementFailure(f)
          }
        }
      } else {
        terminate(BadRequest, Messages.packageNameIsReserved(entityName.name.asString))
      }
    }
  }

  /**
   * Activating a package is not supported. This method is not permitted and is not reachable.
   *
   * Responses are one of (Code, Message)
   * - 405 Not Allowed
   */
  override def activate(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    logging.error(this, "activate is not permitted on packages")
    reject
  }

  /**
   * Deletes package/binding. If a package, may only be deleted if there are no entities in the package
   * or force parameter is set to true, which will delete all contents of the package before deleting.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskPackage as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
    parameter('force ? false) { force =>
      deleteEntity(
        WhiskPackage,
        entityStore,
        entityName.toDocId,
        (wp: WhiskPackage) => {
          wp.binding map {
            // this is a binding, it is safe to remove
            _ =>
              Future.successful({})
          } getOrElse {
            // may only delete a package if all its ingredients are deleted already or force flag is set
            WhiskAction
              .listCollectionInNamespace(
                entityStore,
                wp.namespace.addPath(wp.name),
                includeDocs = true,
                skip = 0,
                limit = 0) flatMap {
              case Right(list) if list.nonEmpty && force =>
                Future sequence {
                  list.map(action => {
                    WhiskAction.get(
                      entityStore,
                      wp.fullyQualifiedName(false)
                        .add(action.fullyQualifiedName(false).name)
                        .toDocId) flatMap { actionWithRevision =>
                      WhiskAction.del(entityStore, actionWithRevision.docinfo)
                    }
                  })
                } flatMap { _ =>
                  Future.successful({})
                }
              case Right(list) if list.nonEmpty && !force =>
                Future failed {
                  RejectRequest(
                    Conflict,
                    s"Package not empty (contains ${list.size} ${if (list.size == 1) "entity" else "entities"}). Set force param or delete package contents.")
                }
              case _ =>
                Future.successful({})
            }
          }
        })
    }
  }

  /**
   * Gets package/binding.
   * The package/binding name is prefixed with the namespace to create the primary index key.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskPackage has JSON
   * - 404 Not Found
   * - 500 Internal Server Error
   */
  override def fetch(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(
    implicit transid: TransactionId) = {
    if (executeOnly && user.namespace.name != entityName.namespace) {
      val value = entityName.toString
      terminate(Forbidden, forbiddenGetPackage(entityName.asString))
    } else {
      getEntity(WhiskPackage.get(entityStore, entityName.toDocId), Some {
        mergePackageWithBinding() _
      })
    }
  }

  /**
   * Gets all packages/bindings in namespace.
   *
   * Responses are one of (Code, Message)
   * - 200 [] or [WhiskPackage as JSON]
   * - 500 Internal Server Error
   */
  override def list(user: Identity, namespace: EntityPath)(implicit transid: TransactionId) = {
    parameter(
      'skip.as[ListSkip] ? ListSkip(collection.defaultListSkip),
      'limit.as[ListLimit] ? ListLimit(collection.defaultListLimit),
      'count ? false) { (skip, limit, count) =>
      val viewName = if (user.namespace.name.toPath == namespace) WhiskPackage.view else WhiskPackage.publicPackagesView
      if (!count) {
        listEntities {
          WhiskPackage
            .listCollectionInNamespace(
              entityStore,
              namespace,
              skip.n,
              limit.n,
              includeDocs = false,
              viewName = viewName)
            .map(_.fold((js) => js, (ps) => ps.map(WhiskPackage.serdes.write(_))))
        }
      } else {
        countEntities {
          WhiskPackage.countCollectionInNamespace(entityStore, namespace, skip.n, viewName = viewName)
        }
      }
    }
  }

  /**
   * Validates that a referenced binding exists.
   */
  private def checkBinding(binding: FullyQualifiedEntityName)(implicit transid: TransactionId): Future[Unit] = {
    WhiskPackage.get(entityStore, binding.toDocId) recoverWith {
      case t: NoDocumentException => Future.failed(RejectRequest(BadRequest, Messages.bindingDoesNotExist))
      case t: DocumentTypeMismatchException =>
        Future.failed(RejectRequest(Conflict, Messages.requestedBindingIsNotValid))
      case t => Future.failed(RejectRequest(BadRequest, t))
    } flatMap {
      // trying to create a new package binding that refers to another binding
      case provider if provider.binding.nonEmpty =>
        Future.failed(RejectRequest(BadRequest, Messages.bindingCannotReferenceBinding))
      // or creating a package binding that refers to a package
      case _ => Future.successful({})
    }
  }

  /**
   * Creates a WhiskPackage from PUT content, generating default values where necessary.
   * If this is a binding, confirm the referenced package exists.
   */
  private def create(content: WhiskPackagePut, pkgName: FullyQualifiedEntityName)(
    implicit transid: TransactionId): Future[WhiskPackage] = {
    val validateBinding = content.binding map { b =>
      checkBinding(b.fullyQualifiedName)
    } getOrElse Future.successful({})

    validateBinding map { _ =>
      WhiskPackage(
        pkgName.path,
        pkgName.name,
        content.binding,
        content.parameters getOrElse Parameters(),
        content.version getOrElse SemVer(),
        content.publish getOrElse false,
        // remove any binding annotation from PUT (always set by the controller)
        (content.annotations getOrElse Parameters())
          - WhiskPackage.bindingFieldName
          ++ bindingAnnotation(content.binding))
    }
  }

  /** Updates a WhiskPackage from PUT content, merging old package/binding where necessary. */
  private def update(content: WhiskPackagePut)(wp: WhiskPackage)(
    implicit transid: TransactionId): Future[WhiskPackage] = {
    val validateBinding = content.binding map { binding =>
      wp.binding map {
        // pre-existing entity is a binding, check that new binding is valid
        _ =>
          checkBinding(binding.fullyQualifiedName)
      } getOrElse {
        // pre-existing entity is a package, cannot make it a binding
        Future.failed(RejectRequest(Conflict, Messages.packageCannotBecomeBinding))
      }
    } getOrElse Future.successful({})

    validateBinding map { _ =>
      WhiskPackage(
        wp.namespace,
        wp.name,
        content.binding orElse wp.binding,
        content.parameters getOrElse wp.parameters,
        content.version getOrElse wp.version.upPatch,
        content.publish getOrElse wp.publish,
        // override any binding annotation from PUT (always set by the controller)
        (content.annotations getOrElse wp.annotations)
          - WhiskPackage.bindingFieldName
          ++ bindingAnnotation(content.binding orElse wp.binding)).revision[WhiskPackage](wp.docinfo.rev)
    }
  }

  private def rewriteEntitlementFailure(failure: Throwable)(
    implicit transid: TransactionId): RequestContext => Future[RouteResult] = {
    logging.debug(this, s"rewriting failure $failure")
    failure match {
      case RejectRequest(NotFound, _) => terminate(BadRequest, Messages.bindingDoesNotExist)
      case RejectRequest(Conflict, _) => terminate(Conflict, Messages.requestedBindingIsNotValid)
      case _                          => super.handleEntitlementFailure(failure)
    }
  }

  /**
   * Constructs a "binding" annotation. This is redundant with the binding
   * information available in WhiskPackage but necessary for some clients which
   * fetch package lists but cannot determine which package may be bound. An
   * alternative is to include the binding in the package list "view" but this
   * will require an API change. So using an annotation instead.
   */
  private def bindingAnnotation(binding: Option[Binding]): Parameters = {
    binding map { b =>
      Parameters(WhiskPackage.bindingFieldName, Binding.serdes.write(b))
    } getOrElse Parameters()
  }

  /**
   * Constructs a WhiskPackage that is a merger of a package with its packing binding (if any).
   * If this is a binding, fetch package for binding, merge parameters then emit.
   * Otherwise this is a package, emit it.
   */
  private def mergePackageWithBinding(ref: Option[WhiskPackage] = None)(wp: WhiskPackage)(
    implicit transid: TransactionId): RequestContext => Future[RouteResult] = {
    wp.binding map {
      case b: Binding =>
        val docid = b.fullyQualifiedName.toDocId
        logging.debug(this, s"fetching package '$docid' for reference")
        if (docid == wp.docid) {
          logging.error(this, s"unexpected package binding refers to itself: $docid")
          terminate(UnprocessableEntity, Messages.packageBindingCircularReference(b.fullyQualifiedName.toString))
        } else {

          /** Here's where I check package execute only case with package binding. */
          if (executeOnly && wp.namespace.asString != b.namespace.asString) {
            terminate(Forbidden, forbiddenGetPackageBinding(wp.name.asString))
          } else {
            getEntity(WhiskPackage.get(entityStore, docid), Some {
              mergePackageWithBinding(Some {
                wp
              }) _
            })
          }
        }
    } getOrElse {
      val pkg = ref map { _ inherit wp.parameters } getOrElse wp
      logging.debug(this, s"fetching package actions in '${wp.fullPath}'")
      val actions = WhiskAction.listCollectionInNamespace(entityStore, wp.fullPath, skip = 0, limit = 0) flatMap {
        case Left(list) =>
          Future.successful {
            pkg withPackageActions (list map { o =>
              WhiskPackageAction.serdes.read(o)
            })
          }
        case t =>
          Future.failed {
            logging.error(this, "unexpected result in package action lookup: $t")
            new IllegalStateException(s"unexpected result in package action lookup: $t")
          }
      }

      onComplete(actions) {
        case Success(p) =>
          logging.debug(this, s"[GET] entity success")
          complete(OK, p)
        case Failure(t) =>
          logging.error(this, s"[GET] failed: ${t.getMessage}")
          terminate(InternalServerError)
      }
    }
  }

  /** Custom unmarshaller for query parameters "limit" for "list" operations. */
  private implicit val stringToListLimit: Unmarshaller[String, ListLimit] = RestApiCommons.stringToListLimit(collection)

  /** Custom unmarshaller for query parameters "skip" for "list" operations. */
  private implicit val stringToListSkip: Unmarshaller[String, ListSkip] = RestApiCommons.stringToListSkip(collection)

}
