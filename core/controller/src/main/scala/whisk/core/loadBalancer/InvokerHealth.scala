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
import whisk.common.Scheduler

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
    instanceChange: Array[Int] => Unit,
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

    def getInvokerIndices(): Array[Int] = {
        curStatus.get() map { _.index }
    }

    def getInvokerActivationCounts(): Array[(Int, Int)] = {
        curStatus.get() map { status => (status.index, status.activationCount) }
    }

    private def getHealth(statuses: Array[Status]): Array[(Int, Boolean)] = {
        statuses map { status => (status.index, status.status) }
    }

    def getInvokerHealth(): Array[(Int, Boolean)] = getHealth(curStatus.get())

    def getInvokerHealthJson(): JsObject = {
        val health = getInvokerHealth().map { case (index, isUp) => s"invoker${index}" -> (if (isUp) "up" else "down").toJson }
        JsObject(health toMap)
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
            Map(LoadBalancerKeys.invokerHealth -> getInvokerHealthJson(),
                LoadBalancerKeys.activationCountKey -> getKafkaPostCount().toJson)
        })

    /** query the KV store this often */
    private val healthCheckInterval = 2 seconds

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        kv.getRecurse(InvokerKeys.allInvokers) map { invokerInfo =>
            // keys are like invokers/invokerN/count
            val flattened = ConsulClient.dropKeyLevel(invokerInfo)
            val nested = ConsulClient.toNestedMap(flattened)

            // Get the new status (some entries may correspond to new instances)
            val statusMap = nested map {
                case (key, inner) =>
                    val index = InvokerKeys.extractInvokerIndex(key)
                    val JsString(startDate) = inner(InvokerKeys.startKey).parseJson
                    val JsString(lastDate) = inner(InvokerKeys.statusKey).parseJson
                    (index, Status(index, startDate, lastDate, isFresh(lastDate), inner(InvokerKeys.activationCountKey).toInt))
            }
            val newStatus = statusMap.values.toArray.sortBy(_.index)

            // Warning is issued only if up/down is changed
            if (getInvokerHealth().deep != getHealth(newStatus).deep) {
                warn(this, s"InvokerHealth status change: ${newStatus.deep.mkString(" ")}")
            }

            // Existing entries that have become stale require recording and a warning
            val stale = curStatus.get().filter {
                case Status(index, startDate, _, _, _) =>
                    statusMap.get(index).map(startDate != _.startDate) getOrElse false
            }
            if (!stale.isEmpty) {
                oldStatus.set(oldStatus.get() ++ stale)
                warn(this, s"Stale invoker status has changed: ${oldStatus.get().deep.mkString(" ")}")
                instanceChange(stale.map(_.index))
            }

            curStatus.set(newStatus)
        }
    }

    /**
     * Finds an available invoker starting at current index and trying for remaining indexes.
     */
    private def getNext(current: Int, remain: Int): Option[Int] = {
        if (remain > 0) {
            val choice = curStatus.get()(current)
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

    private def numInvokers = curStatus.get().length

    /*
     * Invoker indices are 0-based.
     * curStatus maintains the status of the curent instance at a particular index while oldStatus
     * tracks instances (potentially many per index) that are not longer fresh (invoker was restarted).
     */
    private case class Status(index: Int, startDate: String, lastDate: String, status: Boolean, activationCount: Int) {
        override def toString = s"index: $index, healthy: $status, activations: $activationCount, start: $startDate, last: $lastDate"
    }
    private lazy val curStatus = new AtomicReference(Array(): Array[Status])
    private lazy val oldStatus = new AtomicReference(Array(): Array[Status])

    private val msgCount = new AtomicInteger(0)
    private val activationCountMap = TrieMap[(String, String), AtomicInteger]()
}
