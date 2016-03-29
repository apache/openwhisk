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

import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.duration.MINUTES
import scala.util.Try

import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError

/**
 * MemoyLimit encapsulates allowed memory for an action. The limit must be within a
 * permissible range (currently [128MB, 8192MB]).
 *
 * It is a value type (hence == is .equals, immutable and cannot be assigned null).
 * The constructor is private so that argument requirements are checked and normalized
 * before creating a new instance.
 *
 * @param megabytes the memory limit in megabytes for the action
 */
protected[entity] class MemoryLimit private (val megabytes: Int) extends AnyVal {
    protected[core] def apply() = megabytes
}

protected[core] object MemoryLimit extends ArgNormalizer[MemoryLimit] {
    protected[entity] val MIN_MEMORY = 128 // MB
    protected[entity] val MAX_MEMORY = 512 // MB
    protected[core] val STD_MEMORY = 256 // MB

    /** Gets TimeLimit with default duration */
    protected[core] def apply(): MemoryLimit = new MemoryLimit(STD_MEMORY)

    /**
     * Creates MemoryLimit for limit, iff limit is within permissible range.
     *
     * @param megabytes the limit in megabytes, must be within permissible range
     * @return MemoryLimit with limit set
     * @throws IllegalArgumentException if limit does not conform to requirements
     */
    @throws[IllegalArgumentException]
    protected[core] def apply(megabytes: Int): MemoryLimit = {
        require(megabytes >= MIN_MEMORY, s"memory $megabytes below allowed threshold")
        require(megabytes <= MAX_MEMORY, s"memory $megabytes exceeds allowed threshold")
        new MemoryLimit(megabytes);
    }

    override protected[core] implicit val serdes = new RootJsonFormat[MemoryLimit] {
        def write(m: MemoryLimit) = JsNumber(m.megabytes)

        def read(value: JsValue) = Try {
            val JsNumber(mb) = value
            require(mb.isWhole(), "memory limit must be whole number")
            MemoryLimit(mb.intValue)
        } getOrElse deserializationError("memory limit malformed")
    }
}
