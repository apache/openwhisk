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

package whisk.core.loadBalancer

import java.nio.charset.StandardCharsets
import java.time.{ Clock, Instant }

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import org.apache.kafka.clients.producer.RecordMetadata

import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout

import whisk.common.ConsulClient
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.connector.{ ActivationMessage, CompletionMessage }
import whisk.core.connector.MessageFeed
import whisk.core.connector.MessageProducer
import whisk.core.database.NoDocumentException
import whisk.core.entity.{ ActivationId, WhiskAction, WhiskActivation }
import whisk.core.entity.InstanceId
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAction
import whisk.core.entity.types.EntityStore
import scala.annotation.tailrec

trait LoadBalancer {

    val activeAckTimeoutGrace = 1.minute

    /**
     * Retrieves a per namespace id map of counts representing in-flight activations as seen by the load balancer
     *
     * @return a map where the key is the namespace id and the long is total issued activations by that namespace
     */
    def getActiveNamespaceActivationCounts: Map[UUID, Int]

    /**
     * Publishes activation message on internal bus for an invoker to pick up.
     *
     * @param action the action to invoke
     * @param msg the activation message to publish on an invoker topic
     * @param transid the transaction id for the request
     * @return result a nested Future the outer indicating completion of publishing and
     *         the inner the completion of the action (i.e., the result)
     *         if it is ready before timeout (Right) otherwise the activation id (Left).
     *         The future is guaranteed to complete within the declared action time limit
     *         plus a grace period (see activeAckTimeoutGrace).
     */
    def publish(action: ExecutableWhiskAction, msg: ActivationMessage)(implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]]

}

