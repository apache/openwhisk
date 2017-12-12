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

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import org.apache.kafka.clients.producer.RecordMetadata
import akka.actor.ActorRefFactory
import akka.actor.ActorSystem
import akka.actor.Props
import akka.cluster.Cluster
import akka.util.Timeout
import akka.pattern.ask
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.connector.{ActivationMessage, CompletionMessage}
import whisk.core.connector.MessageFeed
import whisk.core.connector.MessageProducer
import whisk.core.connector.MessagingProvider
import whisk.core.database.NoDocumentException
import whisk.core.entity._
import whisk.core.entity.{ActivationId, WhiskActivation}
import whisk.core.entity.EntityName
import whisk.core.entity.ExecutableWhiskActionMetaData
import whisk.core.entity.Identity
import whisk.core.entity.InstanceId
import whisk.core.entity.UUID
import whisk.core.entity.WhiskAction
import whisk.core.entity.types.EntityStore
import whisk.spi.SpiLoader
import pureconfig._

case class LoadbalancerConfig(blackboxFraction: Double, invokerBusyThreshold: Int)

trait LoadBalancer {

  val activeAckTimeoutGrace = 1.minute

  /** Gets the number of in-flight activations for a specific user. */
  def activeActivationsFor(namespace: UUID): Future[Int]

  /** Gets the number of in-flight activations in the system. */
  def totalActiveActivations: Future[Int]

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
  def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]]

}

