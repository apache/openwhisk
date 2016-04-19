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

package whisk.core.loadBalancer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.collection.concurrent.TrieMap

import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.pimpAny
import whisk.common.ConsulKV
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.DateUtil
import whisk.common.Logging
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.connector.{ ActivationMessage => Message }
import scala.language.postfixOps

object InvokerHealth {
    val requiredProperties = consulServer
}

/**
 * Monitors the health of the invokers. The number of invokers is dynamic, and preferably a power of 2.
 *
 * We are starting to put real load-balancer logic in here too.  Should probably be moved out at some point.
 */
class InvokerHealth(config: WhiskConfig, getKafkaPostCount: () => Int) extends Logging {

    setComponentName("LoadBalancer");
    setVerbosity(Verbosity.Loud);

    private val activationCountBeforeNextInvoker = 10

    def getInvoker(msg : Message): Option[Int] = {
        val (hash, count) = hashAndCountSubjectAction(msg)
        val numInv = numInvokers // dynamic
        if (numInv > 0) {
            val globalCount = msgCount.getAndIncrement()
            val hashCount = math.abs(hash + count / activationCountBeforeNextInvoker)
            val choice = pickInvoker(hashCount % numInv)
            getNext(choice, numInv)
        } else None
    }

    /*
     * The path contains more than the action per se but seems sufficiently
     * isomorphic as the other parts are constant.  Extracting just the
     * action out specifically will involve some hairy regex's that the
     * Invoker is currently using and which is better avoid if/until
     * these are moved to some common place (like a subclass of Message?)
     */
    def hashAndCountSubjectAction(msg : Message): (Int, Int) = {
         val subject = msg.subject().toString()
         val path = msg.path
         val hash = subject.hashCode() ^ path.hashCode()
         val key = (subject, path)
         val count = activationCountMap.get(key) match {
           case Some(counter) => counter.getAndIncrement()
           case None => {
             activationCountMap.put(key, new AtomicInteger(0))
             0
           }
         }
         //info(this, s"getInvoker: ${subject} ${path} -> ${hash} ${count}")
         return (hash, count)
    }

    def getInvokerHealth(): JsObject = {
        val health = invokers.get().map { s => s"invoker${s.index}" -> (if (s.status) "up" else "down").toJson }
        JsObject(health toMap)
    }

    def getInvokerIndices() : Array[Int] = {
        invokers.get() map { status => status.index }
    }

    def getInvokerActivationCounts() : Array[Int] = {
        invokers.get() map { status => status.activationCount }
    }

    new Thread() {
        /** query the KV store this often */
        private val healthCheckPeriodMillis = 2000
        /** allow the last invoker update to be this far behind */
        private val maximumAllowedDelayMilli = 5000

        def isFresh(lastDate: String) = {
            val lastDateMilli = DateUtil.parseToMilli(lastDate)
            val now = System.currentTimeMillis() // We fetch this repeatedly in case KV fetch is slow
            (now - lastDateMilli) <= maximumAllowedDelayMilli
        }

        private val kvStore = new ConsulKV(config.consulServer)
        private val reporter = new ConsulKVReporter(kvStore, 3000, 2000,
            LoadBalancerKeys.hostnameKey,
            LoadBalancerKeys.startKey,
            LoadBalancerKeys.statusKey,
            { () =>
                Map(LoadBalancerKeys.invokerHealth -> getInvokerHealth(),
                    LoadBalancerKeys.activationCountKey -> getKafkaPostCount().toJson)
            })

        /** Continously read from the KV store to get invoker status.*/
        override def run() = {
            while (true) {
                val info = kvStore.getRecurse(ConsulKV.InvokerKeys.allInvokers)
                val freshMap = info.flatMap {
                    case (k, JsString(lastDate)) =>
                        ConsulKV.InvokerKeys.getStatusIndex(k) map {
                            index =>
                              val activationCount = info.get(ConsulKV.InvokerKeys.activationCount(index)) match {
                                case Some(JsNumber(v)) => v.toInt
                                case _ => 0
                              }
                              Some((k, Status(index, isFresh(lastDate), activationCount)))
                        } getOrElse None
                    case _ => None
                }
                val newStatus = freshMap.values.toArray
                val oldStatus = invokers.get()
                invokers.set(newStatus)
                if (newStatus.deep != oldStatus.deep) {
                    warn(this, s"InvokerHealth status change: ${newStatus.deep.mkString(" ")}")
                }
                Thread.sleep(healthCheckPeriodMillis)
            } // while
        }
    }.start()

    /**
     * Finds an available invoker starting at current index and trying for remaining indexes.
     */
    private def getNext(current: Int, remain: Int): Option[Int] = {
        if (remain > 0) {
            val choice = invokers.get()(current)
            choice.status match {
                case true  => Some(choice.index)
                case false => getNext(nextInvoker(current), remain - 1)
            }
        } else {
            error(this, s"all invokers down")
            None
        }
    }

    private def pickInvoker(msgCount: Int): Int = {
        val numInv = numInvokers
        if (numInv > 0) msgCount % numInv else 0
    }

    private def nextInvoker(i: Int): Int = {
        val numInv = numInvokers
        if (numInv > 0) (i + 1) % numInv else 0
    }

    private val msgCount = new AtomicInteger(0)

    // Because we are not using 0-based indexing yet...
    private case class Status(index: Int, status: Boolean, activationCount : Int) {
        override def toString = s"index: $index, healthy: $status, activations: $activationCount"
    }
    private lazy val invokers = new AtomicReference(Array(): Array[Status])
    private def numInvokers = invokers.get().length
    private val activationCountMap = TrieMap[(String, String), AtomicInteger]()
}
