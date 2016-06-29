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

package whisk.core.entitlement

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import spray.http.HttpMethod
import spray.http.HttpMethods.DELETE
import spray.http.HttpMethods.GET
import spray.http.HttpMethods.POST
import spray.http.HttpMethods.PUT
import whisk.common.TransactionId
import whisk.core.entity.DocId
import whisk.core.entity.Subject
import whisk.core.entity.WhiskPackage
import whisk.core.entity.types.EntityStore
import scala.concurrent.Promise
import whisk.core.entity.WhiskEntity
import whisk.core.entity.EntityName

class PackageCollection(entityStore: EntityStore) extends Collection(Collection.PACKAGES) {

    protected override val allowedEntityRights = {
        Set(Privilege.READ, Privilege.PUT, Privilege.DELETE)
    }

    /**
     * Computes implicit rights on a package/binding.
     *
     * Must fetch the resource (a package or binding) to determine if it is in allowed namespaces.
     * There are two cases:
     *
     * 1. the resource is a package: then either it is in allowed namespaces or it is public.
     * 2. the resource is a binding: then it must be in allowed namespaces and (1) must hold for
     *    the referenced package.
     *
     * A published package makes all its assets public regardless of their shared bit.
     * All assets that are not in an explicit package are private because the default package is private.
     */
    protected[core] override def implicitRights(namespaces: Set[String], right: Privilege, resource: Resource)(
        implicit ec: ExecutionContext, transid: TransactionId) = {
        resource.entity map {
            pkgname =>
                val isOwner = namespaces.contains(resource.namespace.root())
                right match {
                    case Privilege.READ =>
                        // must determine if this is a public or owned package
                        // or, for a binding, that it references a public or owned package
                        val docid = DocId(WhiskEntity.qualifiedName(resource.namespace.root, EntityName(pkgname)))
                        val promise = Promise[Boolean]
                        checkPackageReadPermission(promise, namespaces, isOwner, docid)
                        promise.future
                    case _ => Future successful { isOwner && allowedEntityRights.contains(right) }
                }
        } getOrElse {
            // only a READ on the package collection is permitted;
            // NOTE: currently, the implementation allows any subject to
            // list packages in any namespace, and defers the filtering of
            // public packages to non-owning subjects to the API handlers
            // for packages
            Future successful { right == Privilege.READ }
        }
    }

    /**
     * @param promise the promise to complete with authorization bit
     * @param isOwner indicates if the resource is owned by the subject requesting authorization
     * @param docid the package (or binding) document id
     */
    private def checkPackageReadPermission(
        promise: Promise[Boolean],
        namespaces: Set[String],
        isOwner: Boolean,
        doc: DocId)(
            implicit ec: ExecutionContext, transid: TransactionId): Unit = {

        val right = Privilege.READ

        WhiskPackage.get(entityStore, doc.asDocInfo) map {
            case wp if wp.binding.isEmpty =>
                val allowed = wp.publish || isOwner
                info(this, s"entitlement check on package, '$right' allowed?: $allowed")
                promise.success(allowed)
            case wp =>
                if (isOwner) {
                    val binding = wp.binding.get
                    val pkgOwner = namespaces.contains(binding.namespace.root())
                    val pkgDocid = binding.docid
                    info(this, s"checking subject has privilege '$right' for bound package '$pkgDocid'")
                    checkPackageReadPermission(promise, namespaces, pkgOwner, pkgDocid)
                } else {
                    info(this, s"entitlement check on package binding, '$right' allowed?: false")
                    promise.success(false)
                }
        } onFailure {
            case t =>
                error(this, s"entitlement check on package failed: ${t.getMessage}")
                promise.success(false)
        }
    }
}
