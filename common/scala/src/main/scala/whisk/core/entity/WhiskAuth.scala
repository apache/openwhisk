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
import whisk.common.TransactionId
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory

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

    override def toString = {
        s"""|subject: $subject
            |auth: $authkey""".stripMargin.replace("\n", ", ")
    }

    override def docid = DocId(subject.asString)

    def toJson = JsObject(
        "subject" -> subject.toJson,
        "uuid" -> authkey.uuid.toJson,
        "key" -> authkey.key.toJson)

    def toIdentity = subject.toIdentity(authkey)
}

object WhiskAuth extends DocumentFactory[WhiskAuth] {

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
    override def cacheKeyForUpdate(w: WhiskAuth) = w.uuid

    def get(datastore: ArtifactStore[WhiskAuth], subject: Subject, fromCache: Boolean)(
        implicit transid: TransactionId): Future[Identity] = {
        implicit val ec = datastore.executionContext
        super.get(datastore, DocId(subject.asString), fromCache = fromCache).map(_.toIdentity)
    }
}
