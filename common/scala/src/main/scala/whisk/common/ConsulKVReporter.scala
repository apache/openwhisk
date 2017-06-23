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

package whisk.common

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.actor.ActorSystem
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsValue
import spray.json.pimpAny

/**
 * Helper utility to periodically report values to consul's key-value store
 *
 * @param kv instance of the ConsulClient to use
 * @param initialDelay time to wait before starting the reporting initially
 * @param interval time between two reports being send
 * @param hostKey the key for the host address of the component
 * @param startKey the key for the startup timestamp of the component
 * @param statusKey the key for the freshness timestamp of the component
 * @param updater the function to call to update arbitrary values in consul
 */
class ConsulKVReporter(
    consul: ConsulClient,
    initialDelay: FiniteDuration,
    interval: FiniteDuration,
    hostKey: String,
    startKey: String,
    statusKey: String,
    updater: Int => Map[String, JsValue])(
        implicit val system: ActorSystem,
        logging: Logging) {

    implicit val executionContext = system.dispatcher
    private var count = 0

    system.scheduler.scheduleOnce(initialDelay) {
        val (selfHostname, _, _) = SimpleExec.syncRunCmd(Array("hostname", "-f"))(TransactionId.unknown, logging)
        consul.kv.put(hostKey, selfHostname.toJson.compactPrint)
        consul.kv.put(startKey, DateUtil.getTimeString.toJson.compactPrint)

        Scheduler.scheduleWaitAtLeast(interval) { () =>
            val statusPut = consul.kv.put(statusKey, DateUtil.getTimeString.toJson.compactPrint)
            val updatePuts = updater(count) map {
                case (k, v) => consul.kv.put(k, v.compactPrint)
            }
            count = count + 1
            val allPuts = updatePuts.toSeq :+ statusPut
            Future.sequence(allPuts)
        }
    }
}
