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
import scala.util.Try
import spray.http.StatusCodes.BadRequest
import spray.http.StatusCodes.Conflict
import spray.http.StatusCodes.InternalServerError
import spray.http.StatusCodes.OK
import spray.httpx.SprayJsonSupport._
import spray.json.JsBoolean
import spray.json.JsObject
import spray.routing.Directive.pimpApply
import spray.routing.RequestContext
import spray.routing.directives.OnCompleteFutureMagnet.apply
import spray.routing.directives.ParamDefMagnet.apply
import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.Collection
import whisk.core.entity.Binding
import whisk.core.entity.DocId
import whisk.core.entity.EntityName
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.types.EntityStore
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.WhiskPackage
import whisk.core.entity.WhiskPackageAction
import whisk.core.entity.WhiskPackagePut
import whisk.core.entity.types.EntityStore
import whisk.http.ErrorResponse.terminate
import whisk.core.entity.Identity

object WhiskPackagesApi {
    def requiredProperties = WhiskEntityStore.requiredProperties
}

trait WhiskPackagesApi extends WhiskCollectionAPI {
    services: WhiskServices =>

    protected override val collection = Collection(Collection.PACKAGES)

    /** Database service to CRUD packages. */
    protected val entityStore: EntityStore

    /** Route directives for API. The methods that are supported on packages. */
    protected override lazy val entityOps = put | get | delete

    /** Must exclude any private packages when listing those in a namespace unless owned by subject. */
    protected override val listRequiresPrivateEntityFilter = true

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
    override def create(user: Identity, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        parameter('overwrite ? false) { overwrite =>
            entity(as[WhiskPackagePut]) { content =>
                val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
                putEntity(WhiskPackage, entityStore, docid, overwrite,
                    update(content) _, () => create(content, namespace, name))
            }
        }
    }

