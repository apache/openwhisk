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

package whisk.core.entity

import scala.concurrent.Future

import spray.json._
import types.AuthStore
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.MultipleReadersSingleWriterCache
import whisk.core.database.NoDocumentException
import whisk.core.entitlement.Privilege
import whisk.core.entitlement.Privilege.Privilege

protected[core] case class Identity(subject: Subject, namespace: EntityName, authkey: AuthKey, rights: Set[Privilege])

object Identities extends MultipleReadersSingleWriterCache[Identity, DocInfo] {

    private val viewName = "subjects/identities"

    override val cacheEnabled = true
    override def cacheKeyForUpdate(i: Identity) = i.authkey

    def get(datastore: AuthStore, authkey: AuthKey)(
        implicit transid: TransactionId): Future[Identity] = {
        implicit val logger: Logging = datastore
        implicit val ec = datastore.executionContext

        cacheLookup(authkey, {
            list(datastore, authkey) map { list =>
                list.length match {
                    case 1 =>
                        val row = list.head
                        row.getFields("id", "value") match {
                            case Seq(JsString(id), JsObject(value)) =>
                                val subject = Subject(id)
                                val JsString(uuid) = value("uuid")
                                val JsString(secret) = value("key")
                                val JsString(namespace) = value("namespace")
                                Identity(subject, EntityName(namespace), AuthKey(UUID(uuid), Secret(secret)), Privilege.ALL)
                            case _ =>
                                logger.error(this, s"$viewName[${authkey.uuid}] has malformed view '${row.compactPrint}'")
                                throw new IllegalStateException("identities view malformed")
                        }
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

    def list(datastore: AuthStore, authkey: AuthKey)(
        implicit transid: TransactionId): Future[List[JsObject]] = {
        val key = List(authkey.uuid.toString, authkey.key.toString)
        datastore.query(viewName,
            startKey = key,
            endKey = key,
            skip = 0,
            limit = 2,
            includeDocs = false,
            descending = true,
            reduce = false)
    }
}
