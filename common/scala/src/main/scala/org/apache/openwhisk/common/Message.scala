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

package org.apache.openwhisk.common

import org.apache.openwhisk.core.entity.InvokerInstanceId

case object GracefulShutdown
case object Enable

// States an Invoker can be in
sealed trait InvokerState {
  val asString: String
  val isUsable: Boolean
}

object InvokerState {
  // Invokers in this state can be used to schedule workload to
  sealed trait Usable extends InvokerState { val isUsable = true }
  // No workload should be scheduled to invokers in this state
  sealed trait Unusable extends InvokerState { val isUsable = false }

  // A completely healthy invoker, pings arriving fine, no system errors
  case object Healthy extends Usable { val asString = "up" }
  // The invoker can not create a container
  case object Unhealthy extends Unusable { val asString = "unhealthy" }
  // Pings are arriving fine, the invoker does not respond with active-acks in the expected time though
  case object Unresponsive extends Unusable { val asString = "unresponsive" }
  // The invoker is down
  case object Offline extends Unusable { val asString = "down" }
}

/**
 * Describes an abstract invoker. An invoker is a local container pool manager that
 * is in charge of the container life cycle management.
 *
 * @param id a unique instance identifier for the invoker
 * @param status it status (healthy, unhealthy, unresponsive, offline)
 */
case class InvokerHealth(id: InvokerInstanceId, status: InvokerState) {
  override def equals(obj: scala.Any): Boolean = obj match {
    case that: InvokerHealth => that.id == this.id && that.status == this.status
    case _                   => false
  }

  override def toString = s"InvokerHealth($id, $status)"
}
