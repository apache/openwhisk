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

import scala.BigInt
import scala.collection.concurrent.TrieMap
import scala.math.BigInt.int2bigInt
import scala.util.Try

import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsValue
import whisk.common.ConsulKV
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.Counter
import whisk.common.Logging


class ActivationThrottle(consulServer: String,
                         invokerHealth : InvokerHealth) extends Logging {

    private val DEFAULT_NAMESPACE_CONCURRENCY_LIMITS_KEY = "whiskprops/DEFAULT_NAMESPACE_CONCURRENCY_LIMITS"
    private val DEFAULT_LIMIT: BigInt = BigInt(100)

    def countForNamespace(ns: String) = userActivationCounter.getOrElse(ns, BigInt(0))
    def limitForNamespace(ns: String) = userActivationLimits.getOrElse(ns, DEFAULT_LIMIT)

    private val userActivationCounter: TrieMap[String, BigInt] = new TrieMap[String, BigInt]
    private val userActivationLimits: TrieMap[String, BigInt] = new TrieMap[String, BigInt]

    private def printUserActivationCounter = {
        userActivationCounter.map {
            case (user, count) => info(this, s"controller: activation count of user ${user} is ${count}")
        }
    }

    new Thread() {
        /** query the KV store this often */
        private val healthCheckPeriodMillis = 2000
        /** allow the last invoker update to be this far behind */
        private val maximumAllowedDelayMilli = 5000

        private val kvStore = new ConsulKV(consulServer)

        /** temporary map for holding the values */
        private val tempCounter: TrieMap[String, BigInt] = new TrieMap[String, BigInt]

        private def decrementCounter(values: JsObject) = {
            tempCounter foreach {
                case (user, count) =>
                    values.getFields(user) match {
                        case Seq(JsNumber(x)) =>
                            val newCount = count - BigInt(x.intValue)
                            if (newCount >= 0) {
                                info(this, s"activation count for user ${user} decremented ${count} -> ${newCount}")
                                tempCounter(user) = newCount
                            } else {
                                warn(this, s"activation count for user ${user} is negative ${count} -> ${newCount}")
                                tempCounter(user) = 0
                            }
                        case _ => // do nothing
                    }
            }
        }

        /**
         * Continuously read from the KV store to get user activation counts from invoker and loadbalancer.
         * Reject action invocation if there are more than n number of concurrent invocations active.
         */
        override def run() = {
            while (true) {
                Try {
                    val limitInfo = kvStore.get(DEFAULT_NAMESPACE_CONCURRENCY_LIMITS_KEY).asJsObject.fields
                    val limits = limitInfo.flatMap {
                        case (k: String, v: JsNumber) => Some(k, BigInt(v.value.toInt))
                        case _                        => None
                    }.toMap
                    userActivationLimits.clear()
                    userActivationLimits ++= limits
                    info(this, s"Got user activation limits from consul: ${limits.keys} ${limits.values}")
                } getOrElse {
                    warn(this, "Could not get user activation limits from kvstore")
                }

                Try {
                    val loadBalancerInfo = ActivationThrottle.fetchLoadBalancerUserActivation(kvStore)
                    val limits = loadBalancerInfo.flatMap {
                        case (k: String, v: JsNumber) => Some(k, BigInt(v.value.toInt))
                        case _                        => None
                    }.toMap
                    tempCounter.clear()
                    tempCounter ++= limits
                    info(this, s"""Got user activation count from loadbalancer: ${limits.keys} ${limits.values}""")

                    val indices = invokerHealth.getInvokerIndices()
                    val invokerCounts = indices flatMap {
                        index => ActivationThrottle.getOneInvokerUserActivationCounts(kvStore, index)
                    }
                    invokerCounts.foreach { invCount => decrementCounter(invCount) }

                    info(this, s"""Finally got user activation counts: ${limits.keys} ${limits.values}""")
                    userActivationCounter.clear()
                    userActivationCounter ++= tempCounter
                    printUserActivationCounter
                } getOrElse {
                    warn(this, "Could not get user activation counts from loadbalancer kvstore")
                }

                Thread.sleep(healthCheckPeriodMillis)
            } // while
        }
    }.start()
}

/*
 * Isolate here the concrete representation of load-balancer generated user action count information.
 */
object ActivationThrottle {

    // Obtain from consul the the load-balancer generated user activation count information
    def fetchLoadBalancerUserActivation(kvStore : ConsulKV) : Map[String, JsValue] = {
        kvStore.getRecurse(ConsulKV.LoadBalancerKeys.userActivationCountKey) flatMap {
            case (_, v) => v.asJsObject.fields
        }
    }

    //  Convert the in-core representation of user activation counts into an opaque bundle of consule kv pairs
    //  Note: TrieMap is not a Map but we should find a beter supertype)
    def encodeLoadBalancerUserActivation(userActivationCounter : TrieMap[String, Counter]) : Map[String, JsObject] = {

        val subjects = userActivationCounter.keySet toList
        val groups = subjects.groupBy { user => user.substring(0, 1) }
        groups.keySet map { prefix =>
          val key = LoadBalancerKeys.userActivationCountKey + "/" + prefix
          val users = groups.getOrElse(prefix, Set())
          val items = users map { u => (u, JsNumber(userActivationCounter.get(u) map { c => c.cur } getOrElse 0))}
          key -> JsObject(items toMap)
        } toMap
    }

    // Fetches everything under the user activation count for one invoker.
    // Because we are getting all subkeys, the way it's split up by the invoker doesn't matter.
    def getOneInvokerUserActivationCounts(kvStore : ConsulKV, index : Int) = {
        val invokerInfo = kvStore.getRecurse(ConsulKV.InvokerKeys.userActivationCount(index))
        invokerInfo flatMap {
            case (_, userActivationCount) => Some(userActivationCount.asJsObject)
        }
    }

}
