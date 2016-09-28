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
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.pattern.after
import spray.json.JsObject
import whisk.common.ConsulClient
import whisk.common.ConsulKV.LoadBalancerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.controller.WhiskServices.LoadBalancerReq
import whisk.core.entity.ActivationId
import whisk.core.entity.WhiskActivation

class LoadBalancerService(config: WhiskConfig, verbosity: LogLevel)(
    implicit val actorSystem: ActorSystem)
    extends LoadBalancerToKafka
    with Logging {

    implicit val executionContext: ExecutionContext = actorSystem.dispatcher

    /**
     * The two public methods are getInvokerHealth and the inherited doPublish methods.
     */
    def getInvokerHealth(): JsObject = invokerHealth.getInvokerHealthJson()

    override def getInvoker(message: ActivationMessage): Option[Int] = invokerHealth.getInvoker(message)

    override val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    private val invokerHealth = new InvokerHealth(config, resetIssueCountByInvoker, () => producer.sentCount())

    // map stores the promise which either completes if an active response is received or
    // after the set timeout expires
    private val queryMap = new TrieMap[ActivationId, Promise[WhiskActivation]]

    // this must happen after the overrides
    setVerbosity(verbosity)

    private val kv = new ConsulClient(config.consulServer)
    private val reporter = new ConsulKVReporter(kv, 3 seconds, 2 seconds,
        LoadBalancerKeys.hostnameKey,
        LoadBalancerKeys.startKey,
        LoadBalancerKeys.statusKey,
        { count =>
            // Get counts as seen by the loadbalancer
            val issuedCounts = getIssueCountByInvoker()
            val issuedCount = issuedCounts.values.sum

            // Get counts as seen by the invokers
            val invokerCounts = invokerHealth.getInvokerActivationCounts()
            val completedCount = invokerCounts.values.sum

            val inFlight = issuedCount - completedCount

            val health = invokerHealth.getInvokerHealth()
            if (count % 10 == 0) {
                implicit val sid = TransactionId.loadbalancer
                info(this, s"In flight: $issuedCount - $completedCount = $inFlight")
                info(this, s"Issued counts: [${issuedCounts.mkString(", ")}]")
                info(this, s"Completion counts: [${invokerCounts.mkString(", ")}]")
                info(this, s"Invoker health: [${health.mkString(", ")}]")
            }
            Map(LoadBalancerKeys.invokerHealth -> getInvokerHealth()) ++
                getUserActivationCounts()
        })

    /**
     * A bridge method to impedance match against RestAPI.
     */
    def acceptRequest(lbr: LoadBalancerReq) = publish(lbr._1)(lbr._2)

    /**
     * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
     * The promise is removed form the map when the result arrives or upon timeout.
     *
     * @param msg is the kafka message payload as Json
     */
    def processCompletion(msg: CompletionMessage) = {
        implicit val tid = msg.transid
        val aid = msg.response.activationId
        val response = msg.response
        val promise = queryMap.remove(aid)
        promise map { p =>
            p.trySuccess(response)
            info(this, s"processed active response for '$aid'")
        }
    }

    /**
     * Tries for a while to get a WhiskActivation from the fast path instead of hitting the DB.
     * Instead of sleep/poll, the promise is filled in when the completion messages arrives.
     * If for some reason, there is no ack, promise eventually times out and the promise is removed.
     */
    def queryActivationResponse(activationId: ActivationId, timeout: FiniteDuration, transid: TransactionId): Future[WhiskActivation] = {
        implicit val tid = transid
        // either create a new promise or reuse a previous one for this activation if it exists
        queryMap.getOrElseUpdate(activationId, {
            val promise = Promise[WhiskActivation]
            // store the promise to complete on success, and the timed future that completes
            // with the TimeoutException after alloted time has elapsed
            after(timeout, actorSystem.scheduler)({
                if (queryMap.remove(activationId).isDefined) {
                    val failedit = promise.tryFailure(new ActiveAckTimeout(activationId))
                    if (failedit) info(this, "active response timed out")
                }
                Future.successful {} // do not care about this future, need to return promise.future below
            })
            promise
        }).future
    }

    val consumer = new KafkaConsumerConnector(config.kafkaHost, "completions", "completed")
    consumer.setVerbosity(verbosity)
    consumer.onMessage((topic, partition, offset, bytes) => {
        val raw = new String(bytes, "utf-8")
        CompletionMessage(raw) match {
            case Success(m) => processCompletion(m)
            case Failure(t) => error(this, s"failed processing message: $raw with $t")
        }
    })

}

object LoadBalancerService {
    def requiredProperties = kafkaHost ++
        consulServer ++
        InvokerHealth.requiredProperties
}

private case class ActiveAckTimeout(activationId: ActivationId) extends TimeoutException
