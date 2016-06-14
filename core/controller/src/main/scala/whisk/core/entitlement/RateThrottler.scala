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

import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.entity.Subject
import whisk.common.TransactionId
import whisk.common.Logging

/**
 * A class tracking the rate of invocation (or any operation) by subject (any key really).
 *
 * For now, we throttle only at a 1-minute granularity.
 */
class RateThrottler(config: WhiskConfig,
                    maxPerMinute: Int,
                    maxPerHour: Int) extends Logging {

    // Parameters
    private val exemptSubject = Set[Subject]() // We exempt no one.

    // Implementation
    private val rateMap = new TrieMap[Subject, RateInfo]

    // Track the activation rate of one subject at multiple time-granularity.
    class RateInfo extends Logging {
        setVerbosity(Verbosity.Loud)
        var lastMin = getCurrentMinute
        var lastMinCount = 0
        var lastHour = getCurrentHour
        var lastHourCount = 0

        def check()(implicit transid: TransactionId): Boolean = {
            roll()
            lastMinCount = lastMinCount + 1
            lastHourCount = lastHourCount + 1
            lastMinCount <= maxPerMinute && lastHourCount <= maxPerHour
        }

        def roll()(implicit transid: TransactionId) = {
            val curMin = getCurrentMinute
            val curHour = getCurrentHour
            if (curMin != lastMin) {
                lastMin = curMin
                lastMinCount = 0
            }
            if (curHour != lastHour) {
                lastHour = curHour
                lastHourCount = 0
            }
        }

        private def getCurrentMinute = System.currentTimeMillis / (60 * 1000)
        private def getCurrentHour = System.currentTimeMillis / (3600 * 1000)
    }

    /**
     * Checks whether the operation should be allowed to proceed.
     * Delegate to subject-based RateInfo to perform the check after checking for exemption(s).
     */
    def check(subject: Subject)(implicit transid: TransactionId): Boolean = {
        if (exemptSubject.contains(subject)) {
            true
        } else {
            info(this, s"RateThrottler.check: subject = ${subject.toString}")
            rateMap.getOrElseUpdate(subject, new RateInfo()).check()
        }
    }
}


