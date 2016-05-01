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
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.ActorSystem
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsObject
import spray.json.JsString
import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.DateUtil
import whisk.common.Logging
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.connector.{ ActivationMessage => Message }

object InvokerHealth {
    val requiredProperties = consulServer
}

/**
 * Monitors the health of the invokers. The number of invokers is dynamic, and preferably a power of 2.
 *
 * We are starting to put real load-balancer logic in here too.  Should probably be moved out at some point.
 */
class InvokerHealth(
    config: WhiskConfig,
    getKafkaPostCount: () => Int)(
        implicit val system: ActorSystem,
        val ec: ExecutionContext) extends Logging {

    setComponentName("LoadBalancer");
    setVerbosity(Verbosity.Loud);

    private val activationCountBeforeNextInvoker = 10

    def getInvoker(msg: Message): Option[Int] = {
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
    def hashAndCountSubjectAction(msg: Message): (Int, Int) = {
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

    def getInvokerIndices(): Array[Int] = {
        invokers.get() map { _.index }
    }

    def getInvokerActivationCounts(): Array[(Int, Int)] = {
        invokers.get() map { status => (status.index, status.activationCount) }
    }

    private val maximumAllowedDelay = 5 seconds
    def isFresh(lastDate: String) = {
        val lastDateMilli = DateUtil.parseToMilli(lastDate)
        val now = System.currentTimeMillis() // We fetch this repeatedly in case KV fetch is slow
        (now - lastDateMilli) <= maximumAllowedDelay.toMillis
    }

    private val kv = new ConsulClient(config.consulServer)
    private val reporter = new ConsulKVReporter(kv, 3 seconds, 2 seconds,
        LoadBalancerKeys.hostnameKey,
        LoadBalancerKeys.startKey,
        LoadBalancerKeys.statusKey,
        { () =>
            Map(LoadBalancerKeys.invokerHealth -> getInvokerHealth(),
                LoadBalancerKeys.activationCountKey -> getKafkaPostCount().toJson)
        })

    /** query the KV store this often */
    private val healthCheckInterval = 2 seconds

    system.scheduler.schedule(0 seconds, healthCheckInterval) {
        kv.getRecurse(InvokerKeys.allInvokers) foreach { invokerInfo =>
            // keys are like invokers/invokerN/count
            val flattened = ConsulClient.dropKeyLevel(invokerInfo)
            val nested = ConsulClient.toNestedMap(flattened)

            val status = nested map {
                case (key, inner) =>
                    val index = key.substring(7).toInt // key is invokerN
                    val JsString(lastDate) = inner("status").parseJson
                    Status(index, isFresh(lastDate), inner("activationCount").toInt)
            }

            val newStatus = status.toArray.sortBy(_.index)
            val oldStatus = invokers.get()
            invokers.set(newStatus)
            if (newStatus.deep != oldStatus.deep) {
                warn(this, s"InvokerHealth status change: ${newStatus.deep.mkString(" ")}")
            }
        }
    }

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
    private case class Status(index: Int, status: Boolean, activationCount: Int) {
        override def toString = s"index: $index, healthy: $status, activations: $activationCount"
    }
    private lazy val invokers = new AtomicReference(Array(): Array[Status])
    private def numInvokers = invokers.get().length
    private val activationCountMap = TrieMap[(String, String), AtomicInteger]()
}
