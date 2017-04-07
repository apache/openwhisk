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

import java.time.{ Clock, Instant }
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.pattern.ask
import whisk.common.ConsulClient
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{ consulServer, kafkaHost, loadbalancerActivationCountBeforeNextInvoker }
import whisk.core.connector.{ ActivationMessage, CompletionMessage }
import whisk.core.entity.{ ActivationId, CodeExec, WhiskAction, WhiskActivation }
import whisk.core.connector.PingMessage
import akka.util.Timeout
import akka.actor.ActorRefFactory

trait LoadBalancer {

    /**
     * Retrieves a per subject map of counts representing in-flight activations as seen by the load balancer
     *
     * @return a map where the key is the subject and the long is total issued activations by that user
     */
    def getActiveUserActivationCounts: Map[String, Int]

    /**
     * Publishes activation message on internal bus for the invoker to pick up.
     *
     * @param msg the activation message to publish on an invoker topic
     * @param timeout the desired active ack timeout
     * @param transid the transaction id for the request
     * @return result a nested Future the outer indicating completion of publishing and
     *         the inner the completion of the action (i.e., the result)
     *         if it is ready before timeout otherwise the future fails with ActiveAckTimeout
     */
    def publish(action: WhiskAction, msg: ActivationMessage, timeout: FiniteDuration)(implicit transid: TransactionId): Future[Future[WhiskActivation]]

}

class LoadBalancerService(config: WhiskConfig)(implicit val actorSystem: ActorSystem, logging: Logging) extends LoadBalancer {

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext = actorSystem.dispatcher

    /** How many invokers are dedicated to blackbox images.  We range bound to something sensical regardless of configuration. */
    private val blackboxFraction: Double = Math.max(0.0, Math.min(1.0, config.controllerBlackboxFraction))
    logging.info(this, s"blackboxFraction = $blackboxFraction")

    /** We run this often on an invoker before going onto the next. */
    private val activationCountBeforeNextInvoker = Math.max(1, config.loadbalancerActivationCountBeforeNextInvoker)
    logging.info(this, s"activationCountBeforeNextInvoker = $activationCountBeforeNextInvoker")

    override def getActiveUserActivationCounts: Map[String, Int] = activationBySubject.toMap mapValues { _.size }

