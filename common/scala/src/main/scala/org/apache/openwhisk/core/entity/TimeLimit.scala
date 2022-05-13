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

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.http.Messages

/**
 * TimeLimit encapsulates a duration for an action. The duration must be within a
 * permissible range (currently [100 msecs, 5 minutes]).
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param duration the duration for the action, required not null
 */
protected[entity] class TimeLimit private (val duration: FiniteDuration) extends AnyVal {
  protected[core] def millis = duration.toMillis.toInt
  override def toString = duration.toString

  /** It checks the namespace duration limit setting value  */
  @throws[ActionTimeLimitException]
  protected[core] def checkNamespaceLimit(user: Identity): Unit = {
    val durationMax = user.limits.allowedMaxActionTimeout
    val durationMix = user.limits.allowedMinActionTimeout
    try {
      require(
        duration <= durationMax,
        Messages.durationExceedsAllowedThreshold(TimeLimit.timeLimitFieldName, duration, durationMax))
      require(
        duration >= durationMix,
        Messages.durationBelowAllowedThreshold(TimeLimit.timeLimitFieldName, duration, durationMix))
    } catch {
      case e: IllegalArgumentException => throw ActionTimeLimitException(e.getMessage)
    }
  }
}

case class NamespaceTimeLimitConfig(max: FiniteDuration, min: FiniteDuration)
case class TimeLimitConfig(max: FiniteDuration, min: FiniteDuration, std: FiniteDuration)

protected[core] object TimeLimit extends ArgNormalizer[TimeLimit] {
  val config = loadConfigOrThrow[TimeLimitConfig](ConfigKeys.timeLimit)
  val namespaceDefaultConfig = try {
    loadConfigOrThrow[NamespaceTimeLimitConfig](ConfigKeys.namespaceTimeLimit)
  } catch {
    case _: Throwable =>
      // Supports backwards compatibility for openwhisk that do not use the namespace default limit
      NamespaceTimeLimitConfig(config.max, config.min)
  }
  val timeLimitFieldName = "duration"

  /**
   * These system limits and namespace default limits are set once at the beginning.
   * Dynamic configuration updates are not supported at the moment.
   */
  protected[core] val STD_DURATION: FiniteDuration = config.std
  protected[core] val MIN_DURATION: FiniteDuration = config.min
  protected[core] val MAX_DURATION: FiniteDuration = config.max

  /** Default namespace limit used if there is no namespace-specific limit */
  protected[core] val MIN_DURATION_DEFAULT: FiniteDuration = namespaceDefaultConfig.min
  protected[core] val MAX_DURATION_DEFAULT: FiniteDuration = namespaceDefaultConfig.max

  require(MAX_DURATION >= MAX_DURATION_DEFAULT, "The system max limit must be greater than the namespace max limit.")
  require(MIN_DURATION <= MIN_DURATION_DEFAULT, "The system min limit must be less than the namespace min limit.")

  /** A singleton TimeLimit with default value */
  protected[core] val standardTimeLimit = TimeLimit(STD_DURATION)

  /** Gets TimeLimit with default duration */
  protected[core] def apply(): TimeLimit = standardTimeLimit

  /**
   * Creates TimeLimit for duration, iff duration is within permissible range.
   *
   * @param duration the duration in milliseconds, must be within permissible range
   * @return TimeLimit with duration set
   * @throws IllegalArgumentException if duration does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(duration: FiniteDuration): TimeLimit = {
    require(duration != null, s"duration undefined")
    new TimeLimit(duration)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[TimeLimit] {
    def write(t: TimeLimit) = JsNumber(t.millis)

    def read(value: JsValue) =
      Try {
        val JsNumber(ms) = value
        require(ms.isWhole, "time limit must be whole number")
        TimeLimit(Duration(ms.intValue, MILLISECONDS))
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("time limit malformed", e)
      }
  }
}
