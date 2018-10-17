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

import scala.util.Try

import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError

protected[core] class Subject private (private val subject: String) extends AnyVal {
  protected[core] def asString = subject // to make explicit that this is a string conversion
  protected[entity] def toJson = JsString(subject)
  override def toString = subject
}

protected[core] object Subject extends ArgNormalizer[Subject] {

  /** Minimum subject length */
  protected[core] val MIN_LENGTH = 5

  /**
   * Creates a Subject from a string.
   *
   * @param str the subject name, at least 6 characters
   * @return Subject instance
   * @throws IllegalArgumentException is argument is undefined
   */
  @throws[IllegalArgumentException]
  override protected[entity] def factory(str: String): Subject = {
    require(str.length >= MIN_LENGTH, s"subject must be at least $MIN_LENGTH characters")
    new Subject(str)
  }

  /**
   * Creates a random subject
   *
   * @return Subject
   */
  protected[core] def apply(): Subject = {
    Subject("anon-" + rand.alphanumeric.take(27).mkString)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[Subject] {
    def write(s: Subject) = s.toJson

    def read(value: JsValue) =
      Try {
        val JsString(s) = value
        Subject(s)
      } getOrElse deserializationError("subject malformed")
  }

  private val rand = new scala.util.Random()
}
