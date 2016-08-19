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

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import spray.json.DefaultJsonProtocol._
import spray.json.pimpString
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.Logging
import whisk.common.Scheduler
import whisk.core.entity.Subject

/**
 * Determines user limits and activation counts as seen by the invoker and the loadbalancer
 * in a scheduled, repeating task for other services to get the cached information to be able
 * to calculate and determine whether the namespace currently invoking a new action should
 * be allowed to do so
 *
 * @param config containing the config information needed (consulServer)
 */
class ActivationThrottler(consulServer: String, concurrencyLimit: Int, overloadLimit: Int)(
    implicit val system: ActorSystem) extends Logging {

    implicit private val executionContext = system.dispatcher

    /**
     * holds the values of the last run of the scheduler below to be gettable by outside
     * services to be able to determine whether a namespace should be throttled or not based on
     * the number of concurrent invocations it has in the system
     */
    private var userActivationCounter = Map.empty[String, Long]

    private val healthCheckInterval = 2.seconds
    private val consul = new ConsulClient(consulServer)

    /**
     * Checks whether the operation should be allowed to proceed.
     */
    def check(subject: Subject): Boolean = userActivationCounter.getOrElse(subject(), 0L) < concurrencyLimit

    /**
     * Checks whether the system is overloaded
     */
    def isOverloaded: Boolean = userActivationCounter.values.sum > overloadLimit

    /**
     * Returns the per-namespace activation count as seen by the loadbalancer
     *
     * @returns a map where each namespace maps to the activations for it counted
     *     by the loadbalancer
     */
    private def getLoadBalancerActivationCount(): Future[Map[String, Long]] =
        consul.kv.getRecurse(LoadBalancerKeys.userActivationCountKey) map {
            _ map {
                case (_, users) => users.parseJson.convertTo[Map[String, Long]]
            } reduce { _ ++ _ } // keys are unique in every sub map, no adding necessary
        }

    /**
     * Returns the per-namespace activation count as seen by all invokers combined
     *
     * @returns a map where each namespace maps to the activations for it counted
     *     by all the invokers combined
     */
    private def getInvokerActivationCount(): Future[Map[String, Long]] =
        consul.kv.getRecurse(InvokerKeys.allInvokersData) map { rawMaps =>
            val activationCountPerInvoker = rawMaps map {
                case (_, users) => users.parseJson.convertTo[Map[String, Long]]
            }

            // merge all maps and sum values with identical keys
            activationCountPerInvoker.foldLeft(Map[String, Long]()) { (a, b) =>
                (a.keySet ++ b.keySet).map { k =>
                    (k, a.getOrElse(k, 0L) + b.getOrElse(k, 0L))
                }.toMap
            }
        }

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        for {
            loadbalancerActivationCount <- getLoadBalancerActivationCount
            invokerActivationCount <- getInvokerActivationCount
        } yield {
            userActivationCounter = invokerActivationCount map {
                case (subject, invokerCount) =>
                    val loadbalancerCount = loadbalancerActivationCount(subject)
                    subject -> (loadbalancerCount - invokerCount)
            }
        }
    }
}
