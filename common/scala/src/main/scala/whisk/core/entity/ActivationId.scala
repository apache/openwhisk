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

import java.math.BigInteger

import scala.util.Try

import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny
import scala.language.postfixOps

/**
 * An activation id, is a unique id assigned to activations (invoke action or fire trigger).
 * It must be globally unique.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param id the activation id, required not null
 */
protected[core] class ActivationId private (private val id: java.util.UUID) extends AnyVal {
    protected[core] def apply() = toString
    override def toString = id.toString.replaceAll("-", "")
    def toJsObject = JsObject("activationId" -> toString.toJson)
}

protected[core] object ActivationId extends ArgNormalizer[ActivationId] {

    /**
     * Unapply method for convenience of case matching.
     */
    protected[core] def unapply(name: String): Option[ActivationId] = {
        Try { ActivationId(name) } toOption
    }

    /**
     * Creates an activation id from a java.util.UUID.
     *
     * @param uuid the activation id as UUID
     * @return ActivationId instance
     * @throws IllegalArgumentException is argument is not defined
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(uuid: java.util.UUID): ActivationId = {
        require(uuid != null, "argument undefined")
        new ActivationId(uuid)
    }

    /**
     * Generates a random activation id using java.util.UUID factory.
     *
     * @return new ActivationId
     */
    protected[core] def apply(): ActivationId = new ActivationId(java.util.UUID.randomUUID())

    /**
     * Overrides factory method so that string is not interpreted as number
     * e.g., 2e11.
     */
    override protected[entity] def factory(s: String): ActivationId = {
        serdes.read(JsString(s))
    }

    override protected[core] implicit val serdes = new RootJsonFormat[ActivationId] {
        def write(d: ActivationId) = JsString(d.toString)

        def read(value: JsValue) = Try {
            val JsString(s) = value
            if (!s.contains("-")) {
                val lb = new BigInteger(s.substring(0, 16), 16)
                val up = new BigInteger(s.substring(16, 32), 16)
                val uuid = new java.util.UUID(lb.longValue(), up.longValue())
                ActivationId(uuid)
            } else {
                ActivationId(java.util.UUID.fromString(s))
            }
        } getOrElse deserializationError("activation id is malformed")
    }
}
