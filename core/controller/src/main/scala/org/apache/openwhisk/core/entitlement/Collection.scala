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

import org.apache.openwhisk.core.entitlement.Privilege._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.http.scaladsl.model.HttpMethod
import akka.http.scaladsl.model.HttpMethods.DELETE
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.HttpMethods.PUT
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.Identity
import org.apache.openwhisk.core.entity.WhiskAction
import org.apache.openwhisk.core.entity.WhiskActivation
import org.apache.openwhisk.core.entity.WhiskPackage
import org.apache.openwhisk.core.entity.WhiskRule
import org.apache.openwhisk.core.entity.WhiskTrigger
import org.apache.openwhisk.core.entity.types.EntityStore
import pureconfig._
import pureconfig.generic.auto._

/**
 * A collection encapsulates the name of a collection and implicit rights when subject
 * lacks explicit rights on a resource in the collection.
 *
 * @param path the name of the collection (the resource path in URI and the view name in the datastore)
 * @param defaultListLimit the default limit on number of entities returned from a collection on a list operation
 * @param defaultListSkip the default skip on number of entities returned from a collection on a list operation
 */
protected[core] case class Collection protected (val path: String,
                                                 val defaultListLimit: Int = Collection.DEFAULT_LIST_LIMIT,
                                                 val defaultListSkip: Int = Collection.DEFAULT_SKIP_LIMIT) {
  override def toString = path

  /** Determines the right to request for the resources and context. */
  protected[core] def determineRight(op: HttpMethod, resource: Option[String])(
    implicit transid: TransactionId): Privilege = {
    op match {
      case GET => Privilege.READ
      case PUT =>
        resource map { _ =>
          Privilege.PUT
        } getOrElse Privilege.REJECT
      case POST =>
        resource map { _ =>
          activateAllowed
        } getOrElse Privilege.REJECT
      case DELETE =>
        resource map { _ =>
          Privilege.DELETE
        } getOrElse Privilege.REJECT
      case _ => Privilege.REJECT
    }
  }

  protected val allowedCollectionRights: Set[Privilege] = Set(Privilege.READ)
  protected val allowedEntityRights: Set[Privilege] = {
    Set(Privilege.READ, Privilege.PUT, Privilege.ACTIVATE, Privilege.DELETE)
  }

  private lazy val activateAllowed = {
    if (allowedEntityRights.contains(Privilege.ACTIVATE)) {
      Privilege.ACTIVATE
    } else Privilege.REJECT
  }

  /**
   * Infers implicit rights on a resource in the collection before checking explicit
   * rights in the entitlement matrix. The subject has CRUD and activate rights
   * to any resource in in any of their namespaces as long as the right (the implied operation)
   * is permitted on the resource.
   */
  protected[core] def implicitRights(user: Identity, namespaces: Set[String], right: Privilege, resource: Resource)(
    implicit ep: EntitlementProvider,
    ec: ExecutionContext,
    transid: TransactionId): Future[Boolean] = Future.successful {
    // if the resource root namespace is in any of the allowed namespaces
    // then this is an owner of the resource
    val self = namespaces.contains(resource.namespace.root.asString)

    resource.entity map { _ =>
      self && allowedEntityRights.contains(right)
    } getOrElse {
      self && allowedCollectionRights.contains(right)
    }
  }
}

/** An enumeration of known collections. */
protected[core] object Collection {

  private case class QueryLimit(maxListLimit: Int, defaultListLimit: Int)
  private val queryLimit = loadConfigOrThrow[QueryLimit](ConfigKeys.query)

  /** Number of records allowed per query. */
  protected[core] val DEFAULT_LIST_LIMIT = queryLimit.defaultListLimit
  protected[core] val MAX_LIST_LIMIT = queryLimit.maxListLimit
  protected[core] val DEFAULT_SKIP_LIMIT = 0

  protected[core] val ACTIONS = WhiskAction.collectionName
  protected[core] val TRIGGERS = WhiskTrigger.collectionName
  protected[core] val RULES = WhiskRule.collectionName
  protected[core] val PACKAGES = WhiskPackage.collectionName
  protected[core] val ACTIVATIONS = WhiskActivation.collectionName
  protected[core] val NAMESPACES = "namespaces"
  protected[core] val LIMITS = "limits"

  private val collections = scala.collection.mutable.Map[String, Collection]()
  private def register(c: Collection) = collections += c.path -> c

  protected[core] def apply(name: String) = collections.get(name).get

  protected[core] def initialize(entityStore: EntityStore)(implicit logging: Logging) = {
    register(new ActionCollection(entityStore))
    register(new Collection(TRIGGERS))
    register(new Collection(RULES))
    register(new PackageCollection(entityStore))

    register(new Collection(ACTIVATIONS) {
      protected[core] override def determineRight(op: HttpMethod,
                                                  resource: Option[String])(implicit transid: TransactionId) = {
        if (op == GET) Privilege.READ else Privilege.REJECT
      }

      protected override val allowedEntityRights: Set[Privilege] = Set(Privilege.READ)
    })

    register(new Collection(NAMESPACES) {
      protected[core] override def determineRight(op: HttpMethod,
                                                  resource: Option[String])(implicit transid: TransactionId) = {
        resource map { _ =>
          Privilege.REJECT
        } getOrElse {
          if (op == GET) Privilege.READ else Privilege.REJECT
        }
      }

      protected override val allowedEntityRights: Set[Privilege] = Set(Privilege.READ)
    })

    register(new Collection(LIMITS) {
      protected[core] override def determineRight(op: HttpMethod,
                                                  resource: Option[String])(implicit transid: TransactionId) = {
        if (op == GET) Privilege.READ else Privilege.REJECT
      }
    })
  }
}
