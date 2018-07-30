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
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.LongAdder

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.event.Logging.InfoLevel
import akka.management.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.stream.ActorMaterializer
import org.apache.kafka.clients.producer.RecordMetadata
import pureconfig._
import whisk.common.LoggingMarkers._
import whisk.common._
import whisk.core.WhiskConfig._
import whisk.core.connector._
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.core.{ConfigKeys, WhiskConfig}
import whisk.spi.SpiLoader

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * A loadbalancer that schedules workload based on a hashing-algorithm.
 *
 * ## Algorithm
 *
 * At first, for every namespace + action pair a hash is calculated and then an invoker is picked based on that hash
 * (`hash % numInvokers`). The determined index is the so called "home-invoker". This is the invoker where the following
 * progression will **always** start. If this invoker is healthy (see "Invoker health checking") and if there is
 * capacity on that invoker (see "Capacity checking"), the request is scheduled to it.
 *
 * If one of these prerequisites is not true, the index is incremented by a step-size. The step-sizes available are the
 * all coprime numbers smaller than the amount of invokers available (coprime, to minimize collisions while progressing
 * through the invokers). The step-size is picked by the same hash calculated above (`hash & numStepSizes`). The
 * home-invoker-index is now incremented by the step-size and the checks (healthy + capacity) are done on the invoker
 * we land on now.
 *
 * This procedure is repeated until all invokers have been checked at which point the "overload" strategy will be
 * employed, which is to choose a healthy invoker randomly. In a steadily running system, that overload means that there
 * is no capacity on any invoker left to schedule the current request to.
 *
 * If no invokers are available or if there are no healthy invokers in the system, the loadbalancer will return an error
 * stating that no invokers are available to take any work. Requests are not queued anywhere in this case.
 *
 * An example:
 * - availableInvokers: 10 (all healthy)
 * - hash: 13
 * - homeInvoker: hash % availableInvokers = 13 % 10 = 3
 * - stepSizes: 1, 3, 7 (note how 2 and 5 is not part of this because it's not coprime to 10)
 * - stepSizeIndex: hash % numStepSizes = 13 % 3 = 1 => stepSize = 3
 *
 * Progression to check the invokers: 3, 6, 9, 2, 5, 8, 1, 4, 7, 0 --> done
 *
 * This heuristic is based on the assumption, that the chance to get a warm container is the best on the home invoker
 * and degrades the more steps you make. The hashing makes sure that all loadbalancers in a cluster will always pick the
 * same home invoker and do the same progression for a given action.
 *
 * Known caveats:
 * - This assumption is not always true. For instance, two heavy workloads landing on the same invoker can override each
 *   other, which results in many cold starts due to all containers being evicted by the invoker to make space for the
 *   "other" workload respectively. Future work could be to keep a buffer of invokers last scheduled for each action and
 *   to prefer to pick that one. Then the second-last one and so forth.
 *
 * ## Capacity checking
 *
 * The maximum capacity per invoker is configured using `invoker-busy-threshold`, which is the maximum amount of actions
 * running in parallel on that invoker.
 *
 * Spare capacity is determined by what the loadbalancer thinks it scheduled to each invoker. Upon scheduling, an entry
 * is made to update the books and a slot in a Semaphore is taken. That slot is only released after the response from
 * the invoker (active-ack) arrives **or** after the active-ack times out. The Semaphore has as many slots as are
 * configured via `invoker-busy-threshold`.
 *
 * Known caveats:
 * - In an overload scenario, activations are queued directly to the invokers, which makes the active-ack timeout
 *   unpredictable. Timing out active-acks in that case can cause the loadbalancer to prematurely assign new load to an
 *   overloaded invoker, which can cause uneven queues.
 * - The same is true if an invoker is extraordinarily slow in processing activations. The queue on this invoker will
 *   slowly rise if it gets slow to the point of still sending pings, but handling the load so slowly, that the
 *   active-acks time out. The loadbalancer again will think there is capacity, when there is none.
 *
 * Both caveats could be solved in future work by not queueing to invoker topics on overload, but to queue on a
 * centralized overflow topic. Timing out an active-ack can then be seen as a system-error, as described in the
 * following.
 *
 * ## Invoker health checking
 *
 * Invoker health is determined via a kafka-based protocol, where each invoker pings the loadbalancer every second. If
 * no ping is seen for a defined amount of time, the invoker is considered "Offline".
 *
 * Moreover, results from all activations are inspected. If more than 3 out of the last 10 activations contained system
 * errors, the invoker is considered "Unhealthy". If an invoker is unhealty, no user workload is sent to it, but
 * test-actions are sent by the loadbalancer to check if system errors are still happening. If the
 * system-error-threshold-count in the last 10 activations falls below 3, the invoker is considered "Healthy" again.
 *
 * To summarize:
 * - "Offline": Ping missing for > 10 seconds
 * - "Unhealthy": > 3 **system-errors** in the last 10 activations, pings arriving as usual
 * - "Healthy": < 3 **system-errors** in the last 10 activations, pings arriving as usual
 *
 * ## Horizontal sharding
 *
 * Sharding is employed to avoid both loadbalancers having to share any data, because the metrics used in scheduling
 * are very fast changing.
 *
 * Horizontal sharding means, that each invoker's capacity is evenly divided between the loadbalancers. If an invoker
 * has at most 16 slots available (invoker-busy-threshold = 16), those will be divided to 8 slots for each loadbalancer
 * (if there are 2).
 *
 * Known caveats:
 * - If a loadbalancer leaves or joins the cluster, all state is removed and created from scratch. Those events should
 *   not happen often.
 */
class ShardingContainerPoolBalancer(config: WhiskConfig, controllerInstance: ControllerInstanceId)(
  implicit val actorSystem: ActorSystem,
  logging: Logging,
  materializer: ActorMaterializer)
    extends LoadBalancer {

  private implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  /** Build a cluster of all loadbalancers */
  private val cluster: Option[Cluster] = if (loadConfigOrThrow[ClusterConfig](ConfigKeys.cluster).useClusterBootstrap) {
    AkkaManagement(actorSystem).start()
    ClusterBootstrap(actorSystem).start()
    Some(Cluster(actorSystem))
  } else if (loadConfigOrThrow[Seq[String]]("akka.cluster.seed-nodes").nonEmpty) {
    Some(Cluster(actorSystem))
  } else {
    None
  }

  private val lbConfig = loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer)

  /** State related to invocations and throttling */
  private val activations = TrieMap[ActivationId, ActivationEntry]()
  private val activationsPerNamespace = TrieMap[UUID, LongAdder]()
  private val totalActivations = new LongAdder()
  private val totalActivationMemory = new LongAdder()

  /** State needed for scheduling. */
  private val schedulingState = ShardingContainerPoolBalancerState()(lbConfig)

  actorSystem.scheduler.schedule(0.seconds, 10.seconds) {
    MetricEmitter.emitHistogramMetric(LOADBALANCER_ACTIVATIONS_INFLIGHT(controllerInstance), totalActivations.longValue)
    MetricEmitter.emitHistogramMetric(LOADBALANCER_MEMORY_INFLIGHT(controllerInstance), totalActivationMemory.longValue)
  }

  /**
   * Monitors invoker supervision and the cluster to update the state sequentially
   *
   * All state updates should go through this actor to guarantee that
   * [[ShardingContainerPoolBalancerState.updateInvokers]] and [[ShardingContainerPoolBalancerState.updateCluster]]
   * are called exclusive of each other and not concurrently.
   */
  private val monitor = actorSystem.actorOf(Props(new Actor {
    override def preStart(): Unit = {
      cluster.foreach(_.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent]))
    }

    // all members of the cluster that are available
    var availableMembers = Set.empty[Member]

    override def receive: Receive = {
      case CurrentInvokerPoolState(newState) =>
        schedulingState.updateInvokers(newState)

      // State of the cluster as it is right now
      case CurrentClusterState(members, _, _, _, _) =>
        availableMembers = members.filter(_.status == MemberStatus.Up)
        schedulingState.updateCluster(availableMembers.size)

      // General lifecycle events and events concerning the reachability of members. Split-brain is not a huge concern
      // in this case as only the invoker-threshold is adjusted according to the perceived cluster-size.
      // Taking the unreachable member out of the cluster from that point-of-view results in a better experience
      // even under split-brain-conditions, as that (in the worst-case) results in premature overloading of invokers vs.
      // going into overflow mode prematurely.
      case event: ClusterDomainEvent =>
        availableMembers = event match {
          case MemberUp(member)          => availableMembers + member
          case ReachableMember(member)   => availableMembers + member
          case MemberRemoved(member, _)  => availableMembers - member
          case UnreachableMember(member) => availableMembers - member
          case _                         => availableMembers
        }

        schedulingState.updateCluster(availableMembers.size)
    }
  }))

  /** Loadbalancer interface methods */
  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(schedulingState.invokers)
  override def activeActivationsFor(namespace: UUID): Future[Int] =
    Future.successful(activationsPerNamespace.get(namespace).map(_.intValue()).getOrElse(0))
  override def totalActiveActivations: Future[Int] = Future.successful(totalActivations.intValue())
  override def clusterSize: Int = schedulingState.clusterSize

  /** 1. Publish a message to the loadbalancer */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val (invokersToUse, stepSizes) =
      if (!action.exec.pull) (schedulingState.managedInvokers, schedulingState.managedStepSizes)
      else (schedulingState.blackboxInvokers, schedulingState.blackboxStepSizes)
    val chosen = if (invokersToUse.nonEmpty) {
      val hash = ShardingContainerPoolBalancer.generateHash(msg.user.namespace.name, action.fullyQualifiedName(false))
      val homeInvoker = hash % invokersToUse.size
      val stepSize = stepSizes(hash % stepSizes.size)
      ShardingContainerPoolBalancer.schedule(invokersToUse, schedulingState.invokerSlots, homeInvoker, stepSize)
    } else {
      None
    }

    chosen
      .map { invoker =>
        val entry = setupActivation(msg, action, invoker)
        sendActivationToInvoker(messageProducer, msg, invoker).map { _ =>
          entry.promise.future
        }
      }
      .getOrElse {
        // report the state of all invokers
        val actionType = if (!action.exec.pull) "non-blackbox" else "blackbox"
        val invokerStates = invokersToUse.foldLeft(Map.empty[InvokerState, Int]) { (agg, curr) =>
          val count = agg.getOrElse(curr.status, 0) + 1
          agg + (curr.status -> count)
        }

        logging.error(this, s"failed to schedule $actionType action, invokers to use: $invokerStates")
        Future.failed(LoadBalancerException("No invokers available"))
      }
  }

  /** 2. Update local state with the to be executed activation */
  private def setupActivation(msg: ActivationMessage,
                              action: ExecutableWhiskActionMetaData,
                              instance: InvokerInstanceId): ActivationEntry = {

    totalActivations.increment()
    totalActivationMemory.add(action.limits.memory.megabytes)
    activationsPerNamespace.getOrElseUpdate(msg.user.namespace.uuid, new LongAdder()).increment()

    // Timeout is a multiple of the configured maximum action duration. The minimum timeout is the configured standard
    // value for action durations to avoid too tight timeouts.
    // Timeouts in general are diluted by a configurable factor. In essence this factor controls how much slack you want
    // to allow in your topics before you start reporting failed activations.
    val timeout = (action.limits.timeout.duration.max(TimeLimit.STD_DURATION) * lbConfig.timeoutFactor) + 1.minute

    // Install a timeout handler for the catastrophic case where an active ack is not received at all
    // (because say an invoker is down completely, or the connection to the message bus is disrupted) or when
    // the active ack is significantly delayed (possibly dues to long queues but the subject should not be penalized);
    // in this case, if the activation handler is still registered, remove it and update the books.
    activations.getOrElseUpdate(
      msg.activationId, {
        val timeoutHandler = actorSystem.scheduler.scheduleOnce(timeout) {
          processCompletion(Left(msg.activationId), msg.transid, forced = true, invoker = instance)
        }

        // please note: timeoutHandler.cancel must be called on all non-timeout paths, e.g. Success
        ActivationEntry(
          msg.activationId,
          msg.user.namespace.uuid,
          instance,
          action.limits.memory.megabytes.MB,
          timeoutHandler,
          Promise[Either[ActivationId, WhiskActivation]]())
      })
  }

  private val messagingProvider = SpiLoader.get[MessagingProvider]
  private val messageProducer = messagingProvider.getProducer(config)

  /** 3. Send the activation to the invoker */
  private def sendActivationToInvoker(producer: MessageProducer,
                                      msg: ActivationMessage,
                                      invoker: InvokerInstanceId): Future[RecordMetadata] = {
    implicit val transid: TransactionId = msg.transid

    val topic = s"invoker${invoker.toInt}"

    MetricEmitter.emitCounterMetric(LoggingMarkers.LOADBALANCER_ACTIVATION_START)
    val start = transid.started(
      this,
      LoggingMarkers.CONTROLLER_KAFKA,
      s"posting to '$invoker' with activation id '${msg.activationId}'",
      logLevel = InfoLevel)

    producer.send(topic, msg).andThen {
      case Success(status) =>
        transid.finished(
          this,
          start,
          s"posted to ${status.topic()}[${status.partition()}][${status.offset()}]",
          logLevel = InfoLevel)
      case Failure(_) => transid.failed(this, start, s"error on posting to topic $topic")
    }
  }

  /**
   * Subscribes to active acks (completion messages from the invokers), and
   * registers a handler for received active acks from invokers.
   */
  private val activeAckTopic = s"completed${controllerInstance.asString}"
  private val maxActiveAcksPerPoll = 128
  private val activeAckPollDuration = 1.second
  private val activeAckConsumer =
    messagingProvider.getConsumer(config, activeAckTopic, activeAckTopic, maxPeek = maxActiveAcksPerPoll)

  private val activationFeed = actorSystem.actorOf(Props {
    new MessageFeed(
      "activeack",
      logging,
      activeAckConsumer,
      maxActiveAcksPerPoll,
      activeAckPollDuration,
      processActiveAck)
  })

  /** 4. Get the active-ack message and parse it */
  private def processActiveAck(bytes: Array[Byte]): Future[Unit] = Future {
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

  /** 5. Process the active-ack and update the state accordingly */
  private def processCompletion(response: Either[ActivationId, WhiskActivation],
                                tid: TransactionId,
                                forced: Boolean,
                                invoker: InvokerInstanceId): Unit = {
    val aid = response.fold(l => l, r => r.activationId)

    val invocationResult = if (forced) {
      InvocationFinishedResult.Timeout
    } else {
      // If the response contains a system error, report that, otherwise report Success
      // Left generally is considered a Success, since that could be a message not fitting into Kafka
      val isSystemError = response.fold(_ => false, _.response.isWhiskError)
      if (isSystemError) {
        InvocationFinishedResult.SystemError
      } else {
        InvocationFinishedResult.Success
      }
    }

    activations.remove(aid) match {
      case Some(entry) =>
        totalActivations.decrement()
        totalActivationMemory.add(entry.memory.toMB * (-1))
        activationsPerNamespace.get(entry.namespaceId).foreach(_.decrement())
        schedulingState.invokerSlots.lift(invoker.toInt).foreach(_.release())

        if (!forced) {
          entry.timeoutHandler.cancel()
          entry.promise.trySuccess(response)
        } else {
          entry.promise.tryFailure(new Throwable("no active ack received"))
        }

        logging.info(this, s"${if (!forced) "received" else "forced"} active ack for '$aid'")(tid)
        // Active acks that are received here are strictly from user actions - health actions are not part of
        // the load balancer's activation map. Inform the invoker pool supervisor of the user action completion.
        invokerPool ! InvocationFinishedMessage(invoker, invocationResult)
      case None if tid == TransactionId.invokerHealth =>
        // Health actions do not have an ActivationEntry as they are written on the message bus directly. Their result
        // is important to pass to the invokerPool because they are used to determine if the invoker can be considered
        // healthy again.
        logging.info(this, s"received active ack for health action on $invoker")(tid)
        invokerPool ! InvocationFinishedMessage(invoker, invocationResult)
      case None if !forced =>
        // Received an active-ack that has already been taken out of the state because of a timeout (forced active-ack).
        // The result is ignored because a timeout has already been reported to the invokerPool per the force.
        logging.debug(this, s"received active ack for '$aid' which has no entry")(tid)
      case None =>
        // The entry has already been removed by an active ack. This part of the code is reached by the timeout and can
        // happen if active-ack and timeout happen roughly at the same time (the timeout was triggered before the active
        // ack canceled the timer). As the active ack is already processed we don't have to do anything here.
        logging.debug(this, s"forced active ack for '$aid' which has no entry")(tid)
    }
  }

  private val invokerPool = {
    InvokerPool.prepare(controllerInstance, WhiskEntityStore.datastore())

    actorSystem.actorOf(
      InvokerPool.props(
        (f, i) => f.actorOf(InvokerActor.props(i, controllerInstance)),
        (m, i) => sendActivationToInvoker(messageProducer, m, i),
        messagingProvider.getConsumer(config, s"health${controllerInstance.asString}", "health", maxPeek = 128),
        Some(monitor)))
  }
}

object ShardingContainerPoolBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): LoadBalancer = new ShardingContainerPoolBalancer(whiskConfig, instance)

  def requiredProperties: Map[String, String] = kafkaHosts

  /** Generates a hash based on the string representation of namespace and action */
  def generateHash(namespace: EntityName, action: FullyQualifiedEntityName): Int = {
    (namespace.asString.hashCode() ^ action.asString.hashCode()).abs
  }

  /** Euclidean algorithm to determine the greatest-common-divisor */
  @tailrec
  def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

  /** Returns pairwise coprime numbers until x. Result is memoized. */
  def pairwiseCoprimeNumbersUntil(x: Int): IndexedSeq[Int] =
    (1 to x).foldLeft(IndexedSeq.empty[Int])((primes, cur) => {
      if (gcd(cur, x) == 1 && primes.forall(i => gcd(i, cur) == 1)) {
        primes :+ cur
      } else primes
    })

  /**
   * Scans through all invokers and searches for an invoker tries to get a free slot on an invoker. If no slot can be
   * obtained, randomly picks a healthy invoker.
   *
   * @param invokers a list of available invokers to search in, including their state
   * @param dispatched semaphores for each invoker to give the slots away from
   * @param index the index to start from (initially should be the "homeInvoker"
   * @param step stable identifier of the entity to be scheduled
   * @return an invoker to schedule to or None of no invoker is available
   */
  @tailrec
  def schedule(invokers: IndexedSeq[InvokerHealth],
               dispatched: IndexedSeq[ForcibleSemaphore],
               index: Int,
               step: Int,
               stepsDone: Int = 0)(implicit logging: Logging, transId: TransactionId): Option[InvokerInstanceId] = {
    val numInvokers = invokers.size

    if (numInvokers > 0) {
      val invoker = invokers(index)
      // If the current invoker is healthy and we can get a slot
      if (invoker.status.isUsable && dispatched(invoker.id.toInt).tryAcquire()) {
        Some(invoker.id)
      } else {
        // If we've gone through all invokers
        if (stepsDone == numInvokers + 1) {
          val healthyInvokers = invokers.filter(_.status.isUsable)
          if (healthyInvokers.nonEmpty) {
            // Choose a healthy invoker randomly
            val random = healthyInvokers(ThreadLocalRandom.current().nextInt(healthyInvokers.size)).id
            dispatched(random.toInt).forceAcquire()
            logging.warn(this, s"system is overloaded. Chose invoker${random.toInt} by random assignment.")
            Some(random)
          } else {
            None
          }
        } else {
          val newIndex = (index + step) % numInvokers
          schedule(invokers, dispatched, newIndex, step, stepsDone + 1)
        }
      }
    } else {
      None
    }
  }
}

