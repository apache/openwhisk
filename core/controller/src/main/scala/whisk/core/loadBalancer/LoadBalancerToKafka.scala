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

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.LoadBalancerResponse
import akka.event.Logging.LogLevel
import whisk.common.ConsulKV.LoadBalancerKeys

trait LoadBalancerToKafka extends Logging {

    /** Gets a producer which can publish messages to the kafka bus. */
    val producer: KafkaProducerConnector

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext

    override def setVerbosity(level: LogLevel) = {
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
            case Some((invokerIndex, topic)) =>
                val subject = msg.subject()
                info(this, s"posting topic '$topic' with activation id '${msg.activationId}'")
                producer.send(topic, msg) map { status =>
                    if (component == Message.INVOKER) {
                        val counter = updateActivationCount(subject)
                        info(this, s"user has ${counter} activations posted. Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                    }
                    LoadBalancerResponse.id(msg.activationId)
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

    private def getTopic(component: String, message: Message): Option[(Int, String)] = {
        if (component == Message.INVOKER) {
            getInvoker(message) map { i => (i, s"$component$i") }
        } else Some(-1, component)
    }

    /**
     * Updates the activation count for the user by one. Creating
     * a new counter iff the user doesn't exist yet in the map.
     */
    private def updateActivationCount(user: String): Int =
        userActivationCounter.getOrElseUpdate(user, new Counter()).next()

    /**
     * Convert user activation counters into a map of JsObjects to be written into consul kv
     *
     * @param userActivationCounter the counters for each user's activations
     * @return a map where the key represents the final nested key structure for consul and a JsObject
     *     containing the activation counts for each user
     */
    protected def getUserActivationCounts(): Map[String, JsObject] =
        userActivationCounter.toMap mapValues {
            _.cur
        } groupBy {
            case (key, _) => LoadBalancerKeys.userActivationCountKey + "/" + key.substring(0, 1)
        } mapValues { map =>
            map.toJson.asJsObject
        }

    // A count of how many activations have been posted to Kafka based on invoker index or user/subject.
    private val userActivationCounter = new TrieMap[String, Counter]
    private val idError = LoadBalancerResponse.error("no invokers available")

}
