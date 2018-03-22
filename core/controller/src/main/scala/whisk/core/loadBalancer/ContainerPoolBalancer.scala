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
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Cancellable
import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import akka.pattern.ask
import akka.cluster.Cluster
import akka.stream.ActorMaterializer
import org.apache.kafka.clients.producer.RecordMetadata
import pureconfig._
import java.util.concurrent.atomic.AtomicBoolean
import whisk.common.{Logging, LoggingMarkers, TransactionId}
import whisk.core.WhiskConfig._
import whisk.core.connector._
import whisk.core.entity._
import whisk.core.{ConfigKeys, WhiskConfig}
import whisk.core.entity.types.EntityStore
import whisk.spi.SpiLoader
import pureconfig._
import scala.collection.mutable
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

case class LoadbalancerConfig(blackboxFraction: Double, invokerBusyThreshold: Int)

class ContainerPoolBalancer(config: WhiskConfig, controllerInstance: InstanceId)(implicit val actorSystem: ActorSystem, logging: Logging, materializer: ActorMaterializer)
    extends LoadBalancer {

  private val lbConfig = loadConfigOrThrow[LoadbalancerConfig](ConfigKeys.loadbalancer)

  /** The execution context for futures */
  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  private val activeAckTimeoutGrace = 1.minute

  var allInvokersLocal = IndexedSeq[InvokerHealth]()
  var localOverflowActivationCount: Int = 0
  val overflowState = new AtomicBoolean(false)

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
      new DistributedLoadBalancerData(controllerInstance)
    }
  }
  
  val messagingProvider = SpiLoader.get[MessagingProvider]
  private val messageProducer = messagingProvider.getProducer(config)

  val maxOverflowMsgPerPoll = config.loadbalancerInvokerBusyThreshold //TODO: only pull enough messages that can be processed immediately
  val overflowConsumer =
    messagingProvider.getConsumer(config, "overflow", s"overflow", maxPeek = maxOverflowMsgPerPoll)


    /** Gets the number of in-flight activations for a specific user. */
  override def activeActivationsFor(namespace: UUID) = Future.successful(0)

  /** Gets the number of in-flight activations in the system. */
  override def totalActiveActivations = Future.successful(0)

  /** An indexed sequence of all invokers in the current system. */
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = {
    invokerPool
      .ask(GetStatus)(Timeout(5.seconds))
      .mapTo[IndexedSeq[InvokerHealth]]
  }

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
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {
    /*
    chooseInvoker(msg.user, action).flatMap { invokerName =>
      val entry = setupActivation(action, msg.activationId, msg.user.uuid, invokerName, transid)
      sendActivationToInvoker(messageProducer, msg, invokerName).map { _ =>
        entry.promise.future
      }
    }*/
    val hash = generateHash(msg.user.namespace, action)
    if (!overflowState.get()) {
      sendToInvokerOrOverflow(msg, action, hash, action.exec.pull)
    } else {
      sendActivationToOverflow(
        messageProducer,
        OverflowMessage(transid, msg, action.limits.timeout.duration.toSeconds.toInt, hash, action.exec.pull, controllerInstance))
        .flatMap { _ =>
          val entry = setupActivation(action.limits.timeout.duration, msg.activationId, msg.user.uuid, None, transid)
          entry.promise.future
        }
    }
  }

  private def sendToInvokerOrOverflow(msg: ActivationMessage, action: ExecutableWhiskActionMetaData, hash: Int, pull: Boolean)(
    implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {
    val invMatched = chooseInvoker(hash, pull, false)
    val entry = setupActivation(action.limits.timeout.duration, msg.activationId, msg.user.uuid, invMatched, transid)

    invMatched match {
      case Some(i) =>
        ContainerPoolBalancer.sendActivationToInvoker(messageProducer, msg, i).flatMap { _ =>
          entry.promise.future
        }
      case None =>
        if (overflowState.compareAndSet(false, true)) {
          logging.info(this, "entering overflow state; no invokers have capacity")
        }

        sendActivationToOverflow(
          messageProducer,
          OverflowMessage(transid, msg, action.limits.timeout.duration.toSeconds.toInt, hash, pull, controllerInstance)).flatMap {
          _ =>
            entry.promise.future
        }
    }
  }

  private def sendActivationToOverflow(producer: MessageProducer, msg: OverflowMessage): Future[RecordMetadata] = {
    implicit val transid = msg.transid

    val topic = "overflow"
    val start = transid.started(
      this,
      LoggingMarkers.CONTROLLER_KAFKA,
      s"posting overflow topic '$topic' with activation id '${msg.msg.activationId}'")

    producer.send(topic, msg).andThen {
      case Success(status) =>
        localOverflowActivationCount += 1
        transid.finished(this, start, s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]")
      case Failure(e) => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  override def clusterSize = config.controllerInstances.toInt

  /**
   * Tries to fill in the result slot (i.e., complete the promise) when a completion message arrives.
   * The promise is removed form the map when the result arrives or upon timeout.
   *
   * @param msg is the kafka message payload as Json
   */
  private def processCompletion(response: Either[ActivationId, WhiskActivation],
                                tid: TransactionId,
                                forced: Boolean,
                                invoker: Option[InstanceId]): Unit = {
    val aid = response.fold(l => l, r => r.activationId)

    // treat left as success (as it is the result of a message exceeding the bus limit)
    val isSuccess = response.fold(l => true, r => !r.response.isWhiskError)

    loadBalancerData.removeActivation(aid) match {
      case Some(entry) =>
        //cancel the scheduled timeout handler
        timeouts.remove(aid).foreach(_.cancel())
        logging.info(this, s"${if (!forced) "received" else "forced"} active ack for '$aid'")(tid)
        // Active acks that are received here are strictly from user actions - health actions are not part of
        // the load balancer's activation map. Inform the invoker pool supervisor of the user action completion.
        
        // If the active ack was forced, because the waiting period expired, treat it as a failed activation.
        // A cluster of such failures will eventually turn the invoker unhealthy and suspend queuing activations
        // to that invoker topic.
        entry.invokerName.foreach(invokerInstance => {
          invokerPool ! InvocationFinishedMessage(invokerInstance, isSuccess && !forced)
          //if processing overflow that initiated elsewhere, propagate the completion
          entry.originalController.foreach(controllerInstance => {
            val msg = CompletionMessage(tid, response, invokerInstance)
            messageProducer.send(s"completed${controllerInstance.toInt}", msg)
          })
        })

        //if this is an entry for processing overflow, adjust overflow state if needed
        if (entry.isOverflow) {
          localOverflowActivationCount -= 1
          if (overflowState.get() && localOverflowActivationCount == 0 && overflowState.compareAndSet(true, false)) {
            logging.info(this, "removing overflow state after processing outstanding overflow messages")
          }
        }
        if (!forced) {
          entry.promise.trySuccess(response)
        } else {
          entry.promise.tryFailure(new Throwable("no active ack received"))
        }
      case None if !forced =>
        // the entry has already been removed but we receive an active ack for this activation Id.
        // This happens for health actions, because they don't have an entry in Loadbalancerdata or
        // for activations that already timed out.
        
        invoker.foreach(invokerPool ! InvocationFinishedMessage(_, isSuccess))
        //invokerPool ! InvocationFinishedMessage(invoker, isSuccess)
        logging.debug(this, s"received active ack for '$aid' which has no entry")(tid)
      case None =>
        // the entry has already been removed by an active ack. This part of the code is reached by the timeout.
        // As the active ack is already processed we don't have to do anything here.
        logging.debug(this, s"forced active ack for '$aid' which has no entry")(tid)
    }
  }

  val timeouts = mutable.Map[ActivationId, Cancellable]()

  /**
   * Creates an activation entry and insert into various maps.
   */
  private def setupActivation(actionTimeout: FiniteDuration,
                              activationId: ActivationId,
                              namespaceId: UUID,
                              invokerName: Option[InstanceId],
                              transid: TransactionId,
                              originalController: Option[InstanceId] = None,
                              isOverflow: Boolean = false): ActivationEntry = {
    val timeout = actionTimeout + activeAckTimeoutGrace
    // Install a timeout handler for the catastrophic case where an active ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    // in case of missing synchronization between n controllers in HA configuration the invoker queue can be overloaded
    // n-1 times and the maximal time for answering with active ack can be n times the action time (plus some overhead)
    loadBalancerData.putActivation(
      activationId, {
        timeouts.put(activationId, actorSystem.scheduler.scheduleOnce(timeout) {
          processCompletion(Left(activationId), transid, forced = true, invokerName)
        })

        ActivationEntry(
          activationId,
          namespaceId,
          invokerName,
          Promise[Either[ActivationId, WhiskActivation]],
          originalController,
          isOverflow)
      },
      isOverflow)
  }

  /** Gets a producer which can publish messages to the kafka bus. */
  val maxPingsPerPoll = 128
  val healthConsumer = messagingProvider.getConsumer(config, s"health${controllerInstance.toInt}", "health", maxPeek = maxPingsPerPoll)


  private val invokerPool = ContainerPoolBalancer.createInvokerPool(controllerInstance, actorSystem.dispatcher, 
    actorSystem, WhiskEntityStore.datastore(config), messageProducer, healthConsumer)

  /**
   * Subscribes to active acks (completion messages from the invokers), and
   * registers a handler for received active acks from invokers.
   */
  val activeAckTopic = s"completed${controllerInstance.toInt}"
  val maxActiveAcksPerPoll = 128
  val activeAckPollDuration = 1.second
  private val activeAckConsumer = messagingProvider.getConsumer(config, activeAckTopic, activeAckTopic, maxPeek = maxActiveAcksPerPoll)

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
        processCompletion(m.response, m.transid, forced = false, invoker = Some(m.invoker))
        activationFeed ! MessageFeed.Processed

      case Failure(t) =>
        activationFeed ! MessageFeed.Processed
        logging.error(this, s"failed processing message: $raw with $t")
    }
  }

  // //TODO: only pull enough messages that can be processed immediately
  val overflowPollDuration = 200.milliseconds

  val offsetMonitor = actorSystem.actorOf(Props {
    new Actor {
      override def receive = {
        case MessageFeed.MaxOffset =>
          if (overflowState.compareAndSet(true, false)) {
            logging.info(this, "resetting overflow state via offsetMonitor for overflow topic")
          }
      }
    }
  })

  //ideally the overflow capacity should be dynamic, based on free invokers, to provide some backpressure. For now, capacity of 1
  //(or some small number less than number of invokers) may be ok.
  val overflowHandlerCapacity = overflowConsumer.maxPeek
  val overflowFeed = actorSystem.actorOf(Props {
    new MessageFeed(
      "overflow",
      logging,
      overflowConsumer,
      overflowHandlerCapacity,
      overflowPollDuration,
      processOverflow,
      offsetMonitor = Some(offsetMonitor))
  })

  private def processOverflow(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    OverflowMessage.parse(raw) match {
      case Success(m: OverflowMessage) =>
        implicit val tid = m.msg.transid
        logging.info(this, s"processing overflow msg for activation ${m.msg.activationId}")
        //remove from entries (will replace with an overflow entry if it exists locally)
        val entryOption = loadBalancerData
          .removeActivation(m.msg.activationId)

        //process the activation request: update the invoker ref, and send to invoker
        chooseInvoker(m.hash, m.pull, true) match {
          case Some(instanceId) =>
            //Update the invoker name for the overflow ActivationEntry
            //The timeout for the activationId will still be effective.
            entryOption match {
              case Some(entry) =>
                entry.invokerName = Some(instanceId)
                loadBalancerData.putActivation(m.msg.activationId, entry, false)
                ContainerPoolBalancer.sendActivationToInvoker(messageProducer, m.msg, instanceId)
              case None =>
                //TODO: adjust the timeout for time spent in overflow topic!
                val entry = setupActivation(
                  m.actionTimeoutSeconds.seconds,
                  m.msg.activationId,
                  m.msg.user.uuid,
                  Some(instanceId),
                  m.msg.transid,
                  Some(m.originalController),
                  true)
                loadBalancerData.putActivation(m.msg.activationId, entry, true)
                val updatedMsg = m.msg.copy(rootControllerIndex = this.controllerInstance)
                ContainerPoolBalancer.sendActivationToInvoker(messageProducer, updatedMsg, instanceId)
            }

          case None =>
            //if no invokers available, all activations will go to overflow queue till capacity is available again
            logging.error(this, "invalid overflow processing; no invokers have capacity")
          //TODO: should requeue to overflow?
        }
        overflowFeed ! MessageFeed.Processed

      case Failure(t) =>
        logging.error(this, s"failed processing overflow message: $raw with $t")
    }
  }

  /** Compute the number of blackbox-dedicated invokers by applying a rounded down fraction of all invokers (but at least 1). */
  private def numBlackbox(totalInvokers: Int) = Math.max(1, (totalInvokers.toDouble * blackboxFraction).toInt)

  /** Return invokers dedicated to running blackbox actions. */
  private def blackboxInvokers(invokers: IndexedSeq[InvokerHealth]): IndexedSeq[InvokerHealth] = {
    val blackboxes = numBlackbox(invokers.size)
    invokers.takeRight(blackboxes)
  }

  /**
   * Return (at least one) invokers for running non black-box actions.
   * This set can overlap with the blackbox set if there is only one invoker.
   */
  private def managedInvokers(invokers: IndexedSeq[InvokerHealth]): IndexedSeq[InvokerHealth] = {
    val managed = Math.max(1, invokers.length - numBlackbox(invokers.length))
    invokers.take(managed)
  }

  /** Determine which invoker this activation should go to. Due to dynamic conditions, it may return no invoker. */
  private def chooseInvoker(hash: Int, pull: Boolean, overflow: Boolean): Option[InstanceId] = {
    
    val invokersToUse = if (pull) blackboxInvokers(allInvokersLocal) else managedInvokers(allInvokersLocal)
    val currentActivations = loadBalancerData.activationCountPerInvoker
    val invokersWithUsage = invokersToUse.view.map {
      // Using a view defers the comparably expensive lookup to actual access of the element
      case invoker => (invoker.id, invoker.status, currentActivations.getOrElse(invoker.id.toString, 0))
    }
    ContainerPoolBalancer.schedule(invokersWithUsage, config.loadbalancerInvokerBusyThreshold, hash, overflow)
  }

  /** Generates a hash based on the string representation of namespace and action */
  private def generateHash(namespace: EntityName, action: ExecutableWhiskActionMetaData): Int = {
    (namespace.asString.hashCode() ^ action.fullyQualifiedName(false).asString.hashCode()).abs
  }
}

