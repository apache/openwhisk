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

import scala.language.postfixOps
import org.apache.openwhisk.core.ConfigKeys
import spray.json.{deserializationError, JsNumber, JsValue, RootJsonFormat}
import org.apache.openwhisk.core.entity.size.SizeInt
import pureconfig.loadConfigOrThrow
import org.apache.openwhisk.core.entity.size._

import scala.util.{Failure, Success, Try}

protected[entity] class ParameterLimit private (val megabytes: Int) extends AnyVal {
  def toByteSize: ByteSize = ByteSize(megabytes, SizeUnits.MB)
}

protected[core] object ParameterLimit extends ArgNormalizer[ParameterLimit] {

  protected[core] val MAX_SIZE = loadConfigOrThrow[ByteSize](ConfigKeys.parameterSizeLimit) // system limit
  protected[core] val MAX_SIZE_DEFAULT = loadConfigOrThrow[ByteSize](ConfigKeys.namespaceParameterSizeLimit) // namespace default limit

  require(MAX_SIZE >= MAX_SIZE_DEFAULT, "The system limit must be greater than the namespace limit.")

  /**
   * Creates ParameterLimit for parameter, iff limit is within permissible range.
   *
   * @param megabytes the limit in megabytes, must be within permissible range
   * @return ParameterLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(megabytes: ByteSize): ParameterLimit = {
    new ParameterLimit(megabytes.toMB.toInt)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[ParameterLimit] {
    def write(m: ParameterLimit) = JsNumber(m.megabytes)

    def read(value: JsValue) =
      Try {
        val JsNumber(mb) = value
        require(mb.isWhole, "parameter limit must be whole number")
        ParameterLimit(mb.intValue MB)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("parameter limit malformed", e)
      }
  }
}
