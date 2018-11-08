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

import java.security.SecureRandom

import com.fasterxml.uuid.Generators

import spray.json._
import spray.json.DefaultJsonProtocol._

/**
 * Represents a user's username and/or a namespace identifier (generally looks like UUIDs)
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param asString the uuid in string representation
 */
protected[core] class UUID private (val asString: String) extends AnyVal {
  protected[core] def snippet: String = asString.substring(0, 8)
  protected[entity] def toJson: JsString = JsString(asString)
  override def toString: String = asString
}

protected[core] object UUID {

  /**
   * Generates a random UUID using java.util.UUID factory.
   *
   * @return new UUID
   */
  protected[core] def apply(): UUID = new UUID(UUIDs.randomUUID().toString)

  protected[core] def apply(str: String): UUID = new UUID(str)

  implicit val serdes: RootJsonFormat[UUID] = new RootJsonFormat[UUID] {
    def write(u: UUID): JsValue = u.toJson
    def read(value: JsValue): UUID = new UUID(value.convertTo[String])
  }
}

object UUIDs {
  private val generator = new ThreadLocal[SecureRandom] {
    override def initialValue() = new SecureRandom()
  }

  /**
   * Static factory to retrieve a type 4 (pseudo randomly generated) UUID.
   *
   * The {@code java.util.UUID} is generated using a pseudo random number
   * generator local to the thread.
   *
   * @return  A randomly generated {@code java.util.UUID}
   */
  def randomUUID(): java.util.UUID = Generators.randomBasedGenerator(generator.get()).generate()
}
