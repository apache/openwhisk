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

import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.common.LoggingMarkers
import whisk.common.PrintStreamEmitter
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.connector.{ ActivationMessage => Message }
import akka.event.Logging.LogLevel

trait LoadBalancerToKafka extends Logging {

    /** Gets a producer which can publish messages to the kafka bus. */
    val producer: KafkaProducerConnector

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext

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
     * @return result of publishing the message as a Future
     */
    def publish(msg: Message)(implicit transid: TransactionId): Future[Unit] = {
        getInvoker(msg) map {
            val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA)
            invokerIndex =>
                val topic = Message.invoker(invokerIndex)
                val subject = msg.subject()
                info(this, s"posting topic '$topic' with activation id '${msg.activationId}'")
                producer.send(topic, msg) map { status =>
                    val counter = updateActivationCount(subject, invokerIndex)
                    transid.finished(this, start, s"user has ${counter} activations posted. Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                }
        } getOrElse {
            Future.failed(new LoadBalancerException("no invokers available"))
        }
    }

    /**
     * Gets an invoker index to send request to.
     *
     * @return index of invoker to receive request
     */
    def getInvoker(message: Message): Option[Int]

    private def updateActivationCount(user: String, invokerIndex: Int): Int = {
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
    def getIssueCountByInvoker(): Map[Int, Int] = invokerActivationCounter.readOnlySnapshot.mapValues(_.cur).toMap

    /**
     * Retrieve a snapshot of activation counts issued per subject by load balancer
     */
    def getUserActivationCounts(): Map[String, Long] = userActivationCounter.toMap mapValues { _.cur.toLong }

    // A count of how many activations have been posted to Kafka based on invoker index or user/subject.
    private val invokerActivationCounter = new TrieMap[Int, Counter]
    private val userActivationCounter = new TrieMap[String, Counter]
    private case class LoadBalancerException(msg: String) extends Throwable(msg)

}
