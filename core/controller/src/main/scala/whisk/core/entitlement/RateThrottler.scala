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
  def check(user: Identity)(implicit transid: TransactionId): Boolean = {
    val uuid = user.uuid // this is namespace identifier
    val rate = rateMap.getOrElseUpdate(uuid, new RateInfo)
    val limit = overrideMaxPerMinute(user).getOrElse(defaultMaxPerMinute)
    val belowLimit = rate.check(limit)
    logging.debug(
      this,
      s"namespace = ${uuid.asString} rate = ${rate.count()}, limit = $limit, below limit = $belowLimit")
    belowLimit
  }
}

/**
 * Tracks the activation rate of one subject at minute-granularity.
 */
private class RateInfo {
  var lastMin = getCurrentMinute
  var lastMinCount = 0

  def count() = lastMinCount

  /**
   * Increments operation count in the current time window by
   * one and checks if below allowed max rate.
   *
   * @param maxPerMinute the current maximum allowed requests
   *                     per minute (might change over time)
   */
  def check(maxPerMinute: Int): Boolean = {
    roll()
    lastMinCount = lastMinCount + 1
    lastMinCount <= maxPerMinute
  }

  def roll() = {
    val curMin = getCurrentMinute
    if (curMin != lastMin) {
      lastMin = curMin
      lastMinCount = 0
    }
  }

  private def getCurrentMinute = System.currentTimeMillis / (60 * 1000)
}
