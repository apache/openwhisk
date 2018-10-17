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

package org.apache.openwhisk.utils

import spray.json.JsObject
import spray.json.JsValue

object JsHelpers {
  def getFieldPath(js: JsObject, path: List[String]): Option[JsValue] = {
    path match {
      case Nil      => Option(js)
      case p :: Nil => js.fields.get(p)
      case p :: tail =>
        js.fields.get(p) match {
          case Some(o: JsObject) => getFieldPath(o, tail)
          case Some(_)           => None // head exists but value is not an object so cannot project further
          case None              => None // head doesn't exist, cannot project further
        }
    }
  }

  def getFieldPath(js: JsObject, path: String*): Option[JsValue] = {
    getFieldPath(js, path.toList)
  }

  def fieldPathExists(js: JsObject, path: List[String]): Boolean = getFieldPath(js, path).isDefined
  def fieldPathExists(js: JsObject, path: String*): Boolean = fieldPathExists(js, path.toList)
}