class LoadBalancerService(
    config: WhiskConfig,
    instance: InstanceId,
    entityStore: EntityStore)(
        implicit val actorSystem: ActorSystem,
        logging: Logging)
    extends LoadBalancer {

    /** The execution context for futures */
    implicit val executionContext: ExecutionContext = actorSystem.dispatcher

    /** How many invokers are dedicated to blackbox images.  We range bound to something sensical regardless of configuration. */
    private val blackboxFraction: Double = Math.max(0.0, Math.min(1.0, config.controllerBlackboxFraction))
    logging.info(this, s"blackboxFraction = $blackboxFraction")

    override def getActiveNamespaceActivationCounts: Map[UUID, Int] = activationByNamespaceId.toMap mapValues { _.size }

    override def publish(action: ExecutableWhiskAction, msg: ActivationMessage)(
        implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
        chooseInvoker(action, msg).flatMap { invokerName =>
            val entry = setupActivation(action, msg.activationId, msg.user.uuid, invokerName, transid)
            sendActivationToInvoker(messageProducer, msg, invokerName).map { _ =>
                entry.promise.future
            }
        }
    }

    def invokerHealth: Future[Map[String, InvokerState]] = invokerPool.ask(GetStatus)(Timeout(5.seconds)).mapTo[Map[String, InvokerState]]

    /**
     * A map storing current activations based on ActivationId.
     * The promise value represents the obligation of writing the answer back.
     */
    case class ActivationEntry(id: ActivationId, namespaceId: UUID, invokerName: String, created: Instant, promise: Promise[Either[ActivationId, WhiskActivation]])
    type TrieSet[T] = TrieMap[T, Unit]
    private val activationById = new TrieMap[ActivationId, ActivationEntry]
    private val activationByInvoker = new TrieMap[String, TrieSet[ActivationEntry]]
    private val activationByNamespaceId = new TrieMap[UUID, TrieSet[ActivationEntry]]

    /**
     * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
     * The promise is removed form the map when the result arrives or upon timeout.
     *
     * @param msg is the kafka message payload as Json
     */
    private def processCompletion(response: Either[ActivationId, WhiskActivation], tid: TransactionId, forced: Boolean): Unit = {
        val aid = response.fold(l => l, r => r.activationId)
        activationById.remove(aid) match {
            case Some(entry @ ActivationEntry(_, namespaceId, invokerIndex, _, p)) =>
                logging.info(this, s"${if (!forced) "received" else "forced"} active ack for '$aid'")(tid)
                activationByInvoker.getOrElseUpdate(invokerIndex, new TrieSet[ActivationEntry]).remove(entry)
                activationByNamespaceId.getOrElseUpdate(namespaceId, new TrieSet[ActivationEntry]).remove(entry)
                if (!forced) {
                    p.trySuccess(response)
                } else {
                    p.tryFailure(new Throwable("no active ack received"))
                }

            case None =>
                // the entry was already removed
                logging.debug(this, s"received active ack for '$aid' which has no entry")(tid)
        }
    }

    /**
     * Creates an activation entry and insert into various maps.
     */
    private def setupActivation(action: ExecutableWhiskAction, activationId: ActivationId, namespaceId: UUID, invokerName: String, transid: TransactionId): ActivationEntry = {
        // either create a new promise or reuse a previous one for this activation if it exists
        val timeout = action.limits.timeout.duration + activeAckTimeoutGrace
        val entry = activationById.getOrElseUpdate(activationId, {
            // Install a timeout handler for the catastrophic case where an active ack is not received at all
            // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
            // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
            // in this case, if the activation handler is still registered, remove it and update the books.
            actorSystem.scheduler.scheduleOnce(timeout) {
                processCompletion(Left(activationId), transid, forced = true)
            }

            ActivationEntry(activationId, namespaceId, invokerName, Instant.now(Clock.systemUTC()), Promise[Either[ActivationId, WhiskActivation]]())
        })

        // add the entry to our maps, for bookkeeping
        activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry]).put(entry, {})
        activationByNamespaceId.getOrElseUpdate(namespaceId, new TrieSet[ActivationEntry]).put(entry, {})
        entry
    }

    /**
     * When invoker health detects a new invoker has come up, this callback is called.
     */
    private def clearInvokerState(invokerName: String) = {
        val actSet = activationByInvoker.getOrElseUpdate(invokerName, new TrieSet[ActivationEntry])
        actSet.keySet map {
            case actEntry @ ActivationEntry(activationId, namespaceId, invokerIndex, _, promise) =>
                promise.tryFailure(new LoadBalancerException(s"Invoker $invokerIndex restarted"))
                activationById.remove(activationId)
                activationByNamespaceId.get(namespaceId) map { _.remove(actEntry) }
        }
        actSet.clear()
    }

    /** Make a new immutable map so caller cannot mess up the state */
    private def getActiveCountByInvoker(): Map[String, Int] = activationByInvoker.toMap mapValues { _.size }

    /**
     * Creates or updates a health test action by updating the entity store.
     * This method is intended for use on startup.
     * @return Future that completes successfully iff the action is added to the database
     */
    private def createTestActionForInvokerHealth(db: EntityStore, action: WhiskAction): Future[Unit] = {
        implicit val tid = TransactionId.loadbalancer
        WhiskAction.get(db, action.docid).flatMap { oldAction =>
            WhiskAction.put(db, action.revision(oldAction.rev))
        }.recover {
            case _: NoDocumentException => WhiskAction.put(db, action)
        }.map(_ => {}).andThen {
            case Success(_) => logging.info(this, "test action for invoker health now exists")
            case Failure(e) => logging.error(this, s"error creating test action for invoker health: $e")
        }
    }

    /** Gets a producer which can publish messages to the kafka bus. */
    private val messageProducer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    private def sendActivationToInvoker(producer: MessageProducer, msg: ActivationMessage, invokerName: String): Future[RecordMetadata] = {
        implicit val transid = msg.transid
        val start = transid.started(this, LoggingMarkers.CONTROLLER_KAFKA, s"posting topic '$invokerName' with activation id '${msg.activationId}'")

        producer.send(invokerName, msg).andThen {
            case Success(status) => transid.finished(this, start, s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
            case Failure(e)      => transid.failed(this, start, s"error on posting to topic $invokerName")
        }
    }

    private val invokerPool = {
        // Do not create the invokerPool if it is not possible to create the health test action to recover the invokers.
        InvokerPool.healthAction(instance).map {
            // Await the creation of the test action; on failure, this will abort the constructor which should
            // in turn abort the startup of the controller.
            a => Await.result(createTestActionForInvokerHealth(entityStore, a), 1.minute)
        }.orElse {
            throw new IllegalStateException("cannot create test action for invoker health because runtime manifest is not valid")
        }

        val maxPingsPerPoll = 128
        val consul = new ConsulClient(config.consulServer)
        // Each controller gets its own Group Id, to receive all messages
        val pingConsumer = new KafkaConsumerConnector(config.kafkaHost, s"health${instance.toInt}", "health", maxPeek = maxPingsPerPoll)
        val invokerFactory = (f: ActorRefFactory, name: String) => f.actorOf(InvokerActor.props(instance), name)

        actorSystem.actorOf(InvokerPool.props(invokerFactory, consul.kv, invoker => {
            clearInvokerState(invoker)
            logging.info(this, s"cleared load balancer state for $invoker")(TransactionId.invokerHealth)
        }, (m, i) => sendActivationToInvoker(messageProducer, m, i), pingConsumer))
    }

    /**
     * Subscribes to active acks (completion messages from the invokers), and
     * registers a handler for received active acks from invokers.
     */
    val maxActiveAcksPerPoll = 128
    val activeAckPollDuration = 1.second

    private val activeAckConsumer = new KafkaConsumerConnector(config.kafkaHost, "completions", s"completed${instance.toInt}", maxPeek = maxActiveAcksPerPoll)
    val activationFeed = actorSystem.actorOf(Props {
        new MessageFeed("activeack", logging, activeAckConsumer, maxActiveAcksPerPoll, activeAckPollDuration, processActiveAck)
    })

    def processActiveAck(bytes: Array[Byte]): Future[Unit] = Future {
        val raw = new String(bytes, StandardCharsets.UTF_8)
        CompletionMessage.parse(raw) match {
            case Success(m: CompletionMessage) =>
                processCompletion(m.response, m.transid, false)
                // treat left as success (as it is the result a the message exceeding the bus limit)
                val isSuccess = m.response.fold(l => true, r => !r.response.isWhiskError)
                activationFeed ! MessageFeed.Processed
                invokerPool ! InvocationFinishedMessage(m.invoker, isSuccess)

            case Failure(t) =>
                activationFeed ! MessageFeed.Processed
                logging.error(this, s"failed processing message: $raw with $t")
        }
    }

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
    private def chooseInvoker(action: ExecutableWhiskAction, msg: ActivationMessage): Future[String] = {
        val invokers = if (action.exec.pull) blackboxInvokers else managedInvokers
        val hash = hashSubjectAction(msg)

        invokers.flatMap { invokers =>
            LoadBalancerService.schedule(
                invokers,
                activationByInvoker.mapValues(_.size),
                config.loadbalancerInvokerBusyThreshold,
                hash) match {
                    case Some(invoker) => Future.successful(invoker)
                    case None =>
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
    private def hashSubjectAction(msg: ActivationMessage): Int = {
        val subject = msg.user.subject.asString
        val path = msg.action.toString
        (subject.hashCode() ^ path.hashCode()).abs
    }
}

object LoadBalancerService {
    def requiredProperties = kafkaHost ++ consulServer ++
        Map(loadbalancerInvokerBusyThreshold -> null)

    /** Memoizes the result of `f` for later use. */
    def memoize[I, O](f: I => O): I => O = new scala.collection.mutable.HashMap[I, O]() {
        override def apply(key: I) = getOrElseUpdate(key, f(key))
    }

    /** Euclidean algorithm to determine the greatest-common-divisor */
    @tailrec
    def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Returns pairwise coprime numbers until x. Result is memoized. */
    val pairwiseCoprimeNumbersUntil: Int => IndexedSeq[Int] = LoadBalancerService.memoize {
        case x =>
            (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
                if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
                    primes :+ cur
                } else primes
            })
    }

    /**
     * Scans through all invokers and searches for an invoker, that has a queue length
     * below the defined threshold. The threshold is subject to a 3 times back off. Iff
     * no "underloaded" invoker was found it will default to the home invoker.
     *
     * @param availableInvokers a list of available (healthy) invokers to search in
     * @param activationsPerInvoker a map of the number of outstanding activations per invoker
     * @param invokerBusyThreshold defines when an invoker is considered overloaded
     * @param hash stable identifier of the entity to be scheduled
     * @return an invoker to schedule to or None of no invoker is available
     */
    def schedule[A](
        availableInvokers: Seq[A],
        activationsPerInvoker: collection.Map[A, Int],
        invokerBusyThreshold: Int,
        hash: Int): Option[A] = {

        val numInvokers = availableInvokers.length
        if (numInvokers > 0) {
            val homeInvoker = hash % numInvokers

            val stepSizes = LoadBalancerService.pairwiseCoprimeNumbersUntil(numInvokers)
            val step = stepSizes(hash % stepSizes.size)

            @tailrec
            def search(targetInvoker: Int, iteration: Int = 1): A = {
                // map the computed index to the actual invoker index
                val invokerName = availableInvokers(targetInvoker)

                // send the request to the target invoker if it has capacity...
                if (activationsPerInvoker.get(invokerName).getOrElse(0) < invokerBusyThreshold * iteration) {
                    invokerName
                } else {
                    // ... otherwise look for a less loaded invoker by stepping through a pre-computed
                    // list of invokers; there are two possible outcomes:
                    // 1. the search lands on a new invoker that has capacity, choose it
                    // 2. walked through the entire list and found no better invoker than the
                    //    "home invoker", force the home invoker
                    val newTarget = (targetInvoker + step) % numInvokers
                    if (newTarget == homeInvoker) {
                        if (iteration < 3) {
                            search(newTarget, iteration + 1)
                        } else {
                            availableInvokers(homeInvoker)
                        }
                    } else {
                        search(newTarget, iteration)
                    }
                }
            }

            Some(search(homeInvoker))
        } else {
            None
        }
    }

}

private case class LoadBalancerException(msg: String) extends Throwable(msg)
