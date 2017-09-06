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

import scala.Vector
import scala.util.Try

import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny

protected[core] case class ActivationLogs(val logs: Vector[String] = Vector()) extends AnyVal {
  def toJsonObject = JsObject("logs" -> toJson)
  def toJson = JsArray(logs map { _.toJson })

  override def toString = logs mkString ("[", ", ", "]")
}

protected[core] object ActivationLogs {
  protected[core] implicit val serdes = new RootJsonFormat[ActivationLogs] {
    def write(l: ActivationLogs) = l.toJson

    def read(value: JsValue) =
      Try {
        val JsArray(logs) = value
        ActivationLogs(logs map {
          case JsString(s) => s
          case _           => deserializationError("activation logs malformed")
        })
      } getOrElse deserializationError("activation logs malformed")
  }
}
