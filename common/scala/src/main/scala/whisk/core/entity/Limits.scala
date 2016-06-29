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

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny
import spray.json.serializationError
import spray.json.DefaultJsonProtocol

/**
 * Abstract type for limits on triggers and actions. This may
 * expand to include global limits as well (for example limits
 * that require global knowledge).
 */
protected[entity] abstract class Limits {
    protected[entity] def toJson: JsValue
    override def toString = toJson.compactPrint
}

/**
 * Limits on a specific action. Includes the following properties
 * { timeout: maximum duration in msecs an action is allowed to consume in [100 msecs, 5 minutes]
 *   memory: maximum memory in megabytes an action is allowed to consume in [128, 512]
 * }
 *
 * @param timeout the duration in milliseconds, assured to be non-null because it is a value
 * @param memory the memory limit in megabytes, assured to be non-null because it is a value
 * @param logs the limit for logs written by the container being written to the database.
 *     <code>Option</code> for database schema migration
 */
protected[core] case class ActionLimits protected[core] (timeout: TimeLimit, memory: MemoryLimit, logs: Option[LogLimit] = Some(LogLimit())) extends Limits {
    override protected[entity] def toJson = ActionLimits.serdes.write(this)
}

/**
 * Limits on a specific trigger. None yet.
 */
protected[core] case class TriggerLimits protected[core] () extends Limits {
    override protected[entity] def toJson: JsValue = TriggerLimits.serdes.write(this)
}

protected[core] object ActionLimits
    extends ArgNormalizer[ActionLimits]
    with DefaultJsonProtocol {

    /** Creates a ActionLimits instance with default duration and memory limit. */
    protected[core] def apply(): ActionLimits = ActionLimits(TimeLimit(), MemoryLimit())

    override protected[core] implicit val serdes = new RootJsonFormat[ActionLimits] {
        val helper = jsonFormat3(ActionLimits.apply)

        def read(value: JsValue) = {
            val inter = helper.read(value)
            inter.copy(logs = Some(inter.logs.getOrElse(LogLimit())))
        }
        def write(a: ActionLimits) = helper.write(a.copy(logs = Some(a.logs.getOrElse(LogLimit()))))
    }
}

protected[core] object TriggerLimits
    extends ArgNormalizer[TriggerLimits]
    with DefaultJsonProtocol {

    override protected[core] implicit val serdes = jsonFormat0(TriggerLimits.apply)
}
