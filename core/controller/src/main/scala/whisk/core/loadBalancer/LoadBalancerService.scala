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

import java.time.{Clock, Instant}

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
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.PrintStreamEmitter
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.connector.ActivationMessage
import whisk.core.connector.CompletionMessage
import whisk.core.entity.ActivationId
import whisk.core.entity.WhiskActivation

class LoadBalancerService(config: WhiskConfig, verbosity: LogLevel, unitTest: Boolean = false)(
    implicit val actorSystem: ActorSystem)
    extends Logging {

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext = actorSystem.dispatcher

    /**
     * The two public methods are getInvokerHealth and the inherited doPublish methods.
     */
    def getInvokerHealth(): JsObject = invokerHealth.getInvokerHealthJson()

    /**
     * Gets an invoker index to send request to.
     *
     * @return index of invoker to receive request
     */
    def getInvoker(message: ActivationMessage): Option[Int] = invokerHealth.getInvoker(message)

    /** Gets a producer which can publish messages to the kafka bus. */
    val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    private val invokerHealth = new InvokerHealth(config, resetIssueCountByInvoker, () => producer.sentCount())

    /**
     * A map storing current activations based on ActivationId.
     * The promise value represents the obligation of writing the answer back.
     */
    type ActivationEntry = (Instant, Promise[WhiskActivation])
    private val activationMap = new TrieMap[ActivationId, ActivationEntry]

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
            Map(LoadBalancerKeys.invokerHealth -> getInvokerHealth())
        })

    /**
     * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
     * The promise is removed form the map when the result arrives or upon timeout.
     *
     * @param msg is the kafka message payload as Json
     */
    def processCompletion(msg: CompletionMessage) = {
        implicit val tid = msg.transid
        val aid = msg.response.activationId
        info(this, s"received active ack for '$aid'")
        val response = msg.response
        activationMap.remove(aid) match {
            case Some((_, p)) =>
                p.trySuccess(response)
                info(this, s"processed active response for '$aid'")
            case None =>
                warn(this, s"processed active response for '$aid' which has no entry")
        }
    }

    /**
     * Tries for a while to get a WhiskActivation from the fast path instead of hitting the DB.
     * Instead of sleep/poll, the promise is filled in when the completion messages arrives.
     * If for some reason, there is no ack, promise eventually times out and the promise is removed.
     */
    def queryActivationResponse(activationId: ActivationId, timeout: FiniteDuration, transid: TransactionId): Future[WhiskActivation] = {
        implicit val tid = transid
        setupActivation(activationId, timeout, transid)._2.future
    }

    /**
     * Creates an entry in the activation map where we note the time of publishing and establishes a place to store the result.
     */
    private def setupActivation(activationId: ActivationId, timeout: FiniteDuration, transid: TransactionId): ActivationEntry = {
        // either create a new promise or reuse a previous one for this activation if it exists
        activationMap.getOrElseUpdate(activationId, {
            val promise = Promise[WhiskActivation]
            // store the promise to complete on success, and the timed future that completes
            // with the TimeoutException after alloted time has elapsed
            after(timeout, actorSystem.scheduler)({
                if (activationMap.remove(activationId).isDefined) {
                    val failedit = promise.tryFailure(new ActiveAckTimeout(activationId))
                    if (failedit) info(this, "active response timed out")
                }
                Future.successful {} // do not care about this future, need to return promise.future below
            })
            (Instant.now(Clock.systemUTC()), promise)
        })
    }

    if (!unitTest) {
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

    private implicit val emitter: PrintStreamEmitter = this

    override def setVerbosity(level: LogLevel) = {
        super.setVerbosity(level)
        producer.setVerbosity(level)
    }

    /**
     * Publishes message on kafka bus for the invoker to pick up.
     *
     * @param msg the activation message to publish on an invoker topic
     * @param transid the transaction id for the request
     * @return result a pair of Futures the first indicating completion of publishing and the second the completion of the action
     */
    def publish(msg: ActivationMessage, timeout: FiniteDuration)(implicit transid: TransactionId): (Future[Unit], Future[WhiskActivation]) = {
        getInvoker(msg) map {
            val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA)
            invokerIndex =>
                val topic = ActivationMessage.invoker(invokerIndex)
                val subject = msg.subject()
                val entry = setupActivation(msg.activationId, timeout, transid)
                info(this, s"posting topic '$topic' with activation id '${msg.activationId}'")
                (producer.send(topic, msg) map { status =>
                    val counter = updateActivationCount(subject, invokerIndex)
                    transid.finished(this, start, s"user has ${counter} activations posted. Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                }, entry._2.future)
        } getOrElse {
            (Future.failed(new LoadBalancerException("no invokers available")),
             Future.failed(new LoadBalancerException("no invokers available")))
        }
    }

    private def updateActivationCount(user: String, invokerIndex: Int): Long = {
        invokerActivationCounter get invokerIndex match {
            case Some(counter) => counter.next()
            case None =>
                invokerActivationCounter(invokerIndex) = new Counter()
                invokerActivationCounter(invokerIndex).next
        }
        userActivationCounter get user match {
            case Some(counter) => counter.next()
            case None =>
                userActivationCounter(user) = new Counter()
                userActivationCounter(user).next
        }
    }

    def resetIssueCountByInvoker(invokerIndices: Array[Int]) = {
        invokerIndices.foreach {
            invokerActivationCounter(_) = new Counter()
        }
    }

    // Make a new immutable map so caller cannot mess up the state
    def getIssueCountByInvoker(): Map[Int, Long] = invokerActivationCounter.readOnlySnapshot.mapValues(_.cur).toMap

    /**
     * Retrieve a snapshot of activation counts issued per subject by load balancer
     *
     * @returns a map where the key is the subject and the long is total issued activations by that user
     */
    def getUserActivationCounts: Map[String, Long] = userActivationCounter.toMap mapValues { _.cur }

    // A count of how many activations have been posted to Kafka based on invoker index or user/subject.
    private val invokerActivationCounter = new TrieMap[Int, Counter]
    private val userActivationCounter = new TrieMap[String, Counter]
    private case class LoadBalancerException(msg: String) extends Throwable(msg)

}


object LoadBalancerService {
    def requiredProperties = kafkaHost ++
        consulServer ++
        InvokerHealth.requiredProperties
}

private case class ActiveAckTimeout(activationId: ActivationId) extends TimeoutException
