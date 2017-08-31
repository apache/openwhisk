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

package whisk.core.entity

import scala.concurrent.Future
import scala.util.Try

import spray.json._
import types.AuthStore
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.MultipleReadersSingleWriterCache
import whisk.core.database.NoDocumentException
import whisk.core.database.StaleParameter
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Privilege.Privilege

case class UserLimits(invocationsPerMinute: Option[Int] = None, concurrentInvocations: Option[Int] = None, firesPerMinute: Option[Int] = None)

object UserLimits extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat3(UserLimits.apply)
}

protected[core] case class Identity(subject: Subject, namespace: EntityName, authkey: AuthKey, rights: Set[Privilege], limits: UserLimits = UserLimits()) {
    def uuid = authkey.uuid
}

object Identity extends MultipleReadersSingleWriterCache[Identity, DocInfo] with DefaultJsonProtocol {

    private val viewName = "subjects/identities"

    override val cacheEnabled = true
    implicit val serdes = jsonFormat5(Identity.apply)

    /**
     * Retrieves a key for namespace.
     * There may be more than one key for the namespace, in which case,
     * one is picked arbitrarily.
     */
    def get(datastore: AuthStore, namespace: EntityName)(
        implicit transid: TransactionId): Future[Identity] = {
        implicit val logger: Logging = datastore.logging
        implicit val ec = datastore.executionContext
        val ns = namespace.asString
        val key = CacheKey(namespace)

        cacheLookup(key, {
            list(datastore, List(ns), limit = 1) map { list =>
                list.length match {
                    case 1 =>
                        rowToIdentity(list.head, ns)
                    case 0 =>
                        logger.info(this, s"$viewName[$namespace] does not exist")
                        throw new NoDocumentException("namespace does not exist")
                    case _ =>
                        logger.error(this, s"$viewName[$namespace] is not unique")
                        throw new IllegalStateException("namespace is not unique")
                }
            }
        })
    }

    def get(datastore: AuthStore, authkey: AuthKey)(
        implicit transid: TransactionId): Future[Identity] = {
        implicit val logger: Logging = datastore.logging
        implicit val ec = datastore.executionContext

        cacheLookup(CacheKey(authkey), {
            list(datastore, List(authkey.uuid.asString, authkey.key.asString)) map { list =>
                list.length match {
                    case 1 =>
                        rowToIdentity(list.head, authkey.uuid.asString)
                    case 0 =>
                        logger.info(this, s"$viewName[${authkey.uuid}] does not exist")
                        throw new NoDocumentException("uuid does not exist")
                    case _ =>
                        logger.error(this, s"$viewName[${authkey.uuid}] is not unique")
                        throw new IllegalStateException("uuid is not unique")
                }
            }
        })
    }

    def list(datastore: AuthStore, key: List[Any], limit: Int = 2)(
        implicit transid: TransactionId): Future[List[JsObject]] = {
        datastore.query(viewName,
            startKey = key,
            endKey = key,
            skip = 0,
            limit = limit,
            includeDocs = true,
            descending = true,
            reduce = false,
            stale = StaleParameter.No)
    }

    private def rowToIdentity(row: JsObject, key: String)(
        implicit transid: TransactionId, logger: Logging) = {
        row.getFields("id", "value", "doc") match {
            case Seq(JsString(id), JsObject(value), doc) =>
                val limits = Try(doc.convertTo[UserLimits]).getOrElse(UserLimits())
                val subject = Subject(id)
                val JsString(uuid) = value("uuid")
                val JsString(secret) = value("key")
                val JsString(namespace) = value("namespace")
                Identity(subject, EntityName(namespace), AuthKey(UUID(uuid), Secret(secret)), Privilege.ALL, limits)
            case _ =>
                logger.error(this, s"$viewName[$key] has malformed view '${row.compactPrint}'")
                throw new IllegalStateException("identities view malformed")
        }
    }
}
