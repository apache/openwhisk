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

package org.apache.openwhisk.core.entitlement

import scala.util.Try

import spray.json.DeserializationException
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat

sealed trait Privilege

/** An enumeration of privileges available to subjects. */
protected[core] object Privilege extends Enumeration {

  case object READ extends Privilege
  case object PUT extends Privilege
  case object DELETE extends Privilege
  case object ACTIVATE extends Privilege
  case object REJECT extends Privilege

  val CRUD: Set[Privilege] = Set(READ, PUT, DELETE)
  val ALL: Set[Privilege] = CRUD + ACTIVATE

  def fromName(name: String) = name match {
    case "READ"     => READ
    case "PUT"      => PUT
    case "DELETE"   => DELETE
    case "ACTIVATE" => ACTIVATE
    case "REJECT"   => REJECT
  }

  implicit val serdes = new RootJsonFormat[Privilege] {
    def write(p: Privilege) = JsString(p.toString)

    def read(json: JsValue) =
      Try {
        val JsString(str) = json
        Privilege.fromName(str.trim.toUpperCase)
      } getOrElse {
        throw new DeserializationException("Privilege must be a valid string")
      }
  }
}
