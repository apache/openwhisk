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

package whisk.core.entity

import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.MILLISECONDS
import spray.json.deserializationError
import spray.json.JsValue
import spray.json.JsNumber
import spray.json.RootJsonFormat
import scala.util.Try
import scala.language.postfixOps

/**
 * TimeLimit encapsulates a duration for an action. The duration must be within a
 * permissible range (currently [100 msecs, 1 minute]).
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param duration the duration for the action, required not null
 */
protected[entity] class TimeLimit private (val duration: FiniteDuration) extends AnyVal {
    protected[core] def millis = duration.toMillis.toInt
    protected[core] def apply() = duration
    override def toString = duration.toString
}

protected[core] object TimeLimit extends ArgNormalizer[TimeLimit] {
    protected[entity] val MIN_DURATION = 100 milliseconds
    protected[entity] val MAX_DURATION = 5 minutes
    protected[entity] val STD_DURATION = 1 minute

    /** Gets TimeLimit with default duration */
    protected[core] def apply(): TimeLimit = new TimeLimit(STD_DURATION)

    /**
     * Creates TimeLimit for duration, iff duration is within permissible range.
     *
     * @param duration the duration in milliseconds, must be within permissible range
     * @return TimeLimit with duration set
     * @throws IllegalArgumentException if duration does not conform to requirements
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(duration: Int): TimeLimit = {
        require(duration >= MIN_DURATION.toMillis.toInt, s"duration $duration below allowed threshold")
        require(duration <= MAX_DURATION.toMillis.toInt, s"duration $duration exceeds allowed threshold")
        new TimeLimit(Duration(duration, MILLISECONDS))
    }

    /**
     * Creates TimeLimit for duration, iff duration is within permissible range.
     *
     * @param duration the duration in milliseconds, must be within permissible range
     * @return TimeLimit with duration set
     * @throws IllegalArgumentException if duration does not conform to requirements
     */
    @throws[IllegalArgumentException]
    protected[entity] def apply(duration: FiniteDuration): TimeLimit = {
        require(duration != null, s"duration undefined")
        require(duration >= MIN_DURATION, s"duration $duration below allowed threshold")
        require(duration <= MAX_DURATION, s"duration $duration exceeds allowed threshold")
        new TimeLimit(duration)
    }

    override protected[core] implicit val serdes = new RootJsonFormat[TimeLimit] {
        def write(t: TimeLimit) = JsNumber(t.millis)

        def read(value: JsValue) = Try {
            val JsNumber(ms) = value
            require(ms.isWhole, "time limit must be whole number")
            TimeLimit(ms.intValue)
        } getOrElse deserializationError("time limit malformed")
    }
}
