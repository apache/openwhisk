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

package org.apache.openwhisk.core.entitlement

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.http.scaladsl.model.StatusCodes._

import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.controller.RejectRequest
import org.apache.openwhisk.core.database.DocumentTypeMismatchException
import org.apache.openwhisk.core.database.NoDocumentException
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.Messages

class PackageCollection(entityStore: EntityStore)(implicit logging: Logging) extends Collection(Collection.PACKAGES) {

  protected override val allowedEntityRights: Set[Privilege] = {
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
  protected[core] override def implicitRights(user: Identity,
                                              namespaces: Set[String],
                                              right: Privilege,
                                              resource: Resource)(implicit ep: EntitlementProvider,
                                                                  ec: ExecutionContext,
                                                                  transid: TransactionId): Future[Boolean] = {
    resource.entity map { pkgname =>
      val isOwner = namespaces.contains(resource.namespace.root.asString)
      right match {
        case Privilege.READ =>
          // must determine if this is a public or owned package
          // or, for a binding, that it references a public or owned package
          val docid = FullyQualifiedEntityName(resource.namespace.root.toPath, EntityName(pkgname)).toDocId
          checkPackageReadPermission(namespaces, isOwner, docid)
        case _ => Future.successful(isOwner && allowedEntityRights.contains(right))
      }
    } getOrElse {
      // only a READ on the package collection is permitted;
      // NOTE: currently, the implementation allows any subject to
      // list packages in any namespace, and defers the filtering of
      // public packages to non-owning subjects to the API handlers
      // for packages
      Future.successful(right == Privilege.READ)
    }
  }

  /**
   * @param namespaces the set of namespaces the subject is entitled to
   * @param isOwner indicates if the resource is owned by the subject requesting authorization
   * @param docid the package (or binding) document id
   */
  private def checkPackageReadPermission(namespaces: Set[String], isOwner: Boolean, doc: DocId)(
    implicit ec: ExecutionContext,
    transid: TransactionId): Future[Boolean] = {

    val right = Privilege.READ

    WhiskPackage.get(entityStore, doc) flatMap {
      case wp if wp.binding.isEmpty =>
        val allowed = wp.publish || isOwner
        logging.debug(this, s"entitlement check on package, '$right' allowed?: $allowed")
        Future.successful(allowed)
      case wp =>
        if (isOwner) {
          val binding = wp.binding.get
          val pkgOwner = namespaces.contains(binding.namespace.asString)
          val pkgDocid = binding.docid
          logging.debug(this, s"checking subject has privilege '$right' for bound package '$pkgDocid'")
          if (doc == pkgDocid) {
            logging.error(this, s"unexpected package binding refers to itself: $doc")
            Future.failed(
              RejectRequest(
                UnprocessableEntity,
                Messages.packageBindingCircularReference(binding.fullyQualifiedName.toString)))
          } else {
            checkPackageReadPermission(namespaces, pkgOwner, pkgDocid)
          }
        } else {
          logging.debug(this, s"entitlement check on package binding, '$right' allowed?: false")
          Future.successful(false)
        }
    } recoverWith {
      case t: NoDocumentException =>
        logging.debug(this, s"the package does not exist (owner? $isOwner)")
        // if owner, reject with not found, otherwise fail the future to reject with
        // unauthorized (this prevents information leaks about packages in other namespaces)
        if (isOwner) {
          Future.failed(RejectRequest(NotFound))
        } else {
          Future.successful(false)
        }
      case t: DocumentTypeMismatchException =>
        logging.debug(this, s"the requested binding is not a package (owner? $isOwner)")
        // if owner, reject with not found, otherwise fail the future to reject with
        // unauthorized (this prevents information leaks about packages in other namespaces)
        if (isOwner) {
          Future.failed(RejectRequest(Conflict, Messages.conformanceMessage))
        } else {
          Future.successful(false)
        }
      case t: RejectRequest =>
        logging.error(this, s"entitlement check on package failed: $t")
        Future.failed(t)
      case t =>
        logging.error(this, s"entitlement check on package failed: ${t.getMessage}")
        if (isOwner) {
          Future.failed(RejectRequest(InternalServerError, Messages.corruptedEntity))
        } else {
          Future.successful(false)
        }
    }
  }
}
