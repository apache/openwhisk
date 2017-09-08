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

import java.math.BigInteger

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json._

import whisk.core.entity.size._
import whisk.http.Messages

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
protected[whisk] class ActivationId private (private val id: java.util.UUID) extends AnyVal {
  def asString = toString
  override def toString = id.toString.replaceAll("-", "")
  def toJsObject = JsObject("activationId" -> toString.toJson)
}

protected[core] object ActivationId extends ArgNormalizer[ActivationId] {

  protected[core] trait ActivationIdGenerator {
    def make(): ActivationId = new ActivationId(java.util.UUID.randomUUID())
  }

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
  private def apply(uuid: java.util.UUID): ActivationId = {
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

    def read(value: JsValue) =
      Try {
        value match {
          case JsString(s) => stringToActivationId(s)
          case JsNumber(n) => bigIntToActivationId(n.toBigInt)
          case _           => deserializationError(Messages.activationIdIllegal)
        }
      } match {
        case Success(a)                                 => a
        case Failure(DeserializationException(t, _, _)) => deserializationError(t)
        case Failure(t)                                 => deserializationError(Messages.activationIdIllegal)
      }
  }

  private def bigIntToActivationId(n: BigInt): ActivationId = {
    // print the bigint using base 10 then convert to base 16
    val bn = new BigInteger(n.bigInteger.toString(10), 16)
    // mask out the upper 16 ints
    val lb = bn.and(new BigInteger("f" * 16, 16))
    // drop the lower 16 ints
    val up = bn.shiftRight(16)
    val uuid = new java.util.UUID(lb.longValue, up.longValue)
    ActivationId(uuid)
  }

  private def stringToActivationId(s: String): ActivationId = {
    if (!s.contains("-")) {
      if (s.length == 32) {
        val lb = new BigInteger(s.substring(0, 16), 16)
        val up = new BigInteger(s.substring(16, 32), 16)
        val uuid = new java.util.UUID(lb.longValue, up.longValue)
        ActivationId(uuid)
      } else
        deserializationError {
          Messages.activationIdLengthError(SizeError("Activation id", s.length.B, 32.B))
        }
    } else {
      ActivationId(java.util.UUID.fromString(s))
    }

  }
}
