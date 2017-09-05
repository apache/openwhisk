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

package whisk.core.connector

import scala.util.Try

import spray.json._
import whisk.common.TransactionId
import whisk.core.entity.ActivationId
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityPath
import whisk.core.entity.FullyQualifiedEntityName
import whisk.core.entity.Identity
import whisk.core.entity.InstanceId
import whisk.core.entity.WhiskActivation

/** Basic trait for messages that are sent on a message bus connector. */
trait Message {
    /**
     * A transaction id to attach to the message.
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
    action: FullyQualifiedEntityName,
    revision: DocRevision,
    user: Identity,
    activationId: ActivationId,
    activationNamespace: EntityPath,
    rootControllerIndex: InstanceId,
    blocking: Boolean,
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
        s"$action?message=$value"
    }

    def causedBySequence: Boolean = cause.isDefined
}

object ActivationMessage extends DefaultJsonProtocol {

    def parse(msg: String) = Try(serdes.read(msg.parseJson))

    private implicit val fqnSerdes = FullyQualifiedEntityName.serdes
    implicit val serdes = jsonFormat10(ActivationMessage.apply)
}

/**
 * When adding fields, the serdes of the companion object must be updated also.
 * The whisk activation field will have its logs stripped.
 */
case class CompletionMessage(
    override val transid: TransactionId,
    response: Either[ActivationId, WhiskActivation],
    invoker: InstanceId)
    extends Message {

    override def serialize: String = {
        CompletionMessage.serdes.write(this).compactPrint
    }

    override def toString = {
        response.fold(l => l, r => r.activationId).asString
    }
}

object CompletionMessage extends DefaultJsonProtocol {
    def parse(msg: String): Try[CompletionMessage] = Try(serdes.read(msg.parseJson))
    private val serdes = jsonFormat3(CompletionMessage.apply)
}

case class PingMessage(instance: InstanceId) extends Message {
    override def serialize = PingMessage.serdes.write(this).compactPrint
}

object PingMessage extends DefaultJsonProtocol {
    def parse(msg: String) = Try(serdes.read(msg.parseJson))
    implicit val serdes = jsonFormat(PingMessage.apply _, "name")
}
