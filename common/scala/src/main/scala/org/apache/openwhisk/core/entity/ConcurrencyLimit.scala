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

import com.typesafe.config.ConfigFactory
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.http.Messages
import pureconfig._
import pureconfig.generic.auto._

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json._

case class NamespaceConcurrencyLimitConfig(min: Int, max: Int)
case class ConcurrencyLimitConfig(min: Int, max: Int, std: Int)

/**
 * ConcurrencyLimit encapsulates allowed concurrency in a single container for an action. The limit must be within a
 * permissible range (by default [1, 1]). This default range was chosen intentionally to reflect that concurrency
 * is disabled by default.
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param maxConcurrent the max number of concurrent activations in a single container
 */
protected[entity] class ConcurrencyLimit private (val maxConcurrent: Int) extends AnyVal {

  /** It checks the namespace memory limit setting value  */
  @throws[ActionConcurrencyLimitException]
  protected[core] def checkNamespaceLimit(user: Identity): Unit = {
    val concurrencyMax = user.limits.allowedActionConcurrencyMax
    val concurrencyMin = user.limits.allowedActionConcurrencyMin
    try {
      require(
        maxConcurrent <= concurrencyMax,
        Messages.concurrencyExceedsAllowedThreshold(maxConcurrent, concurrencyMax))
      require(maxConcurrent >= concurrencyMin, Messages.concurrencyBelowAllowedThreshold(maxConcurrent, concurrencyMin))
    } catch {
      case e: IllegalArgumentException => throw ActionConcurrencyLimitException(e.getMessage)
    }
  }

  @throws[ActionConcurrencyLimitException]
  protected[core] def checkSystemLimit(): Unit = {
    try {
      require(
        maxConcurrent >= ConcurrencyLimit.MIN_CONCURRENT,
        Messages.concurrencyBelowAllowedThreshold(maxConcurrent, ConcurrencyLimit.MIN_CONCURRENT))
      require(
        maxConcurrent <= ConcurrencyLimit.MAX_CONCURRENT,
        Messages.concurrencyExceedsAllowedThreshold(maxConcurrent, ConcurrencyLimit.MAX_CONCURRENT))
    } catch {
      case e: IllegalArgumentException => throw ActionConcurrencyLimitException(e.getMessage)
    }
  }
}

protected[core] object ConcurrencyLimit extends ArgNormalizer[ConcurrencyLimit] {
  //since tests require override to the default config, load the "test" config, with fallbacks to default
  val config = ConfigFactory.load().getConfig("test")
  private val concurrencyConfig =
    loadConfigWithFallbackOrThrow[ConcurrencyLimitConfig](config, ConfigKeys.concurrencyLimit)
  private val namespaceConcurrencyDefaultConfig =
    loadConfigWithFallbackOrThrow[NamespaceConcurrencyLimitConfig](config, ConfigKeys.namespaceConcurrencyLimit)

  /**
   * These system limits and namespace default limits are set once at the beginning.
   * Dynamic configuration updates are not supported at the moment.
   */
  protected[core] val MIN_CONCURRENT: Int = concurrencyConfig.min
  protected[core] val MAX_CONCURRENT: Int = concurrencyConfig.max
  protected[core] val STD_CONCURRENT: Int = concurrencyConfig.std

  /** Default namespace limit used if there is no namespace-specific limit */
  protected[core] val MIN_CONCURRENT_DEFAULT: Int = namespaceConcurrencyDefaultConfig.min
  protected[core] val MAX_CONCURRENT_DEFAULT: Int = namespaceConcurrencyDefaultConfig.max

  require(
    MAX_CONCURRENT >= MAX_CONCURRENT_DEFAULT,
    "The system max limit must be greater than the namespace max limit.")
  require(MIN_CONCURRENT <= MIN_CONCURRENT_DEFAULT, "The system min limit must be less than the namespace min limit.")

  /** A singleton ConcurrencyLimit with default value */
  protected[core] val standardConcurrencyLimit = ConcurrencyLimit(STD_CONCURRENT)

  /** Gets ConcurrencyLimit with default value */
  protected[core] def apply(): ConcurrencyLimit = standardConcurrencyLimit

  /**
   * Creates ConcurrencyLimit for limit, iff limit is within permissible range.
   *
   * @param concurrency the limit, must be within permissible range
   * @return ConcurrencyLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(concurrency: Int): ConcurrencyLimit = {
    new ConcurrencyLimit(concurrency)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[ConcurrencyLimit] {
    def write(m: ConcurrencyLimit) = JsNumber(m.maxConcurrent)

    def read(value: JsValue) = {
      Try {
        val JsNumber(c) = value
        require(c.isWhole, "concurrency limit must be whole number")

        ConcurrencyLimit(c.toInt)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("concurrency limit malformed", e)
      }
    }
  }
}
