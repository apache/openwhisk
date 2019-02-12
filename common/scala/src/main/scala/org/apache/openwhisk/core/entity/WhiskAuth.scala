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

import spray.json._
import scala.util.Try

/**
 * Represents a namespace for a subject as stored in the authentication
 * database. Each namespace has its own key which is used to determine
 * the {@ Identity} of the user calling.
 */
protected[core] case class WhiskNamespace(namespace: Namespace, authkey: BasicAuthenticationAuthKey)

protected[core] object WhiskNamespace extends DefaultJsonProtocol {
  implicit val serdes = new RootJsonFormat[WhiskNamespace] {
    def write(w: WhiskNamespace) =
      JsObject("name" -> w.namespace.name.toJson, "uuid" -> w.namespace.uuid.toJson, "key" -> w.authkey.key.toJson)

    def read(value: JsValue) =
      Try {
        value.asJsObject.getFields("name", "uuid", "key") match {
          case Seq(JsString(n), JsString(u), JsString(k)) =>
            WhiskNamespace(Namespace(EntityName(n), UUID(u)), BasicAuthenticationAuthKey(UUID(u), Secret(k)))
        }
      } getOrElse deserializationError("namespace record malformed")
  }
}

/**
 * Represents the new version of entries in the subjects database. No
 * top-level authkey is given but each subject has a set of namespaces,
 * which in turn have the keys.
 */
protected[core] case class WhiskAuth(subject: Subject, namespaces: Set[WhiskNamespace]) extends WhiskDocument {

  override def docid = DocId(subject.asString)

  def toJson = JsObject("subject" -> subject.toJson, "namespaces" -> namespaces.toJson)
}

protected[core] object WhiskAuth extends DefaultJsonProtocol {
  // Need to explicitly set field names since WhiskAuth extends WhiskDocument
  // which defines more than the 2 "standard" fields
  implicit val serdes = jsonFormat(WhiskAuth.apply, "subject", "namespaces")
}