object ContainerPoolBalancer extends LoadBalancerProvider {

  override def loadBalancer(whiskConfig: WhiskConfig, instance: InstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = new ContainerPoolBalancer(whiskConfig, instance)

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
  val pairwiseCoprimeNumbersUntil: Int => IndexedSeq[Int] = ContainerPoolBalancer.memoize {
    case x =>
      (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
        if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
          primes :+ cur
        } else primes
      })
  }

  /**
   * Scans through all invokers and searches for an invoker, that has a queue length
   * below the defined threshold. Iff no "underloaded" invoker was found, return None.
   *
   * @param invokers a list of available invokers to search in, including their state and usage
   * @param invokerBusyThreshold defines when an invoker is considered overloaded
   * @param hash stable identifier of the entity to be scheduled
   * @return an invoker to schedule to or None of no invoker is available
   */
  def schedule(invokers: Seq[(InstanceId, InvokerState, Int)], invokerBusyThreshold: Int, hash: Int, overflow: Boolean)(
    implicit logging: Logging): Option[InstanceId] = {

    require(invokerBusyThreshold > 0, "invokerBusyThreshold should be > 0")
    
    val numInvokers = invokers.size
    if (numInvokers > 0) {
      val homeInvoker = hash % numInvokers
      val stepSizes = ContainerPoolBalancer.pairwiseCoprimeNumbersUntil(numInvokers)
      val step = stepSizes(hash % stepSizes.size)

      val invokerProgression = Stream
        .from(0)
        .take(numInvokers)
        .map(i => (homeInvoker + i * step) % numInvokers)
        .map(invokers)
        .filter(_._2 == Healthy)

      if (overflow) {
        //should not arrive here without an invoker who is not busy! but just in case, use the step progression with incrementing busy-ness
        invokerProgression
          .find(_._3 < invokerBusyThreshold)
          .orElse({
            logging.warn(this, "scheduling to a busy invoker during overflow processing")
            invokerProgression.find(_._3 < invokerBusyThreshold * 2)
          })
          .orElse(invokerProgression.find(_._3 < invokerBusyThreshold * 3))
          .orElse(invokerProgression.headOption)
          .map(_._1)
      } else {
        invokerProgression
          .find(_._3 < invokerBusyThreshold)
          //don't consider invokers that have reached capacity when not in overflow state
          .map(_._1)
      }
    } else {
      logging.warn(this, "no invokers available")
      None
    }
  }

  def sendActivationToInvoker(producer: MessageProducer, msg: ActivationMessage, invoker: InstanceId)(
    implicit logging: Logging,
    ec: ExecutionContext): Future[RecordMetadata] = {
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

  def createInvokerPool(instance: InstanceId,
                        executionContext: ExecutionContext,
                        actorSystem: ActorSystem,
                        entityStore: EntityStore,
                        messageProducer: MessageProducer,
                        healthConsumer: MessageConsumer)(implicit logging: Logging): ActorRef = {
    // Do not create the invokerPool if it is not possible to create the health test action to recover the invokers.
    implicit val ec: ExecutionContext = executionContext

    InvokerPool.prepare(instance, entityStore)

    val invokerFactory =
      (f: ActorRefFactory, invokerInstance: InstanceId) => f.actorOf(InvokerActor.props(invokerInstance, instance))

    actorSystem.actorOf(
      InvokerPool
        .props(
          invokerFactory,
          (m, i) => ContainerPoolBalancer.sendActivationToInvoker(messageProducer, m, i),
          healthConsumer))
  }


}

private case class LoadBalancerException(msg: String) extends Throwable(msg)
