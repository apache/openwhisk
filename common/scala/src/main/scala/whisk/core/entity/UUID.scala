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

import scala.util.Try

import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError

/**
 * Wrapper for java.util.UUID.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param uuid the uuid, required not null
 */
protected[core] class UUID private (private val uuid: java.util.UUID) extends AnyVal {
  protected[core] def asString = toString
  protected[core] def snippet = toString.substring(0, 8)
  protected[entity] def toJson = JsString(toString)
  override def toString = uuid.toString
}

protected[core] object UUID extends ArgNormalizer[UUID] {

  /**
   * Creates a UUID from a string. The string must be a valid UUID.
   *
   * @param str the uuid as string
   * @return UUID instance
   * @throws IllegalArgumentException is argument is not a valid UUID
   */
  @throws[IllegalArgumentException]
  override protected[entity] def factory(str: String): UUID = {
    new UUID(java.util.UUID.fromString(str))
  }

  /**
   * Generates a random UUID using java.util.UUID factory.
   *
   * @return new UUID
   */
  protected[core] def apply(): UUID = new UUID(java.util.UUID.randomUUID())

  implicit val serdes = new RootJsonFormat[UUID] {
    def write(u: UUID) = u.toJson

    def read(value: JsValue) =
      Try {
        val JsString(u) = value
        UUID(u)
      } getOrElse deserializationError("uuid malformed")
  }
}
