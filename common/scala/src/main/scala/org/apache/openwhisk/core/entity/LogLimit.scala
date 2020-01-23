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

import pureconfig._
import pureconfig.generic.auto._

import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.size._

case class LogLimitConfig(min: ByteSize, max: ByteSize, std: ByteSize)

/**
 * LogLimit encapsulates allowed amount of logs written by an action.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * Argument type is Int because of JSON deserializer vs. <code>ByteSize</code> and
 * compatibility with <code>MemoryLimit</code>.
 *
 * @param megabytes the log limit in megabytes for the action
 */
protected[core] class LogLimit private (val megabytes: Int) extends AnyVal {
  protected[core] def asMegaBytes: ByteSize = megabytes.megabytes
}

protected[core] object LogLimit extends ArgNormalizer[LogLimit] {
  val config = loadConfigOrThrow[MemoryLimitConfig](ConfigKeys.logLimit)

  /** These values are set once at the beginning. Dynamic configuration updates are not supported at the moment. */
  protected[core] val MIN_LOGSIZE: ByteSize = config.min
  protected[core] val MAX_LOGSIZE: ByteSize = config.max
  protected[core] val STD_LOGSIZE: ByteSize = config.std

  /** A singleton LogLimit with default value */
  protected[core] val standardLogLimit = LogLimit(STD_LOGSIZE)

  /** Gets LogLimit with default log limit */
  protected[core] def apply(): LogLimit = standardLogLimit

  /**
   * Creates LogLimit for limit. Only the default limit is allowed currently.
   *
   * @param megabytes the limit in megabytes, must be within permissible range
   * @return LogLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(megabytes: ByteSize): LogLimit = {
    require(megabytes >= MIN_LOGSIZE, s"log size $megabytes below allowed threshold of $MIN_LOGSIZE")
    require(megabytes <= MAX_LOGSIZE, s"log size $megabytes exceeds allowed threshold of $MAX_LOGSIZE")
    new LogLimit(megabytes.toMB.toInt)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[LogLimit] {
    def write(m: LogLimit) = JsNumber(m.megabytes)

    def read(value: JsValue) =
      Try {
        val JsNumber(mb) = value
        require(mb.isWhole, "log limit must be whole number")
        LogLimit(mb.intValue MB)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("log limit malformed", e)
      }
  }
}
