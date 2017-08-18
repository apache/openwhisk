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

import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.core.loadBalancer.LoadBalancer

/**
 * Determines user limits and activation counts as seen by the invoker and the loadbalancer
 * in a scheduled, repeating task for other services to get the cached information to be able
 * to calculate and determine whether the namespace currently invoking a new action should
 * be allowed to do so.
 *
 * @param loadbalancer contains active quotas
 * @param defaultConcurrencyLimit the default max allowed concurrent operations
 * @param systemOverloadLimit the limit when the system is considered overloaded
 */
class ActivationThrottler(loadBalancer: LoadBalancer, defaultConcurrencyLimit: Int, systemOverloadLimit: Int)(
  implicit logging: Logging) {

  logging.info(this, s"concurrencyLimit = $defaultConcurrencyLimit, systemOverloadLimit = $systemOverloadLimit")(
    TransactionId.controller)

  /**
   * Checks whether the operation should be allowed to proceed.
   */
  def check(user: Identity)(implicit tid: TransactionId): Boolean = {
    val concurrentActivations = loadBalancer.activeActivationsFor(user.uuid)
    val concurrencyLimit = user.limits.concurrentInvocations.getOrElse(defaultConcurrencyLimit)
    logging.info(
      this,
      s"namespace = ${user.uuid.asString}, concurrent activations = $concurrentActivations, below limit = $concurrencyLimit")
    concurrentActivations < concurrencyLimit
  }

  /**
   * Checks whether the system is in a generally overloaded state.
   */
  def isOverloaded()(implicit tid: TransactionId): Boolean = {
    val concurrentActivations = loadBalancer.totalActiveActivations
    logging.info(this, s"concurrent activations in system = $concurrentActivations, below limit = $systemOverloadLimit")
    concurrentActivations > systemOverloadLimit
  }
}
