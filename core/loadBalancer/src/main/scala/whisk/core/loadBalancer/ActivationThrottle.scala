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

package whisk.core.loadBalancer;

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.ActorSystem
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.ConsulClient
import whisk.common.ConsulKV
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.Scheduler
import whisk.core.WhiskConfig

/**
 * Determines user limits and activation counts as seen by the invoker and the loadbalancer
 * in a scheduled, repeating task for other services to get the cached information to be able
 * to calculate and determine whether the namespace currently invoking a new action should
 * be allowed to do so
 *
 * @param config containing the config information needed (consulServer)
 */
class ActivationThrottle(config: WhiskConfig)(
    implicit val system: ActorSystem,
    val ec: ExecutionContext) extends Logging {

    val DEFAULT_NAMESPACE_CONCURRENCY_LIMITS_KEY = "whiskprops/DEFAULT_NAMESPACE_CONCURRENCY_LIMITS"
    val DEFAULT_LIMIT = 100L

    /**
     * holds the values of the last run of the scheduler below to be gettable by outside
     * services to be able to determine whether a namespace should be throttled or not based on
     * the number of concurrent invocations it has in the system
     */
    private var userActivationCounter = Map[String, Long]()
    private var userActivationLimits = Map[String, Long]()

    private val healthCheckInterval = 2 seconds
    private val kvStore = new ConsulClient(config.consulServer)

    /**
     * Returns the activation count for a specific namespace
     *
     * @param ns the namespace to get the activation count for
     * @returns activation count for the given namespace, defaults to 0
     */
    def countForNamespace(ns: String) = userActivationCounter.getOrElse(ns, 0L)

    /**
     * Returns the limit for a specific namespace
     *
     * @param ns the namespace to get the limits for
     * @returns limit of concurrent activations for the given namespace
     */
    def limitForNamespace(ns: String) = userActivationLimits.getOrElse(ns, DEFAULT_LIMIT)

    /**
     * Returns the concurrency limits for all namespaces
     *
     * @returns a map where each namespace maps to the concurrency limit set for it
     */
    private def getConcurrencyLimits(): Future[Map[String, Long]] =
        kvStore.get(DEFAULT_NAMESPACE_CONCURRENCY_LIMITS_KEY) map { limits =>
            limits.parseJson.convertTo[Map[String, Long]]
        }

    /**
     * Returns the per-namespace activation count as seen by the loadbalancer
     *
     * @returns a map where each namespace maps to the activations for it counted
     *     by the loadbalancer
     */
    private def getLoadBalancerActivationCount(): Future[Map[String, Long]] =
        kvStore.getRecurse(ConsulKV.LoadBalancerKeys.userActivationCountKey) map {
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
        kvStore.getRecurse(ConsulKV.InvokerKeys.allInvokersData) map { rawMaps =>
            val activationCountPerInvoker = rawMaps map {
                case (_, users) => users.parseJson.convertTo[Map[String, Long]]
            }

            // merge all maps and sum values with identical keys
            activationCountPerInvoker.foldLeft(Map[String, Long]()) { (a, b) =>
                (a.keySet ++ b.keySet) map { k =>
                    (k, a.getOrElse(k, 0L) + b.getOrElse(k, 0L))
                } toMap
            }
        }

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        for {
            concurrencyLimits <- getConcurrencyLimits
            loadbalancerActivationCount <- getLoadBalancerActivationCount
            invokerActivationCount <- getInvokerActivationCount
        } yield {
            userActivationLimits = concurrencyLimits
            userActivationCounter = invokerActivationCount map {
                case (subject, invokerCount) =>
                    val loadbalancerCount = loadbalancerActivationCount(subject)
                    subject -> (loadbalancerCount - invokerCount)
            }

            userActivationCounter foreach {
                case (user, count) => info(this, s"activation count of user ${user} is ${count}")
            }
        }
    }
}

/**
 * Isolate here the concrete representation of load-balancer generated user action count information.
 */
object ActivationThrottle {
    val requiredProperties = WhiskConfig.consulServer

    /**
     * Convert user activation counters into a map of JsObjects to be written into consul kv
     *
     * @param userActivationCounter the counters for each user's activations
     * @returns a map where the key represents the final nested key structure for consul and a JsObject
     *     containing the activation counts for each user
     */
    def encodeLoadBalancerUserActivation(userActivationCounter: Map[String, Counter]): Map[String, JsObject] = {
        userActivationCounter mapValues {
            _.cur
        } groupBy {
            case (key, _) => LoadBalancerKeys.userActivationCountKey + "/" + key.substring(0, 1)
        } mapValues { map =>
            map.toJson.asJsObject
        }
    }
}