/**
 * Holds the state necessary for scheduling of actions.
 *
 * @param _invokers all of the known invokers in the system
 * @param _managedInvokers all invokers for managed runtimes
 * @param _blackboxInvokers all invokers for blackbox runtimes
 * @param _managedStepSizes the step-sizes possible for the current managed invoker count
 * @param _blackboxStepSizes the step-sizes possible for the current blackbox invoker count
 * @param _invokerSlots state of accessible slots of each invoker
 */
case class ShardingContainerPoolBalancerState(
  private var _invokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _blackboxInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedStepSizes: Seq[Int] = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  private var _blackboxStepSizes: Seq[Int] = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  private var _invokerSlots: IndexedSeq[ForcibleSemaphore] = IndexedSeq.empty[ForcibleSemaphore],
  private var _clusterSize: Int = 1)(
  lbConfig: ShardingContainerPoolBalancerConfig =
    loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer))(implicit logging: Logging) {

  private val totalInvokerThreshold = lbConfig.invokerBusyThreshold
  private var currentInvokerThreshold = totalInvokerThreshold

  private val blackboxFraction: Double = Math.max(0.0, Math.min(1.0, lbConfig.blackboxFraction))
  logging.info(this, s"blackboxFraction = $blackboxFraction")(TransactionId.loadbalancer)

  /** Getters for the variables, setting from the outside is only allowed through the update methods below */
  def invokers: IndexedSeq[InvokerHealth] = _invokers
  def managedInvokers: IndexedSeq[InvokerHealth] = _managedInvokers
  def blackboxInvokers: IndexedSeq[InvokerHealth] = _blackboxInvokers
  def managedStepSizes: Seq[Int] = _managedStepSizes
  def blackboxStepSizes: Seq[Int] = _blackboxStepSizes
  def invokerSlots: IndexedSeq[ForcibleSemaphore] = _invokerSlots
  def clusterSize: Int = _clusterSize

  /**
   * Updates the scheduling state with the new invokers.
   *
   * This is okay to not happen atomically since dirty reads of the values set are not dangerous. It is important though
   * to update the "invokers" variables last, since they will determine the range of invokers to choose from.
   *
   * Handling a shrinking invokers list is not necessary, because InvokerPool won't shrink its own list but rather
   * report the invoker as "Offline".
   *
   * It is important that this method does not run concurrently to itself and/or to [[updateCluster]]
   */
  def updateInvokers(newInvokers: IndexedSeq[InvokerHealth]): Unit = {
    val oldSize = _invokers.size
    val newSize = newInvokers.size

    // for small N, allow the managed invokers to overlap with blackbox invokers, and
    // further assume that blackbox invokers << managed invokers
    val managed = Math.max(1, Math.ceil(newSize.toDouble * (1 - blackboxFraction)).toInt)
    val blackboxes = Math.max(1, Math.floor(newSize.toDouble * blackboxFraction).toInt)

    _invokers = newInvokers
    _managedInvokers = _invokers.take(managed)
    _blackboxInvokers = _invokers.takeRight(blackboxes)

    if (oldSize != newSize) {
      _managedStepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(managed)
      _blackboxStepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(blackboxes)

      if (oldSize < newSize) {
        // Keeps the existing state..
        _invokerSlots = _invokerSlots ++ IndexedSeq.fill(newSize - oldSize) {
          new ForcibleSemaphore(currentInvokerThreshold)
        }
      }
    }

    logging.info(
      this,
      s"loadbalancer invoker status updated. managedInvokers = $managed blackboxInvokers = $blackboxes")(
      TransactionId.loadbalancer)
  }

  /**
   * Updates the size of a cluster. Throws away all state for simplicity.
   *
   * This is okay to not happen atomically, since a dirty read of the values set are not dangerous. At worst the
   * scheduler works on outdated invoker-load data which is acceptable.
   *
   * It is important that this method does not run concurrently to itself and/or to [[updateInvokers]]
   */
  def updateCluster(newSize: Int): Unit = {
    val actualSize = newSize max 1 // if a cluster size < 1 is reported, falls back to a size of 1 (alone)
    if (_clusterSize != actualSize) {
      _clusterSize = actualSize
      val newTreshold = (totalInvokerThreshold / actualSize) max 1 // letting this fall below 1 doesn't make sense
      currentInvokerThreshold = newTreshold
      _invokerSlots = _invokerSlots.map(_ => new ForcibleSemaphore(currentInvokerThreshold))

      logging.info(
        this,
        s"loadbalancer cluster size changed to $actualSize active nodes. invokerThreshold = $currentInvokerThreshold")(
        TransactionId.loadbalancer)
    }
  }
}

/**
 * Configuration for the cluster created between loadbalancers.
 *
 * @param useClusterBootstrap Whether or not to use a bootstrap mechanism
 */
case class ClusterConfig(useClusterBootstrap: Boolean)

/**
 * Configuration for the sharding container pool balancer.
 *
 * @param blackboxFraction the fraction of all invokers to use exclusively for blackboxes
 * @param invokerBusyThreshold how many slots an invoker has available in total
 * @param timeoutFactor factor to influence the timeout period for forced active acks (time-limit.std * timeoutFactor + 1m)
 */
case class ShardingContainerPoolBalancerConfig(blackboxFraction: Double, invokerBusyThreshold: Int, timeoutFactor: Int)

/**
 * State kept for each activation until completion.
 *
 * @param id id of the activation
 * @param namespaceId namespace that invoked the action
 * @param invokerName invoker the action is scheduled to
 * @param timeoutHandler times out completion of this activation, should be canceled on good paths
 * @param promise the promise to be completed by the activation
 */
case class ActivationEntry(id: ActivationId,
                           namespaceId: UUID,
                           invokerName: InvokerInstanceId,
                           memory: ByteSize,
                           timeoutHandler: Cancellable,
                           promise: Promise[Either[ActivationId, WhiskActivation]])
