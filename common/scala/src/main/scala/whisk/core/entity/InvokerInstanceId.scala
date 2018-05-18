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

import spray.json.DefaultJsonProtocol

case class InvokerInstanceId(val instance: Int, name: Option[String] = None) {
  def toInt: Int = instance
}

case class ControllerInstanceId(private val instance: String) {
  //keep instance private, since we will replace illegal chars for kafka usage

  //validate once on construction
  val asString = ControllerInstanceId.ILLEGAL_CHARS.replaceAllIn(instance, "")
  require(
    asString.length <= ControllerInstanceId.MAX_NAME_LENGTH,
    s"topic name can be at most $ControllerInstanceId.MAX_NAME_LENGTH $asString")
}
object InvokerInstanceId extends DefaultJsonProtocol {
  implicit val serdes = jsonFormat2(InvokerInstanceId.apply)
}
object ControllerInstanceId extends DefaultJsonProtocol {
  //for kafka topic legal chars see https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/internals/Topic.java#L29
  private val ILLEGAL_CHARS = "[^a-zA-Z0-9._-]".r
  //reserve 20 (arbitrary) chars for prefix to be prepended to create topic names
  private val MAX_NAME_LENGTH = 249 - 20
  implicit val s = jsonFormat[String, ControllerInstanceId](ControllerInstanceId.apply, "instance")
}
