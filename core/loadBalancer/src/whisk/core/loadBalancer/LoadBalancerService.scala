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

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import spray.json.JsBoolean
import spray.json.JsObject
import whisk.common.ConsulClient
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Logging
import whisk.common.Verbosity
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{ servicePort, kafkaHost, consulServer, kafkaPartitions }
import whisk.core.connector.{ ActivationMessage, CompletionMessage }
import whisk.utils.ExecutionContextFactory
import scala.concurrent.ExecutionContext

class LoadBalancerService(config: WhiskConfig, verbosity: Verbosity.Level)(
    implicit val actorSystem: ActorSystem,
    val executionContext: ExecutionContext)
    extends LoadBalancerToKafka
    with Logging {

    /**
     * The two public methods are getInvokerHealth and the inherited doPublish methods.
     */
    def getInvokerHealth(): JsObject = invokerHealth.getInvokerHealth()

    override def getInvoker(message: ActivationMessage): Option[Int] = invokerHealth.getInvoker(message)
    override def activationThrottle = _activationThrottle

    override val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    private val invokerHealth = new InvokerHealth(config, { () => producer.sentCount() })
    private val _activationThrottle = new ActivationThrottle(config.consulServer, invokerHealth)

    // This must happen after the overrides
    setVerbosity(verbosity)

    private var count = 0
    private val overloadThreshold = 5000 // this is the total across all invokers.  Disable by setting to -1.
    private val kv = new ConsulClient(config.consulServer)
    private val reporter = new ConsulKVReporter(kv, 3 seconds, 2 seconds,
        LoadBalancerKeys.hostnameKey,
        LoadBalancerKeys.startKey,
        LoadBalancerKeys.statusKey,
        { () =>
            val producedCount = getActivationCount()
            val invokerInfo = invokerHealth.getInvokerActivationCounts()
            val consumedCount = invokerInfo map {
                case (index, count) => count
            } sum
            val inFlight = producedCount - consumedCount
            val overload = JsBoolean(overloadThreshold > 0 && inFlight >= overloadThreshold)
            count = count + 1
            if (count % 10 == 0) {
                warn(this, s"In flight: $producedCount - $consumedCount = $inFlight $overload")
                info(this, s"Completion counts: [${invokerInfo.mkString(", ")}]")
            }
            Map(LoadBalancerKeys.overloadKey -> overload,
                LoadBalancerKeys.invokerHealth -> getInvokerHealth()) ++
                getUserActivationCounts()
        })

    /**
     * WIP
     *
     * @param msg is the kafka message payload as Json
     */
    def processCompletion(msg: CompletionMessage) = {
        implicit val tid = msg.transid
        val aid = msg.activationId
        info(this, s"LoadBalancerService.processCompletion: activation id $aid")
    }

    val consumer = new KafkaConsumerConnector(config.kafkaHost, "completions", "completed")
    consumer.onMessage((topic, bytes) => {
        val raw = new String(bytes, "utf-8")
        CompletionMessage(raw) match {
            case Success(m) =>
                processCompletion(m)
            case Failure(t) =>
                error(this, s"failed processing message: $raw with $t")
        }
        true
    })

}

object LoadBalancerService {
    def requiredProperties =
        Map(servicePort -> null) ++
            kafkaHost ++
            consulServer ++
            Map(kafkaPartitions -> null) ++
            InvokerHealth.requiredProperties
}
