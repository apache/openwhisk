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
import spray.json.JsObject
import spray.json.pimpString
import whisk.common.TransactionId
import whisk.core.entity.ActivationId
import whisk.core.entity.Subject
import whisk.core.entity.WhiskActivation

/** Basic trait for messages that are sent on a message bus connector. */
trait Message {
    /**
     * A transaction id to attach to the message. If not defined, defaults to 'dontcare' value.
     */
    val transid = TransactionId.unknown

    /**
     * Serializes message to string. Must be idempotent.
     */
    def serialize: String

    /**
     * String representation of the message. Delegates to serialize.
     */
    override def toString = serialize
}

case class ActivationMessage(
    override val transid: TransactionId,
    path: String,
    subject: Subject,
    activationId: ActivationId,
    content: Option[JsObject],
    cause: Option[ActivationId] = None)
    extends Message {

    def meta = JsObject("meta" -> {
        cause map {
            c => JsObject(c.toJsObject.fields ++ activationId.toJsObject.fields)
        } getOrElse {
            activationId.toJsObject
        }
    })

    override def serialize = ActivationMessage.serdes.write(this).compactPrint

    override def toString = {
        val value = (content getOrElse JsObject()).compactPrint
        s"$path?message=$value"
    }
}

object ActivationMessage extends DefaultJsonProtocol {
    val INVOKER = "invoke"

    def publish(component: String) = s"/publish/$component"

    def apply(msg: String): Try[ActivationMessage] = Try {
        serdes.read(msg.parseJson)
    }

    implicit val serdes = jsonFormat6(ActivationMessage.apply)
}

/**
 * When adding fields, the serdes of the companion object must be updated also.
 * The whisk activation field will have its logs stripped.
 */
case class CompletionMessage(
    override val transid: TransactionId,
    response: WhiskActivation)
    extends Message {

    override def serialize = CompletionMessage.serdes.write(this).compactPrint

    override def toString = {
        s"${response.activationId}"
    }
}

object CompletionMessage extends DefaultJsonProtocol {

    def apply(msg: String): Try[CompletionMessage] = Try {
        serdes.read(msg.parseJson)
    }

    implicit val serdes = jsonFormat2(CompletionMessage.apply)
}
