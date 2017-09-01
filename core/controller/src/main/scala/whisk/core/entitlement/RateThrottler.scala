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

package whisk.core.entitlement

import scala.collection.concurrent.TrieMap

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.core.entity.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A class tracking the rate of invocation (or any operation) by subject (any key really).
 *
 * For now, we throttle only at a 1-minute granularity.
 */
class RateThrottler(description: String, defaultMaxPerMinute: Int, overrideMaxPerMinute: Identity => Option[Int])(
  implicit logging: Logging) {

  logging.info(this, s"$description: defaultMaxPerMinute = $defaultMaxPerMinute")(TransactionId.controller)

  /**
   * Maintains map of subject namespace to operations rates.
   */
  private val rateMap = new TrieMap[UUID, RateInfo]

  /**
   * Checks whether the operation should be allowed to proceed.
   * Every `check` operation charges the subject namespace for one operation.
   *
   * @param user the identity to check
   * @return true iff subject namespace is below allowed limit
   */
  def check(user: Identity)(implicit transid: TransactionId): RateLimit = {
    val uuid = user.uuid // this is namespace identifier
    val throttle = rateMap.getOrElseUpdate(uuid, new RateInfo)
    val limit = overrideMaxPerMinute(user).getOrElse(defaultMaxPerMinute)
    val rate = TimedRateLimit(throttle.update(limit), limit)
    logging.debug(this, s"namespace = ${uuid.asString} rate = ${rate.count}, limit = $limit")
    rate
  }
}

/**
 * Tracks the activation rate of one subject at minute-granularity.
 */
private class RateInfo {
  @volatile var lastMin = getCurrentMinute
  val lastMinCount = new AtomicInteger()

  /**
   * Increments operation count in the current time window by
   * one and checks if below allowed max rate.
   *
   * @param maxPerMinute the current maximum allowed requests
   *                     per minute (might change over time)
   * @return current count
   */
  def update(maxPerMinute: Int): Int = {
    roll()
    lastMinCount.incrementAndGet()
  }

  def roll() = {
    val curMin = getCurrentMinute
    if (curMin != lastMin) {
      lastMin = curMin
      lastMinCount.set(0)
    }
  }

  private def getCurrentMinute = System.currentTimeMillis / (60 * 1000)
}