    /**
     * Activating a package is not supported. This method is not permitted and is not reachable.
     *
     * Responses are one of (Code, Message)
     * - 405 Not Allowed
     */
    override def activate(user: Identity, namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        error(this, "activate is not permitted on packages")
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
    override def remove(namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        deleteEntity(WhiskPackage, entityStore, docid, (wp: WhiskPackage) => {
            wp.binding map {
                // this is a binding, it is safe to remove
                _ => Future successful true
            } getOrElse {
                // may only delete a package if all its ingredients are deleted already
                WhiskAction.listCollectionInNamespace(entityStore, wp.namespace.addpath(wp.name), skip = 0, limit = 0) flatMap {
                    case Left(list) if (list.size != 0) =>
                        Future failed {
                            RejectRequest(Conflict, s"Package not empty (contains ${list.size} ${if (list.size == 1) "entity" else "entities"})")
                        }
                    case _ => Future successful true
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
    override def fetch(namespace: EntityPath, name: EntityName, env: Option[Parameters])(implicit transid: TransactionId) = {
        val docid = DocId(WhiskEntity.qualifiedName(namespace, name))
        getEntity(WhiskPackage, entityStore, docid, Some { mergePackageWithBinding() _ })
    }

    /**
     * Gets all packages/bindings in namespace.
     *
     * Responses are one of (Code, Message)
     * - 200 [] or [WhiskPackage as JSON]
     * - 500 Internal Server Error
     */
    def list(namespace: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId) = {
        // for consistency, all the collections should support the same list API
        // but because supporting docs on actions is difficult, the API does not
        // offer an option to fetch entities with full docs yet; see comment in
        // Actions API for more.
        val docs = false

        // disable listing all public (shared) packages in all namespaces until
        // there exists a process in place to curate and rank these packages
        val publicPackagesInAnyNamespace = false
        parameter('skip ? 0, 'limit ? collection.listLimit, 'count ? false) {
            (skip, limit, count) =>
                if (publicPackagesInAnyNamespace && docs) {
                    terminate(BadRequest, "Parameters 'public' and 'docs' may not both be true at the same time")
                } else listEntities {
                    if (!publicPackagesInAnyNamespace) {
                        WhiskPackage.listCollectionInNamespace(entityStore, namespace, skip, limit, docs) map {
                            list =>
                                // any subject is entitled to list packages in any namespace
                                // however, they shall only observe public packages if the packages
                                // are not in one of the namespaces the subject is entitled to
                                val packages = if (docs) {
                                    list.right.get map { WhiskPackage.serdes.write(_) }
                                } else list.left.get
                                FilterEntityList.filter(packages, excludePrivate,
                                    additionalFilter = { // additionally exclude bindings
                                        case pkg: JsObject => Try {
                                            pkg.fields(WhiskPackage.bindingFieldName) == JsBoolean(false)
                                        } getOrElse false
                                    })
                        }
                    } else {
                        WhiskPackage.listCollectionInAnyNamespace(entityStore, skip, limit, docs = false, reduce = publicPackagesInAnyNamespace) map {
                            _.left.get
                        }
                    }
                }
        }
    }

    /**
     * Creates a WhiskPackage from PUT content, generating default values where necessary.
     * If this is a binding, confirm the referenced package exists.
     */
    private def create(content: WhiskPackagePut, namespace: EntityPath, name: EntityName)(implicit transid: TransactionId) = {
        content.binding match {
            case Some(binding) =>
                val promise = Promise[WhiskPackage]
                val resolvedBinding = Some(binding.resolve(namespace))
                WhiskPackage.get(entityStore, resolvedBinding.get.docid) onComplete {
                    case Success(doc) =>
                        if (doc.binding.isEmpty) promise success {
                            WhiskPackage(
                                namespace,
                                name,
                                resolvedBinding,
                                content.parameters getOrElse Parameters(),
                                content.version getOrElse SemVer(),
                                content.publish getOrElse false,
                                // override any binding annotation from PUT (always set by the controller)
                                (content.annotations getOrElse Parameters()) ++ bindingAnnotation(resolvedBinding))
                        }
                        else promise failure {
                            RejectRequest(BadRequest, "cannot bind to another package binding")
                        }
                    case Failure(t: NoDocumentException) =>
                        promise failure {
                            RejectRequest(BadRequest, "binding references a package that does not exist")
                        }
                    case Failure(t) => promise failure RejectRequest(BadRequest, t)
                }
                promise.future
            case None =>
                Future successful {
                    WhiskPackage(
                        namespace,
                        name,
                        binding = None,
                        content.parameters getOrElse Parameters(),
                        content.version getOrElse SemVer(),
                        content.publish getOrElse false,
                        // remove any binding annotation from PUT (always set by the controller)
                        (content.annotations map { _ -- WhiskPackage.bindingFieldName }) getOrElse Parameters())
                }
        }
    }

    /** Updates a WhiskPackage from PUT content, merging old package/binding where necessary. */
    private def update(content: WhiskPackagePut)(wp: WhiskPackage)(implicit transid: TransactionId) = {
        content.binding match {
            case Some(binding) =>
                if (wp.binding == None) Future failed {
                    RejectRequest(Conflict, "resource is a package but content specifies a binding")
                }
                else {
                    val promise = Promise[WhiskPackage]
                    val resolvedBinding = Some(binding.resolve(wp.namespace))
                    WhiskPackage.get(entityStore, binding.docid) onComplete {
                        case Success(_) =>
                            promise success {
                                WhiskPackage(
                                    wp.namespace,
                                    wp.name,
                                    resolvedBinding,
                                    content.parameters getOrElse wp.parameters,
                                    content.version getOrElse wp.version.upPatch,
                                    content.publish getOrElse wp.publish,
                                    // override any binding annotation from PUT (always set by the controller)
                                    (content.annotations getOrElse wp.annotations) ++ bindingAnnotation(resolvedBinding)).
                                    revision[WhiskPackage](wp.docinfo.rev)
                            }
                        case Failure(t) => promise.failure { RejectRequest(BadRequest, t) }
                    }
                    promise.future
                }
            case None =>
                Future successful {
                    WhiskPackage(
                        wp.namespace,
                        wp.name,
                        wp.binding,
                        content.parameters getOrElse wp.parameters,
                        content.version getOrElse wp.version.upPatch,
                        content.publish getOrElse wp.publish,
                        // override any binding annotation from PUT (always set by the controller)
                        (content.annotations map {
                            _ -- WhiskPackage.bindingFieldName
                        } getOrElse wp.annotations) ++ bindingAnnotation(wp.binding)).
                        revision[WhiskPackage](wp.docinfo.rev)
                }
        }
    }

    /**
     * Constructs a "binding" annotation. This is redundant with the binding
     * information available in WhiskPackage but necessary for some client which
     * fetch package lists but cannot determine which package may be bound. An
     * alternative is to include the binding in the package list "view" but this
     * will require an API change. So using an annotation instead.
     */
    private def bindingAnnotation(binding: Option[Binding]): Parameters = {
        binding map {
            b => Parameters(WhiskPackage.bindingFieldName, Binding.serdes.write(b))
        } getOrElse Parameters()
    }

    /**
     * Constructs a WhiskPackage that is a merger of a package with its packing binding (if any).
     * If this is a binding, fetch package for binding, merge parameters then emit.
     * Otherwise this is a package, emit it.
     */
    private def mergePackageWithBinding(ref: Option[WhiskPackage] = None)(wp: WhiskPackage)(implicit transid: TransactionId): RequestContext => Unit = {
        wp.binding map {
            case Binding(ns, n) =>
                val docid = DocId(WhiskEntity.qualifiedName(ns, n))
                info(this, s"fetching package '$docid' for reference")
                getEntity(WhiskPackage, entityStore, docid, Some {
                    mergePackageWithBinding(Some { wp }) _
                })
        } getOrElse {
            val pkg = ref map { _ inherit wp.parameters } getOrElse wp
            info(this, s"fetching package actions in '${wp.path}'")
            val actions = WhiskAction.listCollectionInNamespace(entityStore, wp.path, skip = 0, limit = 0) flatMap {
                case Left(list) => Future successful {
                    pkg withPackageActions (list map { o => WhiskPackageAction.serdes.read(o) })
                }
                case t => Future failed {
                    error(this, "unexpected result in package action lookup: $t")
                    new IllegalStateException(s"unexpected result in package action lookup: $t")
                }
            }

            onComplete(actions) {
                case Success(p) =>
                    info(this, s"[GET] entity success")
                    complete(OK, p)
                case Failure(t) =>
                    error(this, s"[GET] failed: ${t.getMessage}")
                    terminate(InternalServerError, t.getMessage)
            }
        }
    }
}
