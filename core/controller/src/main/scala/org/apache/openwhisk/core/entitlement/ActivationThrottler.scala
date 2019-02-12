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

package org.apache.openwhisk.core.entitlement

import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.Identity
import org.apache.openwhisk.core.loadBalancer.LoadBalancer
import org.apache.openwhisk.http.Messages

import scala.concurrent.{ExecutionContext, Future}

/**
 * Determine whether the namespace currently invoking a new action should be allowed to do so.
 *
 * @param loadBalancer contains active quotas
 * @param concurrencyLimit a calculated limit relative to the user using the system
 */
class ActivationThrottler(loadBalancer: LoadBalancer, concurrencyLimit: Identity => Int)(
  implicit logging: Logging,
  executionContext: ExecutionContext) {

  /**
   * Checks whether the operation should be allowed to proceed.
   */
  def check(user: Identity)(implicit tid: TransactionId): Future[RateLimit] = {
    loadBalancer.activeActivationsFor(user.namespace.uuid).map { concurrentActivations =>
      val currentLimit = concurrencyLimit(user)
      logging.debug(
        this,
        s"namespace = ${user.namespace.uuid.asString}, concurrent activations = $concurrentActivations, below limit = $currentLimit")
      ConcurrentRateLimit(concurrentActivations, currentLimit)
    }
  }
}

sealed trait RateLimit {
  def ok: Boolean
  def errorMsg: String
  def limitName: String
}

case class ConcurrentRateLimit(count: Int, allowed: Int) extends RateLimit {
  val ok: Boolean = count < allowed // must have slack for the current activation request
  override def errorMsg: String = Messages.tooManyConcurrentRequests(count, allowed)
  val limitName: String = "ConcurrentRateLimit"
}

case class TimedRateLimit(count: Int, allowed: Int) extends RateLimit {
  val ok: Boolean = count <= allowed // the count is already updated to account for the current request
  override def errorMsg: String = Messages.tooManyRequests(count, allowed)
  val limitName: String = "TimedRateLimit"
}
