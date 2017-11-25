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

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult

import spray.json._

import whisk.common.TransactionId
import whisk.core.database.DocumentTypeMismatchException
import whisk.core.database.CacheChangeNotification
import whisk.core.database.NoDocumentException
import whisk.core.entitlement._
import whisk.core.entity._
import whisk.core.entity.types.EntityStore
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages

trait WhiskPackagesApi extends WhiskCollectionAPI with ReferencedEntities {
  services: WhiskServices =>

  protected override val collection = Collection(Collection.PACKAGES)

  protected[core] val RESERVED_NAMES = Array("default")

  /** Database service to CRUD packages. */
  protected val entityStore: EntityStore

  /** Notification service for cache invalidation. */
  protected implicit val cacheChangeNotification: Some[CacheChangeNotification]

  /** Route directives for API. The methods that are supported on packages. */
  protected override lazy val entityOps = put | get | delete

  /** Must exclude any private packages when listing those in a namespace unless owned by subject. */
  protected override val listRequiresPrivateEntityFilter = true

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

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
      if (!overwrite && (RESERVED_NAMES contains entityName.name.asString)) {
        terminate(BadRequest, Messages.packageNameIsReserved(entityName.name.asString))
      } else {
        entity(as[WhiskPackagePut]) { content =>
          val request = content.resolve(entityName.namespace)

          request.binding.map { b =>
            logging.info(this, "checking if package is accessible")
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
   * Deletes package/binding. If a package, may only be deleted if there are no entities in the package.
   *
   * Responses are one of (Code, Message)
   * - 200 WhiskPackage as JSON
   * - 404 Not Found
   * - 409 Conflict
   * - 500 Internal Server Error
   */
  override def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId) = {
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
          // may only delete a package if all its ingredients are deleted already
          WhiskAction
            .listCollectionInNamespace(entityStore, wp.namespace.addPath(wp.name), skip = 0, limit = 0) flatMap {
            case Left(list) if (list.size != 0) =>
              Future failed {
                RejectRequest(
                  Conflict,
                  s"Package not empty (contains ${list.size} ${if (list.size == 1) "entity" else "entities"})")
              }
            case _ => Future.successful({})
          }
        }
      })
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
    getEntity(WhiskPackage, entityStore, entityName.toDocId, Some { mergePackageWithBinding() _ })
  }

  /**
   * Gets all packages/bindings in namespace.
   *
   * Responses are one of (Code, Message)
   * - 200 [] or [WhiskPackage as JSON]
   * - 500 Internal Server Error
   */
  override def list(user: Identity, namespace: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId) = {
    // for consistency, all the collections should support the same list API
    // but because supporting docs on actions is difficult, the API does not
    // offer an option to fetch entities with full docs yet; see comment in
    // Actions API for more.
    val docs = false

    parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false) { (skip, limit, count) =>
      listEntities {
        WhiskPackage.listCollectionInNamespace(entityStore, namespace, skip, limit, docs) map { list =>
          // any subject is entitled to list packages in any namespace
          // however, they shall only observe public packages if the packages
          // are not in one of the namespaces the subject is entitled to
          val packages = list.fold((js) => js, (ps) => ps.map(WhiskPackage.serdes.write(_)))

          FilterEntityList.filter(packages, excludePrivate, additionalFilter = {
            // additionally exclude bindings
            _.fields.get(WhiskPackage.bindingFieldName) match {
              case Some(JsBoolean(isbinding)) => !isbinding
              case _                          => false // exclude anything that does not conform
            }
          })
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
        b =>
          checkBinding(b.fullyQualifiedName)
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
    logging.info(this, s"rewriting failure $failure")
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
        logging.info(this, s"fetching package '$docid' for reference")
        getEntity(WhiskPackage, entityStore, docid, Some {
          mergePackageWithBinding(Some { wp }) _
        })
    } getOrElse {
      val pkg = ref map { _ inherit wp.parameters } getOrElse wp
      logging.info(this, s"fetching package actions in '${wp.fullPath}'")
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
          logging.info(this, s"[GET] entity success")
          complete(OK, p)
        case Failure(t) =>
          logging.error(this, s"[GET] failed: ${t.getMessage}")
          terminate(InternalServerError)
      }
    }
  }
}
