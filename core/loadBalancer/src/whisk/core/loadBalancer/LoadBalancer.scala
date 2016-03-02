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

import akka.actor.Actor
import akka.japi.Creator
import spray.json.JsObject
import spray.json.JsBoolean
import whisk.common.ConsulKV
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Verbosity
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.connector.Message
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.WhiskConfig.kafkaPartitions
import whisk.core.WhiskConfig.servicePort
import whisk.http.BasicHttpService
import whisk.utils.ExecutionContextFactory

class LoadBalancer(config: WhiskConfig, verbosity: Verbosity.Level)
    extends LoadBalancerService
    with LoadBalancerToKafka
    with Actor {

    setVerbosity(verbosity)

    override def getInvoker(message : Message): Option[Int] = invokerHealth.getInvoker(message)
    override def getInvokerHealth: JsObject = invokerHealth.getInvokerHealth()
    override def activationThrottle = _activationThrottle

    override def actorRefFactory = context
    override val executionContext = ExecutionContextFactory.makeExecutionContext()
    override val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    private val kvStore = new ConsulKV(config.consulServer)
    private val invokerHealth = new InvokerHealth(config, { () => producer.sentCount() })
    private val _activationThrottle = new ActivationThrottle(LoadBalancer.config.consulServer, invokerHealth)

    // --- WIP -----
    private var count = 0
    private val overloadThreshold = 5000  // this is the total across all invokers.  Disable by setting to -1.
    private val reporter = new ConsulKVReporter(kvStore, 3000, 2000,
        LoadBalancerKeys.hostnameKey,
        LoadBalancerKeys.startKey,
        LoadBalancerKeys.statusKey,
        { () =>
            val producedCount = getActivationCount()
            val consumedCounts = invokerHealth.getInvokerActivationCounts()
            val inFlight = producedCount - consumedCounts.fold(0) { (total, n) => total + n }
            val overload = JsBoolean(overloadThreshold > 0 && inFlight >= overloadThreshold)
            count = count + 1
            if (count % 10 == 0) {
              warn(this, s"In flight: ${producedCount} - [${consumedCounts.mkString(", ")}] = ${inFlight} ${overload}")
            }
            Map(LoadBalancerKeys.overloadKey -> overload,
                LoadBalancerKeys.invokerHealth -> getInvokerHealth()) ++
            getUserActivationCounts()
        })

}

object LoadBalancer {

    def requiredProperties =
        Map(servicePort -> null) ++
            kafkaHost ++
            consulServer ++
            Map(kafkaPartitions -> null) ++
            InvokerHealth.requiredProperties

    private class ServiceBuilder(config: WhiskConfig) extends Creator[LoadBalancer] {
        def create = new LoadBalancer(config, Verbosity.Loud)
    }

    def main(args: Array[String]): Unit = {

        if (config.isValid) {
            val port = config.servicePort.toInt
            BasicHttpService.startService("loadbalancer", "0.0.0.0", port, new ServiceBuilder(config))
        }
    }

    val config = new WhiskConfig(requiredProperties)
}
