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

import scala.util.Try
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
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
 * {
 *   timeout: maximum duration in msecs an action is allowed to consume in [100 msecs, 5 minutes],
 *   memory: maximum memory in megabytes an action is allowed to consume within namespace limit, default [128 MB, 512 MB],
 *   logs: maximum logs line in megabytes an action is allowed to generate [10 MB],
 *   concurrency: maximum number of concurrently processed activations per container [1, 200]
 * }
 *
 * @param timeout the duration in milliseconds, assured to be non-null because it is a value
 * @param memory the memory limit in megabytes, assured to be non-null because it is a value
 * @param logs the limit for logs written by the container and stored in the activation record, assured to be non-null because it is a value
 * @param concurrency the limit on concurrently processed activations per container, assured to be non-null because it is a value
 * @param instances the limit in which an action can scale up to within the confines of the namespace's concurrency limit
 */
protected[core] case class ActionLimits(timeout: TimeLimit = TimeLimit(),
                                        memory: MemoryLimit = MemoryLimit(),
                                        logs: LogLimit = LogLimit(),
                                        concurrency: IntraConcurrencyLimit = IntraConcurrencyLimit(),
                                        instances: Option[InstanceConcurrencyLimit] = None)
    extends Limits {
  override protected[entity] def toJson = ActionLimits.serdes.write(this)

  @throws[ActionLimitsException]
  def checkLimits(user: Identity): Unit = {
    timeout.checkNamespaceLimit(user)
    memory.checkNamespaceLimit(user)
    concurrency.checkNamespaceLimit(user)
    logs.checkNamespaceLimit(user)
  }
}

/**
 * Limits on a specific trigger. None yet.
 */
protected[core] case class TriggerLimits protected[core] () extends Limits {
  override protected[entity] def toJson: JsValue = TriggerLimits.serdes.write(this)
}

protected[core] object ActionLimits extends ArgNormalizer[ActionLimits] with DefaultJsonProtocol {

  override protected[core] implicit val serdes = new RootJsonFormat[ActionLimits] {
    val helper = jsonFormat5(ActionLimits.apply)

    def read(value: JsValue) = {
      val obj = Try {
        value.asJsObject.convertTo[Map[String, JsValue]]
      } getOrElse deserializationError("no valid json object passed")

      val time = TimeLimit.serdes.read(obj.getOrElse("timeout", deserializationError("'timeout' is missing")))
      val memory = MemoryLimit.serdes.read(obj.getOrElse("memory", deserializationError("'memory' is missing")))
      val logs = obj.get("logs") map { LogLimit.serdes.read } getOrElse LogLimit()
      val concurrency = obj.get("concurrency") map { IntraConcurrencyLimit.serdes.read } getOrElse IntraConcurrencyLimit()
      val instances = obj.get("instances") map { InstanceConcurrencyLimit.serdes.read }
      ActionLimits(time, memory, logs, concurrency, instances)
    }

    def write(a: ActionLimits) = helper.write(a)
  }
}

protected[core] object TriggerLimits extends ArgNormalizer[TriggerLimits] with DefaultJsonProtocol {

  override protected[core] implicit val serdes = jsonFormat0(TriggerLimits.apply _)
}
