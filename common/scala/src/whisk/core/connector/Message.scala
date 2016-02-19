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

package whisk.core.connector

import scala.util.Try

import spray.json.DefaultJsonProtocol
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.TransactionId
import whisk.core.entity.ActivationId
import whisk.core.entity.Subject

case class Message(
    transid: TransactionId,
    path: String,
    subject: Subject,
    activationId: ActivationId,
    content: Option[JsObject],
    cause: Option[ActivationId] = None) {

    def meta = JsObject("meta" -> {
        cause map {
            c => JsObject(c.toJsObject.fields ++ activationId.toJsObject.fields)
        } getOrElse {
            activationId.toJsObject
        }
    })

    override def toString = {
        val value = (content getOrElse JsObject()).compactPrint
        s"$path?message=$value"
    }
}

object Message extends DefaultJsonProtocol {
    val ACTIVATOR = "whisk"
    val INVOKER = "invoke"

    def publish(component: String) = s"/publish/$component"

    def apply(msg: String): Try[Message] = Try {
        serdes.read(msg.parseJson)
    }

    private implicit object TransidJsonForat extends RootJsonFormat[TransactionId] {
        def write(tid: TransactionId) = tid.id.toJson

        def read(value: JsValue) = Try {
            val JsNumber(tid) = value
            TransactionId(tid).get
        } getOrElse deserializationError("transaction id malformed")
    }

    implicit val serdes = jsonFormat6(Message.apply)
}