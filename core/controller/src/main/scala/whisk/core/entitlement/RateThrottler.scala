/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import whisk.core.entity.Subject

/**
 * A class tracking the rate of invocation (or any operation) by subject (any key really).
 *
 * For now, we throttle only at a 1-minute granularity.
 */
class RateThrottler(description: String, maxPerMinute: Int)(implicit logging: Logging) {

    logging.info(this, s"$description: maxPerMinute = $maxPerMinute")(TransactionId.controller)

    /**
     * Maintains map of subject to operations rates.
     */
    private val rateMap = new TrieMap[Subject, RateInfo]

    /**
     * Checks whether the operation should be allowed to proceed.
     * Every `check` operation charges the subject for one operation.
     *
     * @param subject the subject to check
     * @return true iff subject is below allowed limit
     */
    def check(subject: Subject)(implicit transid: TransactionId): Boolean = {
        val rate = rateMap.getOrElseUpdate(subject, new RateInfo(maxPerMinute))
        val belowLimit = rate.check()
        logging.info(this, s"subject = ${subject.toString}, rate = ${rate.count()}, below limit = $belowLimit")
        belowLimit
    }
}

/**
 * Tracks the activation rate of one subject at minute-granularity.
 */
private class RateInfo(maxPerMinute: Int) {
    var lastMin = getCurrentMinute
    var lastMinCount = 0

    def count() = lastMinCount

    /**
     * Increments operation count in the current time window by
     * one and checks if still below allowed max rate.
     */
    def check(): Boolean = {
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
