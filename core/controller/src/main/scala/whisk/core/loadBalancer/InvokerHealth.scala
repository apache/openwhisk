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

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.{ JsObject, JsString, pimpAny, pimpString }
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.DateUtil
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.common.Scheduler
import whisk.common.TransactionId

object InvokerHealth {
    val requiredProperties = consulServer
}

/**
 * Monitors the health of the invokers. The number of invokers is dynamic.
 *
 * We are starting to put real load-balancer logic in here too.  Should probably be moved out at some point.
 */
class InvokerHealth(
    config: WhiskConfig,
    instanceChange: Array[Int] => Unit,
    getKafkaPostCount: () => Long)(
        implicit val system: ActorSystem) extends Logging {

    /** We obtain the health of all invokers this often. Although we perform a recursive call,
      * the subtree is small and does not contain voluminous information like per-user information.
      */
    private val healthCheckInterval = 2 seconds

    /** If we do not hear from an invoker for this long, we consider it to be out of commission. */
    private val maximumAllowedDelay = 20 seconds

    private implicit val executionContext = system.dispatcher

    setVerbosity(InfoLevel);

    def getInvokerIndices(): Array[Int] = curStatus.get() map { _.index }

    def getCurStatus = curStatus.get().clone()

    private def getHealth(statuses: Array[InvokerStatus]): Map[Int, Boolean] = {
        statuses.map { status => (status.index, status.isUp) }.toMap
    }

    def getInvokerHealth(): Map[Int, Boolean] = getHealth(curStatus.get())

    def getInvokerHealthJson(): JsObject = {
        val health = getInvokerHealth().map { case (index, isUp) => s"invoker${index}" -> (if (isUp) "up" else "down").toJson }
        JsObject(health toMap)
    }

    def isFresh(lastDate: String) = {
        val lastDateMilli = DateUtil.parseToMilli(lastDate)
        val now = System.currentTimeMillis() // We fetch this repeatedly in case KV fetch is slow
        (now - lastDateMilli) <= maximumAllowedDelay.toMillis
    }

    private val consul = new ConsulClient(config.consulServer)

    Scheduler.scheduleWaitAtLeast(healthCheckInterval) { () =>
        consul.kv.getRecurse(InvokerKeys.allInvokers) map { invokerInfo =>
            // keys are like invokers/invokerN/count
            val flattened = ConsulClient.dropKeyLevel(invokerInfo)
            val nested = ConsulClient.toNestedMap(flattened)

            // Get the new status (some entries may correspond to new instances)
            val statusMap = nested map {
                case (key, inner) =>
                    val index = InvokerKeys.extractInvokerIndex(key)
                    val JsString(startDate) = inner(InvokerKeys.startKey).parseJson
                    val JsString(lastDate) = inner(InvokerKeys.statusKey).parseJson
                    (index, InvokerStatus(index, startDate, lastDate, isFresh(lastDate)))
            }
            val newStatus = statusMap.values.toArray.sortBy(_.index)

            // Warning is issued only if up/down is changed
            if (getInvokerHealth() != getHealth(newStatus)) {
                warn(this, s"InvokerHealth status change: ${newStatus.deep.mkString(" ")}")(TransactionId.loadbalancer)
            }

            // Existing entries that have become stale require recording and a warning
            val stale = curStatus.get().filter {
                case InvokerStatus(index, startDate, _, _) =>
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

    private lazy val curStatus = new AtomicReference(Array(): Array[InvokerStatus])
    private lazy val oldStatus = new AtomicReference(Array(): Array[InvokerStatus])

}

/*
 * Invoker indices are 0-based.
 * curStatus maintains the status of the current instance at a particular index while oldStatus
 * tracks instances (potentially many per index) that are not longer fresh (invoker was restarted).
 */
case class InvokerStatus(index: Int, startDate: String, lastDate: String, isUp: Boolean) {
    override def toString = s"index: $index, healthy: $isUp, start: $startDate, last: $lastDate"
}
