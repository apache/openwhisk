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

package org.apache.openwhisk.core.entity

import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.database.{
  MultipleReadersSingleWriterCache,
  NoDocumentException,
  StaleParameter,
  WriteTime
}
import org.apache.openwhisk.core.entitlement.Privilege
import org.apache.openwhisk.core.entity.types.AuthStore
import spray.json._

import scala.concurrent.Future
import scala.util.Try

case class UserLimits(invocationsPerMinute: Option[Int] = None,
                      concurrentInvocations: Option[Int] = None,
                      firesPerMinute: Option[Int] = None,
                      allowedKinds: Option[Set[String]] = None,
                      storeActivations: Option[Boolean] = None)

object UserLimits extends DefaultJsonProtocol {
  val standardUserLimits = UserLimits()

  implicit val serdes = jsonFormat5(UserLimits.apply)
}

protected[core] case class Namespace(name: EntityName, uuid: UUID)

protected[core] object Namespace extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(Namespace.apply)
}

protected[core] case class Identity(subject: Subject,
                                    namespace: Namespace,
                                    authkey: GenericAuthKey,
                                    rights: Set[Privilege] = Set.empty,
                                    limits: UserLimits = UserLimits.standardUserLimits)

object Identity extends MultipleReadersSingleWriterCache[Option[Identity], DocInfo] with DefaultJsonProtocol {

  private val viewName = WhiskQueries.view(WhiskQueries.dbConfig.subjectsDdoc, "identities").name

  override val cacheEnabled = true
  override val evictionPolicy = WriteTime
  // upper bound for the auth cache to prevent memory pollution by sending
  // malicious namespace patterns
  override val fixedCacheSize = 100000

  implicit val serdes = jsonFormat5(Identity.apply)

  /**
   * Retrieves a key for namespace.
   * There may be more than one key for the namespace, in which case,
   * one is picked arbitrarily.
   */
  def get(datastore: AuthStore, namespace: EntityName)(implicit transid: TransactionId): Future[Identity] = {
    implicit val logger: Logging = datastore.logging
    implicit val ec = datastore.executionContext
    val ns = namespace.asString
    val key = CacheKey(namespace)

    cacheLookup(
      key, {
        list(datastore, List(ns), limit = 1) map { list =>
          list.length match {
            case 1 =>
              Some(rowToIdentity(list.head, ns))
            case 0 =>
              logger.info(this, s"$viewName[$namespace] does not exist")
              None
            case _ =>
              logger.error(this, s"$viewName[$namespace] is not unique")
              throw new IllegalStateException("namespace is not unique")
          }
        }
      }).map(_.getOrElse(throw new NoDocumentException("namespace does not exist")))
  }

  def get(datastore: AuthStore, authkey: BasicAuthenticationAuthKey)(
    implicit transid: TransactionId): Future[Identity] = {
    implicit val logger: Logging = datastore.logging
    implicit val ec = datastore.executionContext

    cacheLookup(
      CacheKey(authkey), {
        list(datastore, List(authkey.uuid.asString, authkey.key.asString)) map { list =>
          list.length match {
            case 1 =>
              Some(rowToIdentity(list.head, authkey.uuid.asString))
            case 0 =>
              logger.info(this, s"$viewName[${authkey.uuid}] does not exist")
              None
            case _ =>
              logger.error(this, s"$viewName[${authkey.uuid}] is not unique")
              throw new IllegalStateException("uuid is not unique")
          }
        }
      }).map(_.getOrElse(throw new NoDocumentException("namespace does not exist")))
  }

  def list(datastore: AuthStore, key: List[Any], limit: Int = 2)(
    implicit transid: TransactionId): Future[List[JsObject]] = {
    datastore.query(
      viewName,
      startKey = key,
      endKey = key,
      skip = 0,
      limit = limit,
      includeDocs = true,
      descending = true,
      reduce = false,
      stale = StaleParameter.No)
  }

  protected[entity] def rowToIdentity(row: JsObject, key: String)(implicit transid: TransactionId, logger: Logging) = {
    row.getFields("id", "value", "doc") match {
      case Seq(JsString(id), JsObject(value), doc) =>
        val limits =
          if (doc != JsNull) Try(doc.convertTo[UserLimits]).getOrElse(UserLimits.standardUserLimits)
          else UserLimits.standardUserLimits
        val subject = Subject(id)
        val JsString(uuid) = value("uuid")
        val JsString(secret) = value("key")
        val JsString(namespace) = value("namespace")
        Identity(
          subject,
          Namespace(EntityName(namespace), UUID(uuid)),
          BasicAuthenticationAuthKey(UUID(uuid), Secret(secret)),
          Privilege.ALL,
          limits)
      case _ =>
        logger.error(this, s"$viewName[$key] has malformed view '${row.compactPrint}'")
        throw new IllegalStateException("identities view malformed")
    }
  }
}
