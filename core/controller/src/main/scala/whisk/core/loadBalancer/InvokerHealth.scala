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

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.DateUtil
import whisk.common.Logging
import whisk.common.Scheduler
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.entity.Subject

/**
 * Monitors the health of the invokers. The number of invokers is dynamic, and preferably a power of 2.
 *
 * We are starting to put real load-balancer logic in here too.  Should probably be moved out at some point.
 */
class InvokerHealth(consul: ConsulClient)(
    implicit val system: ActorSystem) extends Logging {

    private implicit val executionContext = system.dispatcher

    setVerbosity(InfoLevel);

    private val activationCountBeforeNextInvoker = 10

    def getInvoker(msg: Message): Option[Int] = {
        val (hash, count) = hashAndCountSubjectAction(msg)
        val numInv = invokers.length
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
        val subject = msg.subject
        val path = msg.path
        val hash = subject().hashCode() ^ path.hashCode()
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

    def getInvokerHealthJson(): JsObject = {
        val health = invokers.map { status => s"invoker${status.index}" -> (if (status.status) "up" else "down").toJson }
        JsObject(health.toMap)
    }

    private val maximumAllowedDelay = 20.seconds
    def isFresh(lastDate: String) = {
        val lastDateMilli = DateUtil.parseToMilli(lastDate)
        val now = System.currentTimeMillis() // We fetch this repeatedly in case KV fetch is slow
        (now - lastDateMilli) <= maximumAllowedDelay.toMillis
    }

    /** query the KV store this often */
    private val healthCheckInterval = 2.seconds

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        consul.kv.getRecurse(InvokerKeys.allInvokers) map { invokerInfo =>
            // keys are like invokers/invokerN/count
            val flattened = ConsulClient.dropKeyLevel(invokerInfo)
            val nested = ConsulClient.toNestedMap(flattened)

            // Get the new status (some entries may correspond to new instances)
            val newStatus = nested.map {
                case (key, inner) =>
                    val index = InvokerKeys.extractInvokerIndex(key)
                    val JsString(lastDate) = inner(InvokerKeys.statusKey).parseJson
                    Status(index, isFresh(lastDate))
            }.toList.sortBy(_.index)

            // Warning is issued only if up/down is changed
            if (invokers != newStatus) {
                warn(this, s"InvokerHealth status change: ${newStatus.mkString(" ")}")
            }

            invokers = newStatus
        }
    }

    /**
     * Finds an available invoker starting at current index and trying for remaining indexes.
     */
    private def getNext(current: Int, remain: Int): Option[Int] = {
        if (remain > 0) {
            val choice = invokers(current)
            if (choice.status) Some(choice.index)
            else getNext(pickInvoker(current + 1), remain - 1)
        } else {
            error(this, s"all invokers down")
            None
        }
    }

    /**
     * Picks the invoker at index {@code i}, rotating the list
     * of invokers if {@code i > invokers.length}
     */
    private def pickInvoker(i: Int) = i % invokers.length

    /*
     * Invoker indices are 0-based.
     * curStatus maintains the status of the current instance at a particular index while oldStatus
     * tracks instances (potentially many per index) that are not longer fresh (invoker was restarted).
     */
    private case class Status(index: Int, status: Boolean) {
        override def toString = s"index: $index, healthy: $status"
    }
    private var invokers = List.empty[Status]

    private val msgCount = new AtomicInteger(0)
    private val activationCountMap = TrieMap[(Subject, String), AtomicInteger]()
}
