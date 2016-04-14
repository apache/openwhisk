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

package whisk.common

import spray.json.JsValue
import spray.json.JsString

/**
 * An object with an underlying thread that periodically stores into the ConsulKV store.
 *
 * Set up a Consul KV interface at the given agent address.
 */
class ConsulKVReporter(
    kvStore: ConsulKV,
    initialDelayMilli: Int,
    periodMilli: Int,
    hostKey: String,
    startKey: String,
    statusKey: String,
    updater: () => Map[String, JsValue]) {

    private val t = new Thread() {
        override def run() = {
            Thread.sleep(initialDelayMilli)
            val (selfHostname, stderr, exitCode) = SimpleExec.syncRunCmd(Array("hostname", "-f"))(TransactionId.unknown)
            kvStore.put(hostKey, JsString(selfHostname))
            kvStore.put(startKey, JsString(s"${DateUtil.getTimeString}"))
            while (true) {
                kvStore.put(statusKey, JsString(s"${DateUtil.getTimeString}"))
                updater().foreach({ case (k, v) => kvStore.put(k, v) })
                Thread.sleep(periodMilli)
            }
        }
    }

    // Start the actual thread
    t.start()
}
