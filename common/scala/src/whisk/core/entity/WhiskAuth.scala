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
import scala.util.Try

import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory
import whisk.core.database.NoDocumentException

/**
 * A WhiskAuth provides an abstraction of the meta-data
 * for a whisk subject authentication record.
 *
 * The WhiskAuth object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskAuth abstraction.
 *
 * @param subject the subject identifier
 * @param authkey the uuid and key pair for subject
 * @throws IllegalArgumentException if any argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskAuth(
    subject: Subject,
    authkey: AuthKey)
    extends WhiskDocument {

    def uuid = authkey.uuid
    def key = authkey.key
    def revoke = WhiskAuth(subject, authkey.revoke)
    def compact = authkey.toString

    override def toString = {
        s"""|subject: $subject
            |auth: $authkey""".stripMargin.replace("\n", ", ")
    }

    override def docid = DocId(subject())

    def toJson = JsObject(
        "subject" -> subject.toJson,
        "uuid" -> authkey.uuid.toJson,
        "key" -> authkey.key.toJson)
}

object WhiskAuth extends DocumentFactory[WhiskAuth] {

    private val viewName = "subjects/uuids"

    private def apply(s: Subject, u: UUID, k: Secret): WhiskAuth = {
        WhiskAuth(s, AuthKey(u, k))
    }

    implicit val serdes = new RootJsonFormat[WhiskAuth] {
        def write(w: WhiskAuth) = w.toJson

        def read(value: JsValue) = Try {
            value.asJsObject.getFields("subject", "uuid", "key") match {
                case Seq(JsString(s), JsString(u), JsString(k)) =>
                    WhiskAuth(Subject(s), AuthKey(UUID(u), Secret(k)))
            }
        } getOrElse deserializationError("auth record malformed")
    }

    override val cacheEnabled = true
    override def cacheKeys(w: WhiskAuth) = Set(w.docid.asDocInfo, w.docinfo, w.uuid)

    def get(datastore: ArtifactStore[WhiskAuth], subject: Subject, fromCache: Boolean)(
        implicit transid: TransactionId): Future[WhiskAuth] = {
        implicit val logger: Logging = datastore
        super.get(datastore, DocInfo(subject()), fromCache)
    }

    def get(datastore: ArtifactStore[WhiskAuth], uuid: UUID)(
        implicit transid: TransactionId): Future[WhiskAuth] = {
        implicit val logger: Logging = datastore
        // it is assumed that there exists at most one record matching the uuid
        // hence it is safe to cache the result of this query result since a put
        // on the auth record will invalidate the cached query result as well
        cacheLookup(datastore, uuid, {
            implicit val ec = datastore.executionContext
            list(datastore, uuid) map { list =>
                list.length match {
                    case 1 =>
                        val row = list(0)
                        row.getFields("id", "value") match {
                            case Seq(JsString(id), JsObject(key)) =>
                                val subject = Subject(id)
                                val JsString(secret) = key("secret")
                                WhiskAuth(subject, AuthKey(uuid, Secret(secret)))
                            case _ =>
                                logger.error(this, s"$viewName[$uuid] has malformed view '${row.compactPrint}'")
                                throw new IllegalStateException("auth view malformed")
                        }
                    case 0 =>
                        logger.info(this, s"$viewName[$uuid] does not exist")
                        throw new NoDocumentException("uuid does not exist")
                    case _ =>
                        logger.error(this, s"$viewName[$uuid] is not unique")
                        throw new IllegalStateException("uuid is not unique")
                }
            }
        })
    }

    def list(datastore: ArtifactStore[WhiskAuth], uuid: UUID)(
        implicit transid: TransactionId): Future[List[JsObject]] = {
        val key = List(uuid.toString)
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
