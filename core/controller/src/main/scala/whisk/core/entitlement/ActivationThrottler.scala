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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import whisk.common.Logging
import whisk.common.Scheduler
import whisk.common.TransactionId
import whisk.core.entity.Identity
import whisk.core.entity.UUID
import whisk.core.loadBalancer.LoadBalancer

/**
 * Determines user limits and activation counts as seen by the invoker and the loadbalancer
 * in a scheduled, repeating task for other services to get the cached information to be able
 * to calculate and determine whether the namespace currently invoking a new action should
 * be allowed to do so.
 *
 * @param config containing the config information needed (consulServer)
 */
class ActivationThrottler(consulServer: String, loadBalancer: LoadBalancer, defaultConcurrencyLimit: Int, systemOverloadLimit: Int)(
    implicit val system: ActorSystem, logging: Logging) {

    logging.info(this, s"concurrencyLimit = $defaultConcurrencyLimit, systemOverloadLimit = $systemOverloadLimit")

    implicit private val executionContext = system.dispatcher

    /**
     * holds the values of the last run of the scheduler below to be gettable by outside
     * services to be able to determine whether a namespace should be throttled or not based on
     * the number of concurrent invocations it has in the system
     */
    @volatile
    private var namespaceActivationCounter = Map.empty[UUID, Int]

    private val healthCheckInterval = 5.seconds

    /**
     * Checks whether the operation should be allowed to proceed.
     */
    def check(user: Identity)(implicit tid: TransactionId): Boolean = {
        val concurrentActivations = namespaceActivationCounter.getOrElse(user.uuid, 0)
        val concurrencyLimit = user.limits.concurrentInvocations.getOrElse(defaultConcurrencyLimit)
        logging.info(this, s"namespace = ${user.uuid.asString}, concurrent activations = $concurrentActivations, below limit = $concurrencyLimit")
        concurrentActivations < concurrencyLimit
    }

    /**
     * Checks whether the system is in a generally overloaded state.
     */
    def isOverloaded()(implicit tid: TransactionId): Boolean = {
        val concurrentActivations = namespaceActivationCounter.values.sum
        logging.info(this, s"concurrent activations in system = $concurrentActivations, below limit = $systemOverloadLimit")
        concurrentActivations > systemOverloadLimit
    }

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        namespaceActivationCounter = loadBalancer.getActiveNamespaceActivationCounts
        Future.successful(Unit)
    }
}