class LoadBalancerService(config: WhiskConfig, instance: InstanceId, entityStore: EntityStore)(
  implicit val actorSystem: ActorSystem,
  logging: Logging)
    extends LoadBalancer {

  private val lbConfig = loadConfigOrThrow[LoadbalancerConfig]("whisk.loadbalancer")

  /** The execution context for futures */
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  /** How many invokers are dedicated to blackbox images.  We range bound to something sensical regardless of configuration. */
  private val blackboxFraction: Double = Math.max(0.0, Math.min(1.0, lbConfig.blackboxFraction))
  logging.info(this, s"blackboxFraction = $blackboxFraction")(TransactionId.loadbalancer)

  /** Feature switch for shared load balancer data **/
  private val loadBalancerData = {
    if (config.controllerLocalBookkeeping) {
      new LocalLoadBalancerData()
    } else {

      /** Specify how seed nodes are generated */
      val seedNodesProvider = new StaticSeedNodesProvider(config.controllerSeedNodes, actorSystem.name)
      Cluster(actorSystem).joinSeedNodes(seedNodesProvider.getSeedNodes())
      new DistributedLoadBalancerData()
    }
  }

  override def activeActivationsFor(namespace: UUID) = loadBalancerData.activationCountOn(namespace)

  override def totalActiveActivations = loadBalancerData.totalActivationCount

  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {
    chooseInvoker(msg.user, action).flatMap { invokerName =>
      val entry = setupActivation(action, msg.activationId, msg.user.uuid, invokerName, transid)
      sendActivationToInvoker(messageProducer, msg, invokerName).map { _ =>
        entry.promise.future
      }
    }
  }

  /** An indexed sequence of all invokers in the current system */
  def allInvokers: Future[IndexedSeq[(InstanceId, InvokerState)]] =
    invokerPool
      .ask(GetStatus)(Timeout(5.seconds))
      .mapTo[IndexedSeq[(InstanceId, InvokerState)]]

  /**
   * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
   * The promise is removed form the map when the result arrives or upon timeout.
   *
   * @param msg is the kafka message payload as Json
   */
  private def processCompletion(response: Either[ActivationId, WhiskActivation],
                                tid: TransactionId,
                                forced: Boolean,
                                invoker: InstanceId): Unit = {
    val aid = response.fold(l => l, r => r.activationId)

    // treat left as success (as it is the result of a message exceeding the bus limit)
    val isSuccess = response.fold(l => true, r => !r.response.isWhiskError)

    loadBalancerData.removeActivation(aid) match {
      case Some(entry) =>
        logging.info(this, s"${if (!forced) "received" else "forced"} active ack for '$aid'")(tid)
        // Active acks that are received here are strictly from user actions - health actions are not part of
        // the load balancer's activation map. Inform the invoker pool supervisor of the user action completion.
        invokerPool ! InvocationFinishedMessage(invoker, isSuccess)
        if (!forced) {
          entry.promise.trySuccess(response)
        } else {
          entry.promise.tryFailure(new Throwable("no active ack received"))
        }
      case None if !forced =>
        // the entry has already been removed but we receive an active ack for this activation Id.
        // This happens for health actions, because they don't have an entry in Loadbalancerdata or
        // for activations that already timed out.
        invokerPool ! InvocationFinishedMessage(invoker, isSuccess)
        logging.debug(this, s"received active ack for '$aid' which has no entry")(tid)
      case None =>
        // the entry has already been removed by an active ack. This part of the code is reached by the timeout.
        // As the active ack is already processed we don't have to do anything here.
        logging.debug(this, s"forced active ack for '$aid' which has no entry")(tid)
    }
  }

  /**
   * Creates an activation entry and insert into various maps.
   */
  private def setupActivation(action: ExecutableWhiskActionMetaData,
                              activationId: ActivationId,
                              namespaceId: UUID,
                              invokerName: InstanceId,
                              transid: TransactionId): ActivationEntry = {
    val timeout = (action.limits.timeout.duration
      .max(TimeLimit.STD_DURATION) * config.controllerInstances.toInt) + activeAckTimeoutGrace
    // Install a timeout handler for the catastrophic case where an active ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    // in case of missing synchronization between n controllers in HA configuration the invoker queue can be overloaded
    // n-1 times and the maximal time for answering with active ack can be n times the action time (plus some overhead)
    loadBalancerData.putActivation(activationId, {
      actorSystem.scheduler.scheduleOnce(timeout) {
        processCompletion(Left(activationId), transid, forced = true, invoker = invokerName)
      }

      ActivationEntry(activationId, namespaceId, invokerName, Promise[Either[ActivationId, WhiskActivation]]())
    })
  }

  /**
   * Creates or updates a health test action by updating the entity store.
   * This method is intended for use on startup.
   * @return Future that completes successfully iff the action is added to the database
   */
  private def createTestActionForInvokerHealth(db: EntityStore, action: WhiskAction): Future[Unit] = {
    implicit val tid = TransactionId.loadbalancer
    WhiskAction
      .get(db, action.docid)
      .flatMap { oldAction =>
        WhiskAction.put(db, action.revision(oldAction.rev))(tid, notifier = None)
      }
      .recover {
        case _: NoDocumentException => WhiskAction.put(db, action)(tid, notifier = None)
      }
      .map(_ => {})
      .andThen {
        case Success(_) => logging.info(this, "test action for invoker health now exists")
        case Failure(e) => logging.error(this, s"error creating test action for invoker health: $e")
      }
  }

  /** Gets a producer which can publish messages to the kafka bus. */
  private val messagingProvider = SpiLoader.get[MessagingProvider]
  private val messageProducer = messagingProvider.getProducer(config, executionContext)

  private def sendActivationToInvoker(producer: MessageProducer,
                                      msg: ActivationMessage,
                                      invoker: InstanceId): Future[RecordMetadata] = {
    implicit val transid = msg.transid

    val topic = s"invoker${invoker.toInt}"
    val start = transid.started(
      this,
      LoggingMarkers.CONTROLLER_KAFKA,
      s"posting topic '$topic' with activation id '${msg.activationId}'")

    producer.send(topic, msg).andThen {
      case Success(status) =>
        transid.finished(this, start, s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
      case Failure(e) => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }
  private val invokerPool = {
    // Do not create the invokerPool if it is not possible to create the health test action to recover the invokers.
    InvokerPool
      .healthAction(instance)
      .map {
        // Await the creation of the test action; on failure, this will abort the constructor which should
        // in turn abort the startup of the controller.
        a =>
          Await.result(createTestActionForInvokerHealth(entityStore, a), 1.minute)
      }
      .orElse {
        throw new IllegalStateException(
          "cannot create test action for invoker health because runtime manifest is not valid")
      }

    val maxPingsPerPoll = 128
    val pingConsumer =
      messagingProvider.getConsumer(config, s"health${instance.toInt}", "health", maxPeek = maxPingsPerPoll)
    val invokerFactory = (f: ActorRefFactory, invokerInstance: InstanceId) =>
      f.actorOf(InvokerActor.props(invokerInstance, instance))

    actorSystem.actorOf(
      InvokerPool.props(invokerFactory, (m, i) => sendActivationToInvoker(messageProducer, m, i), pingConsumer))
  }

  /**
   * Subscribes to active acks (completion messages from the invokers), and
   * registers a handler for received active acks from invokers.
   */
  val maxActiveAcksPerPoll = 128
  val activeAckPollDuration = 1.second
  private val activeAckConsumer =
    messagingProvider.getConsumer(config, "completions", s"completed${instance.toInt}", maxPeek = maxActiveAcksPerPoll)
  val activationFeed = actorSystem.actorOf(Props {
    new MessageFeed(
      "activeack",
      logging,
      activeAckConsumer,
      maxActiveAcksPerPoll,
      activeAckPollDuration,
      processActiveAck)
  })

  def processActiveAck(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    CompletionMessage.parse(raw) match {
      case Success(m: CompletionMessage) =>
        processCompletion(m.response, m.transid, forced = false, invoker = m.invoker)
        activationFeed ! MessageFeed.Processed

      case Failure(t) =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw with $t")
    }
  }

  /** Compute the number of blackbox-dedicated invokers by applying a rounded down fraction of all invokers (but at least 1). */
  private def numBlackbox(totalInvokers: Int) = Math.max(1, (totalInvokers.toDouble * blackboxFraction).toInt)

  /** Return invokers (almost) dedicated to running blackbox actions. */
  private def blackboxInvokers(
    invokers: IndexedSeq[(InstanceId, InvokerState)]): IndexedSeq[(InstanceId, InvokerState)] = {
    val blackboxes = numBlackbox(invokers.size)
    invokers.takeRight(blackboxes)
  }

  /**
   * Return (at least one) invokers for running non black-box actions.
   * This set can overlap with the blackbox set if there is only one invoker.
   */
  private def managedInvokers(
    invokers: IndexedSeq[(InstanceId, InvokerState)]): IndexedSeq[(InstanceId, InvokerState)] = {
    val managed = Math.max(1, invokers.length - numBlackbox(invokers.length))
    invokers.take(managed)
  }

  /** Determine which invoker this activation should go to. Due to dynamic conditions, it may return no invoker. */
  private def chooseInvoker(user: Identity, action: ExecutableWhiskActionMetaData): Future[InstanceId] = {
    val hash = generateHash(user.namespace, action)

    loadBalancerData.activationCountPerInvoker.flatMap { currentActivations =>
      allInvokers.flatMap { invokers =>
        val invokersToUse = if (action.exec.pull) blackboxInvokers(invokers) else managedInvokers(invokers)
        val invokersWithUsage = invokersToUse.view.map {
          // Using a view defers the comparably expensive lookup to actual access of the element
          case (instance, state) => (instance, state, currentActivations.getOrElse(instance.toString, 0))
        }

        LoadBalancerService.schedule(invokersWithUsage, lbConfig.invokerBusyThreshold, hash) match {
          case Some(invoker) => Future.successful(invoker)
          case None =>
            logging.error(this, s"all invokers down")(TransactionId.invokerHealth)
            Future.failed(new LoadBalancerException("no invokers available"))
        }
      }
    }
  }

  /** Generates a hash based on the string representation of namespace and action */
  private def generateHash(namespace: EntityName, action: ExecutableWhiskActionMetaData): Int = {
    (namespace.asString.hashCode() ^ action.fullyQualifiedName(false).asString.hashCode()).abs
  }
}

object LoadBalancerService {
  def requiredProperties =
    kafkaHosts ++
      Map(controllerLocalBookkeeping -> null, controllerSeedNodes -> null)

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
   * no "underloaded" invoker was found it will default to the first invoker in the
   * step-defined progression that is healthy.
   *
   * @param invokers a list of available invokers to search in, including their state and usage
   * @param invokerBusyThreshold defines when an invoker is considered overloaded
   * @param hash stable identifier of the entity to be scheduled
   * @return an invoker to schedule to or None of no invoker is available
   */
  def schedule(invokers: Seq[(InstanceId, InvokerState, Int)],
               invokerBusyThreshold: Int,
               hash: Int): Option[InstanceId] = {

    val numInvokers = invokers.size
    if (numInvokers > 0) {
      val homeInvoker = hash % numInvokers
      val stepSizes = LoadBalancerService.pairwiseCoprimeNumbersUntil(numInvokers)
      val step = stepSizes(hash % stepSizes.size)

      val invokerProgression = Stream
        .from(0)
        .take(numInvokers)
        .map(i => (homeInvoker + i * step) % numInvokers)
        .map(invokers)
        .filter(_._2 == Healthy)

      invokerProgression
        .find(_._3 < invokerBusyThreshold)
        .orElse(invokerProgression.find(_._3 < invokerBusyThreshold * 2))
        .orElse(invokerProgression.find(_._3 < invokerBusyThreshold * 3))
        .orElse(invokerProgression.headOption)
        .map(_._1)
    } else None
  }

}

private case class LoadBalancerException(msg: String) extends Throwable(msg)
