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

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import spray.json.JsObject
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.LoggingMarkers._
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.LoadBalancerResponse

trait LoadBalancerToKafka extends Logging {

    /** Gets a producer which can publish messages to the kafka bus. */
    val producer: KafkaProducerConnector

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext

    override def setVerbosity(level: Verbosity.Level) = {
        super.setVerbosity(level)
        producer.setVerbosity(level)
    }

    /**
     * Publishes message on kafka bus for the invoker to pick up.
     *
     * @param topic the topic name extracted from URI
     * @param msg the message received via POST
     * @param transid the transaction id, this may be the tid assigned by the controller and carried by the message or one determined by the load balancer service
     * @return msg to return in HTTP response
     */
    def doPublish(component: String, msg: Message)(implicit transid: TransactionId): Future[LoadBalancerResponse] = {
        getTopic(component, msg) match {
            case Some(topic) =>
                val subject = msg.subject()
                val userCount = activationThrottle.countForNamespace(subject)
                val userLimit = activationThrottle.limitForNamespace(subject)
                info(this, s"(DoS) current activation count for '$subject': $userCount (limit=$userLimit)")
                if (userCount > userLimit) {
                    info(this, s"(DoS) '$subject' maxed concurrent invocations")
                    Future.successful(throttleError)
                } else {
                    info(this, s"posting topic '$topic' with activation id '${msg.activationId}'", LOADBALANCER_POST_KAFKA)
                    producer.send(topic, msg) map { status =>
                        if (component == Message.INVOKER) {
                            activationCounter.next()
                            val counter = incrementUserActivationCounter(subject)
                            info(this, s"user has ${counter} activations posted. Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                        }
                        LoadBalancerResponse.id(msg.activationId)
                    }
                }
            case None => Future.successful(idError)
        }
    }

    /**
     * Gets an invoker index to send request to.
     *
     * @return index of invoker to receive request
     */
    def getInvoker(message: Message): Option[Int]

    private def getTopic(component: String, message: Message): Option[String] = {
        if (component == Message.INVOKER) {
            getInvoker(message) map { i => s"$component$i" }
        } else Some(component)
    }

    private def incrementUserActivationCounter(user: String): Int = {
        userActivationCounter get user match {
            case Some(counter) => counter.next()
            case None =>
                val counter = new Counter()
                counter.next()
                userActivationCounter(user) = counter
                counter.cur
        }
    }

    def getActivationCount(): Int = {
        activationCounter.cur
    }

    protected def getUserActivationCounts(): Map[String, JsObject] = {
        ActivationThrottle.encodeLoadBalancerUserActivation(userActivationCounter)
    }

    private val activationCounter = new Counter()
    private val userActivationCounter = new TrieMap[String, Counter]
    private val idError = LoadBalancerResponse.error("no invokers available")
    private val throttleError = LoadBalancerResponse.error("too many concurrent activations")

    def activationThrottle: ActivationThrottle
}