    /** Gets a producer which can publish messages to the kafka bus. */
    private val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)
    override def publish(action: WhiskAction, msg: ActivationMessage, timeout: FiniteDuration)(
        implicit transid: TransactionId): Future[Future[WhiskActivation]] = {
        chooseInvoker(action, msg).flatMap { invokerName =>
            val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA)
            val topic = invokerName
            val subject = msg.user.subject.asString
            val entry = setupActivation(msg.activationId, subject, invokerName, timeout, transid)
            logging.info(this, s"posting topic '$topic' with activation id '${msg.activationId}'")
            producer.send(topic, msg).map { status =>
                transid.finished(this, start, s"Posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
                entry.promise.future
            }
        }
    }

    /**
     * A map storing current activations based on ActivationId.
     * The promise value represents the obligation of writing the answer back.
     */
    case class ActivationEntry(id: ActivationId, subject: String, invokerName: String, created: Instant, promise: Promise[WhiskActivation])
    type TrieSet[T] = TrieMap[T, Unit]
    private val activationById = new TrieMap[ActivationId, ActivationEntry]
    private val activationByInvoker = new TrieMap[String, TrieSet[ActivationEntry]]
    private val activationBySubject = new TrieMap[String, TrieSet[ActivationEntry]]

    private val ackConsumer = new KafkaConsumerConnector(config.kafkaHost, "completions", "completed")
    ackConsumer.onMessage((topic, _, _, bytes) => {
        val raw = new String(bytes, "utf-8")
        CompletionMessage.parse(raw) match {
            case Success(m: CompletionMessage) => processCompletion(m)
            case Failure(t)                    => logging.error(this, s"failed processing message: $raw with $t")(TransactionId.loadbalancer)
        }
    })

    /**
     * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
     * The promise is removed form the map when the result arrives or upon timeout.
     *
     * @param msg is the kafka message payload as Json
     */
    private def processCompletion(msg: CompletionMessage) = {
        implicit val tid = msg.transid
        val aid = msg.response.activationId
        logging.info(this, s"received active ack for '$aid'")
        val response = msg.response
        activationById.remove(aid) match {
            case Some(entry @ ActivationEntry(_, subject, invokerIndex, _, p)) =>
                activationByInvoker.getOrElseUpdate(invokerIndex, new TrieSet[ActivationEntry]).remove(entry)
                activationBySubject.getOrElseUpdate(subject, new TrieSet[ActivationEntry]).remove(entry)
                p.trySuccess(response)
                logging.info(this, s"processed active response for '$aid'")
            case None =>
                logging.warn(this, s"processed active response for '$aid' which has no entry")
        }
    }

    /**
     * Creates an activation entry and insert into various maps.
     */
    private def setupActivation(activationId: ActivationId, subject: String, invokerName: String, timeout: FiniteDuration, transid: TransactionId): ActivationEntry = {
        // either create a new promise or reuse a previous one for this activation if it exists
        val entry = activationById.getOrElseUpdate(activationId, {
            val promise = Promise[WhiskActivation]
            // store the promise to complete on success, or fail with ActiveAckTimeout if time is up
            // however, do not remove the entry as this is done only by processCompletion
            actorSystem.scheduler.scheduleOnce(timeout) {
                activationById.get(activationId).foreach { _ =>
                    if (promise.tryFailure(new ActiveAckTimeout(activationId))) {
                        logging.info(this, "active response timed out")(transid)
                    }
                }
            }
            ActivationEntry(activationId, subject, invokerName, Instant.now(Clock.systemUTC()), promise)
        })
        activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry]).put(entry, {})
        activationBySubject.getOrElseUpdate(subject, new TrieSet[ActivationEntry]).put(entry, {})
        entry
    }

    /**
     * When invoker health detects a new invoker has come up, this callback is called.
     */
    private def clearInvokerState(invokerName: String) = {
        val actSet = activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry])
        actSet.keySet map {
            case actEntry @ ActivationEntry(activationId, subject, invokerIndex, _, promise) =>
                promise.tryFailure(new LoadBalancerException(s"Invoker $invokerIndex restarted"))
                activationById.remove(activationId)
                activationBySubject.get(subject) map { _.remove(actEntry) }
        }
        actSet.clear()
    }

    /** Make a new immutable map so caller cannot mess up the state */
    private def getActiveCountByInvoker(): Map[String, Int] = activationByInvoker.toMap mapValues { _.size }

    /*
     * INVOKER MANAGEMENT
     */

    private val pingConsumer = new KafkaConsumerConnector(config.kafkaHost, "health", "health")
    pingConsumer.onMessage((topic, _, _, bytes) => {
        val raw = new String(bytes, "utf-8")
        PingMessage.parse(raw) match {
            case Success(p: PingMessage) => invokerPool ! p
            case Failure(t)              => logging.error(this, s"failed processing message: $raw with $t")
        }
    })

    private val consul = new ConsulClient(config.consulServer)
    private val invokerFactory = (f: ActorRefFactory, name: String) => f.actorOf(InvokerActor.props, name)
    private val invokerPool = actorSystem.actorOf(InvokerPool.props(invokerFactory, consul.kv, invoker => {
        clearInvokerState(invoker)
        logging.info(this, s"cleared loadbalancer state of $invoker")(TransactionId.invokerHealth)
    }))

    def invokerHealth: Future[Map[String, InvokerState]] = invokerPool.ask(GetStatus)(Timeout(5.seconds)).mapTo[Map[String, InvokerState]]

    /** Return a sorted list of available invokers. */
    private def availableInvokers: Future[Seq[String]] = invokerHealth.map {
        _.collect {
            case (name, Healthy) => name
        }.toSeq.sortBy(_.drop(7).toInt) // Sort by the number in "invokerN"
    }.recover {
        case _ => Seq.empty[String]
    }

    /** Compute the number of blackbox-dedicated invokers by applying a rounded down fraction of all invokers (but at least 1). */
    private def numBlackbox(totalInvokers: Int) = Math.max(1, (totalInvokers.toDouble * blackboxFraction).toInt)

    /** Return invokers (almost) dedicated to running blackbox actions. */
    private def blackboxInvokers: Future[Seq[String]] = availableInvokers.map { allInvokers =>
        allInvokers.takeRight(numBlackbox(allInvokers.length))
    }

    /** Return (at least one) invokers for running non black-box actions.  This set can overlap with the blackbox set if there is only one invoker. */
    private def managedInvokers: Future[Seq[String]] = availableInvokers.map { allInvokers =>
        val numManaged = Math.max(1, allInvokers.length - numBlackbox(allInvokers.length))
        allInvokers.take(numManaged)
    }

    /** Determine which invoker this activation should go to. Due to dynamic conditions, it may return no invoker. */
    private def chooseInvoker(action: WhiskAction, msg: ActivationMessage): Future[String] = {
        val isBlackbox = action.exec match {
            case e: CodeExec[_] => e.pull
            case _              => false
        }
        val invokers = if (isBlackbox) blackboxInvokers else managedInvokers
        val (hash, count) = hashAndCountSubjectAction(msg)

        invokers.flatMap { invokers =>
            val numInvokers = invokers.length
            if (numInvokers > 0) {
                val hashCount = math.abs(hash + count / activationCountBeforeNextInvoker)
                val invokerIndex = hashCount % numInvokers
                Future.successful(invokers(invokerIndex))
            } else {
                logging.error(this, s"all invokers down")(TransactionId.invokerHealth)
                Future.failed(new LoadBalancerException("no invokers available"))
            }
        }
    }

    /*
     * The path contains more than the action per se but seems sufficiently
     * isomorphic as the other parts are constant.  Extracting just the
     * action out specifically will involve some hairy regex's that the
     * Invoker is currently using and which is better avoid if/until
     * these are moved to some common place (like a subclass of Message?)
     */
    private val activationCountMap = TrieMap[(String, String), AtomicInteger]()
    private def hashAndCountSubjectAction(msg: ActivationMessage): (Int, Int) = {
        val subject = msg.user.subject.asString
        val path = msg.action.toString
        val hash = subject.hashCode() ^ path.hashCode()
        val key = (subject, path)
        val count = activationCountMap.get(key) match {
            case Some(counter) => counter.getAndIncrement()
            case None => {
                activationCountMap.put(key, new AtomicInteger(0))
                0
            }
        }
        return (hash, count)
    }
}

object LoadBalancerService {
    def requiredProperties = kafkaHost ++ consulServer ++
        Map(loadbalancerActivationCountBeforeNextInvoker -> null)
}

private case class ActiveAckTimeout(activationId: ActivationId) extends TimeoutException
private case class LoadBalancerException(msg: String) extends Throwable(msg)
