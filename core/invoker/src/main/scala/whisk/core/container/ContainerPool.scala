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

package whisk.core.container

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Try, Success, Failure }

import akka.actor.ActorSystem
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.Scheduler
import whisk.common.TimingUtil
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig._
import whisk.core.entity._

/**
 * A thread-safe container pool that internalizes container creation/teardown and allows users
 * to check out a container.
 *
 * Synchronization via "this" is used to maintain integrity of the data structures.
 * A separate object "gcSync" is used to prevent multiple GC's from occurring.
 *
 * TODO: Parallel container creation under evaluation for docker 12.
 */
class ContainerPool(
    config: WhiskConfig,
    invokerInstance: InstanceId = InstanceId(0),
    standalone: Boolean = false,
    saveContainerLog: Boolean = false)(implicit actorSystem: ActorSystem, val logging: Logging)
    extends ContainerUtils {

    implicit val executionContext = actorSystem.dispatcher

    // These must be defined before verbosity is set
    private val datastore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)

    val mounted = !standalone
    val dockerhost = config.selfDockerEndpoint
    val serializeDockerOp = config.invokerSerializeDockerOp.toBoolean
    val serializeDockerPull = config.invokerSerializeDockerPull.toBoolean
    val useRunc = checkRuncAccess(config.invokerUseRunc.toBoolean)
    logging.info(this, s"dockerhost = $dockerhost    serializeDockerOp = $serializeDockerOp   serializeDockerPull = $serializeDockerPull   useRunC = $useRunc")

    // Eventually, we will have a more sophisticated warmup strategy that does multiple sizes
    private val defaultMemoryLimit = MemoryLimit(MemoryLimit.STD_MEMORY)
    private val NODEJS6_IMAGE = ExecManifest.runtimesManifest.manifests("nodejs:6").image

    /**
     *  Check whether we should use runc.  To do so,
     *  1. The whisk config flag must be on.
     *  2. Runc must be successfully accessible.  This is a failsafe in case runc is not set up correctly.
     *     For this stage, logging shows success or failure if we get this far.
     */
    def checkRuncAccess(useRunc: Boolean): Boolean = {
        if (useRunc) {
            implicit val tid = TransactionId.invokerNanny
            val (code, result) = RuncUtils.list()
            val success = (code == 0)
            if (success) {
                logging.info(this, s"Using runc. list result: ${result}")
            } else {
                logging.warn(this, s"Not using runc due to error (code = ${code}): ${result}")
            }
            success
        } else {
            logging.info(this, s"Not using runc because of configuration flag")
            false
        }
    }

    /**
     * Enables GC.
     */
    def enableGC(): Unit = {
        gcOn = true
    }

    /**
     * Disables GC. If disabled, overrides other flags/methods.
     */
    def disableGC(): Unit = {
        gcOn = false
    }

    /**
     * Performs a GC immediately of all idle containers, blocking the caller until completed.
     */
    def forceGC()(implicit transid: TransactionId): Unit = {
        removeAllIdle({ containerInfo => true })
    }

    /*
     * Getter/Setter for various GC parameters.
     */
    def gcThreshold: FiniteDuration = _gcThreshold
    def maxIdle: Int = _maxIdle // container count
    def maxActive: Int = _maxActive // container count
    def gcThreshold_=(value: FiniteDuration): Unit = _gcThreshold = (Duration.Zero max value)
    def maxIdle_=(value: Int): Unit = _maxIdle = Math.max(0, value)
    def maxActive_=(value: Int): Unit = _maxActive = Math.max(0, value)

    def resetMaxIdle() = _maxIdle = defaultMaxIdle
    def resetMaxActive() = {
        _maxActive = ContainerPool.getDefaultMaxActive(config)
        logging.info(this, s"maxActive set to ${_maxActive}")
    }
    def resetGCThreshold() = _gcThreshold = defaultGCThreshold

    /*
     * Controls where docker container logs are put.
     */
    def logDir: String = _logDir
    def logDir_=(value: String): Unit = _logDir = value

    /*
     * How many containers are in the pool at the moment?
     * There are also counts of containers we are trying to start but have not inserted into the data structure.
     */
    def idleCount() = countByState(State.Idle)
    def activeCount() = countByState(State.Active)
    private val startingCounter = new Counter()
    private var shuttingDown = false

    /*
     * Tracks requests for getting containers.
     * The first value doled out via nextPosition.next() will be 1 and completedPosition.cur remains at 0 until completion.
     */
    private val nextPosition = new Counter()
    private val completedPosition = new Counter()

    /*
     * Lists ALL containers at this docker point with "docker ps -a --no-trunc".
     * This could include containers not in this pool at all.
     */
    def listAll()(implicit transid: TransactionId): Seq[ContainerState] = listContainers(true)

    /**
     * Retrieves (possibly create) a container based on the subject and versioned action.
     * A flag is included to indicate whether initialization succeeded.
     * The invariant of returning the container back to the pool holds regardless of whether init succeeded or not.
     * In case of failure to start a container (or for failed docker operations e.g., pull), an exception is thrown.
     */
    def getAction(action: WhiskAction, auth: AuthKey)(implicit transid: TransactionId): (WhiskContainer, Option[RunResult]) = {
        if (shuttingDown) {
            logging.info(this, s"Shutting down: Not getting container for ${action.fullyQualifiedName(true)} with ${auth.uuid}")
            throw new Exception("system is shutting down")
        } else {
            val key = ActionContainerId(auth.uuid, action.fullyQualifiedName(true).toString, action.rev)
            val myPos = nextPosition.next()
            logging.info(this, s"""Getting container for ${action.fullyQualifiedName(true)} of kind ${action.exec.kind} with ${auth.uuid}:
                          | myPos = $myPos
                          | completed = ${completedPosition.cur}
                          | slack = ${slack()}
                          | activeCount = ${activeCount()}
                          | toBeRemoved = ${toBeRemoved.size}
                          | startingCounter = ${startingCounter.cur}""".stripMargin)
            val conResult = Try(getContainer(1, myPos, key, () => makeWhiskContainer(action, auth)))
            completedPosition.next()
            conResult match {
                case Success(Cold(con)) =>
                    logging.info(this, s"Obtained cold container ${con.containerId.id} - about to initialize")
                    val initResult = initWhiskContainer(action, con)
                    (con, Some(initResult))
                case Success(Warm(con)) =>
                    logging.info(this, s"Obtained warm container ${con.containerId.id}")
                    (con, None)
                case Failure(t) =>
                    logging.error(this, s"Exception while trying to get a container: $t")
                    throw t
            }
        }
    }

    /*
     * For testing by ContainerPoolTests where non whisk containers are used.
     * These do not require initialization.
     */
    def getByImageName(imageName: String, args: Array[String])(implicit transid: TransactionId): Option[Container] = {
        logging.info(this, s"Getting container for image $imageName with args " + args.mkString(" "))
        // Not a regular key. Doesn't matter in testing.
        val key = new ActionContainerId(s"instantiated." + imageName + args.mkString("_"))
        getContainer(1, 0, key, () => makeContainer(key, imageName, args)) match {
            case Cold(con) => Some(con)
            case Warm(con) => Some(con)
            case _         => None
        }
    }

    /**
     * Tries to get/create a container via the thunk by delegating to getOrMake.
     * This method will apply retry so that the caller is blocked until retry succeeds.
     */
    @tailrec
    final def getContainer(tryCount: Int, position: Long, key: ActionContainerId, conMaker: () => WhiskContainer)(implicit transid: TransactionId): ContainerResult = {
        val positionInLine = position - completedPosition.cur // Indicates queue position.  1 means front of the line
        val available = slack()
        // Warn at 10 seconds and then once a minute after that.
        val waitDur = 50.millis
        val warnAtCount = 10.seconds.toMillis / waitDur.toMillis
        val warnPeriodic = 60.seconds.toMillis / waitDur.toMillis
        if (tryCount == warnAtCount || tryCount % warnPeriodic == 0) {
            logging.warn(this, s"""getContainer has been waiting about ${warnAtCount * waitDur.toMillis} ms:
                          | position = $position
                          | completed = ${completedPosition.cur}
                          | slack = $available
                          | maxActive = ${_maxActive}
                          | activeCount = ${activeCount()}
                          | startingCounter = ${startingCounter.cur}""".stripMargin)
        }
        if (positionInLine <= available) {
            getOrMake(key, conMaker) match {
                case Some(cr) => cr
                case None     => getContainer(tryCount + 1, position, key, conMaker)
            }
        } else { // It's not our turn in line yet.
            Thread.sleep(waitDur.toMillis) // TODO: Replace with wait/notify but tricky because of desire for maximal concurrency
            getContainer(tryCount + 1, position, key, conMaker)
        }
    }

    def getNumberOfIdleContainers(key: ActionContainerId)(implicit transid: TransactionId): Int = {
        this.synchronized {
            keyMap.get(key) map { bucket => bucket.count { _.isIdle } } getOrElse 0
        }
    }

    /*
     * How many containers can we start?  Someone could have fully started a container so we must include startingCounter.
     * The use of a method rather than a getter is meant to signify the synchronization in the implementation.
     */
    private def slack() = _maxActive - (activeCount() + startingCounter.cur + Math.max(toBeRemoved.size - RM_SLACK, 0))

    /*
     * Try to get or create a container, returning None if there are too many
     * active containers.
     *
     * The multiple synchronization block, and the use of startingCounter,
     * is needed to make sure container count is accurately tracked,
     * data structure maintains integrity, but to keep all length operations
     * outside of the lock.
     *
     * The returned container will be active (not paused).
     */
    def getOrMake(key: ActionContainerId, conMaker: () => WhiskContainer)(implicit transid: TransactionId): Option[ContainerResult] = {
        retrieve(key) match {
            case CacheMiss => {
                val con = conMaker()
                this.synchronized {
                    introduceContainer(key, con).state = State.Active
                }
                Some(Cold(con))
            }
            case CacheHit(con) =>
                con.transid = transid
                runDockerOp {
                    if (con.unpause()) {
                        Some(Warm(con))
                    } else {
                        // resume failed, gc the container
                        putBack(con, delete = true)
                        None
                    }
                }
            case CacheBusy => None
        }
    }

    /**
     * Obtains a pre-existing container from the pool - and putting it to Active state but without docker unpausing.
     * If we are over capacity, signal Busy.
     * If it does not exist ready to do, indicate a miss.
     */
    def retrieve(key: ActionContainerId)(implicit transid: TransactionId): CacheResult = {
        this.synchronized {
            // first check if there is a matching container and only if there aren't any
            // determine if the pool is full or has capacity to accommodate a new container;
            // this allows any new containers introduced into the pool to be reused if already idle
            val bucket = keyMap.getOrElseUpdate(key, new ListBuffer())
            bucket.find({ ci => ci.isIdle }) match {
                case None =>
                    if (activeCount() + startingCounter.cur >= _maxActive) {
                        CacheBusy
                    } else {
                        CacheMiss
                    }
                case Some(ci) => {
                    ci.state = State.Active
                    CacheHit(ci.container)
                }
            }
        }
    }

    /**
     * Moves a container from one bucket (i.e. key) to a different one.
     * This operation is performed when we specialize a pre-warmed container to an action.
     * ContainerMap does not need to be updated as the Container <-> ContainerInfo relationship does not change.
     */
    def changeKey(ci: ContainerInfo, oldKey: ActionContainerId, newKey: ActionContainerId)(implicit transid: TransactionId) = {
        this.synchronized {
            assert(ci.state == State.Active)
            assert(keyMap.contains(oldKey))
            val oldBucket = keyMap(oldKey)
            val newBucket = keyMap.getOrElseUpdate(newKey, new ListBuffer())
            oldBucket -= ci
            newBucket += ci
        }
    }

    /**
     * Returns the container to the pool or delete altogether.
     * This call can be slow but not while locking data structure so it does not interfere with other activations.
     */
    def putBack(container: Container, delete: Boolean = false)(implicit transid: TransactionId): Unit = {
        logging.info(this, s"""putBack returning container ${container.id}
                      | delete = $delete
                      | completed = ${completedPosition.cur}
                      | slack = ${slack()}
                      | maxActive = ${_maxActive}
                      | activeCount = ${activeCount()}
                      | startingCounter = ${startingCounter.cur}""".stripMargin)
        // Docker operation outside sync block. Don't pause if we are deleting.
        if (!delete) {
            runDockerOp {
                // pausing eagerly is pessimal; there could be an action waiting
                // that will immediately unpause the same container to reuse it;
                // to skip pausing, will need to inspect the queue of waiting activations
                // for a matching key
                container.pause()
            }
        }

        val toBeDeleted = this.synchronized { // Return container to pool logically and then optionally delete
            // Always put back logically for consistency
            val Some(ci) = containerMap.get(container)
            assert(ci.state == State.Active)
            ci.lastUsed = System.currentTimeMillis()
            ci.state = State.Idle
            val toBeDeleted = if (delete) {
                removeContainerInfo(ci) // no docker operation here
                List(ci)
            } else {
                List()
            }
            this.notify()
            toBeDeleted
        }

        toBeDeleted.foreach { ci => toBeRemoved.offer(RemoveJob(false, ci)) }
        // Perform capacity-based GC here.
        if (gcOn) { // Synchronization occurs inside calls in a fine-grained manner.
            while (idleCount() > _maxIdle) { // it is safe for this to be non-atomic with body
                removeOldestIdle()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    object State extends Enumeration {
        val Idle, Active = Value
    }

    /**
     * Wraps a Container to allow a ContainerPool-specific information.
     */
    class ContainerInfo(k: ActionContainerId, con: WhiskContainer) {
        val key = k
        val container = con
        var state = State.Idle
        var lastUsed = System.currentTimeMillis()
        def isIdle = state == State.Idle
        def isStemCell = key == stemCellNodejsKey
    }

    private val containerMap = new TrieMap[Container, ContainerInfo]
    private val keyMap = new TrieMap[ActionContainerId, ListBuffer[ContainerInfo]]

    // These are containers that are already removed from the data structure waiting to be docker-removed
    case class RemoveJob(needUnpause: Boolean, containerInfo: ContainerInfo)
    private val toBeRemoved = new ConcurrentLinkedQueue[RemoveJob]

    // Note that the prefix separates the name space of this from regular keys.
    // TODO: Generalize across language by storing image name when we generalize to other languages
    //       Better heuristic for # of containers to keep warm - make sensitive to idle capacity
    private val stemCellNodejsKey = StemCellNodeJsActionContainerId
    private val WARM_NODEJS_CONTAINERS = 2

    // This parameter controls how many outstanding un-removed containers there are before
    // we stop stem cell container creation.  This is also the an allowance in slack calculation
    // to allow limited de-coupling between container removal and creation when under load.
    private val RM_SLACK = 4

    private def keyMapToString(): String = {
        keyMap.map(p => s"[${p._1.stringRepr} -> ${p._2}]").mkString("  ")
    }

    // Easier to walk containerMap than keyMap
    private def countByState(state: State.Value) = this.synchronized { containerMap.count({ case (_, ci) => ci.state == state }) }

    // Sample container name: wsk1_1_joeibmcomhelloWorldDemo_20150901T202701852Z
    private def makeContainerName(localName: String): ContainerName =
        ContainerCounter.containerName(invokerInstance.toInt.toString, localName)

    private def makeContainerName(action: WhiskAction): ContainerName =
        makeContainerName(action.fullyQualifiedName(true).toString)

    /**
     * dockerLock is a fair lock used to serialize all docker operations except pull.
     * However, a non-pull operation can run concurrently with a pull operation.
     */
    val dockerLock = new ReentrantLock(true)

    /**
     * dockerPullLock is used to serialize all pull operations.
     */
    val dockerPullLock = new ReentrantLock(true)

    /* A background thread that
     *   1. Kills leftover action containers on startup
     *   2. (actually a Future) Periodically re-populates the container pool with fresh (un-instantiated) nodejs containers.
     *   3. Periodically tears down containers that have logically been removed from the system
     *   4. Each of the significant method subcalls are guarded to not throw an exception.
     */
    private def nannyThread(allContainers: Seq[ContainerState]) = new Thread {
        override def run {
            implicit val tid = TransactionId.invokerNanny
            if (mounted) {
                killStragglers(allContainers)
                // Create a new stem cell if the number of warm containers is less than the count allowed
                // as long as there is slack so that any actions that may be waiting to create a container
                // are not held back; Note since this method is not fully synchronized, it is possible to
                // start this operation while there is slack and end up waiting on the docker lock later
                val warmupInterval = 100.milliseconds
                Scheduler.scheduleWaitAtLeast(warmupInterval) { () =>
                    implicit val tid = TransactionId.invokerWarmup
                    if (getNumberOfIdleContainers(stemCellNodejsKey) < WARM_NODEJS_CONTAINERS && slack() > 0 && toBeRemoved.size < RM_SLACK) {
                        addStemCellNodejsContainer()(tid)
                    } else {
                        Future.successful(())
                    }
                }
            }
            while (true) {
                Thread.sleep(100) // serves to prevent busy looping
                // We grab the size first so we know there has been enough delay for anything we are shutting down
                val size = toBeRemoved.size()
                1 to size foreach { _ =>
                    val removeJob = toBeRemoved.poll()
                    if (removeJob != null) {
                        Thread.sleep(100) // serves to not hog docker lock and add slack
                        teardownContainer(removeJob)
                    } else {
                        logging.error(this, "toBeRemove.poll failed - possibly another concurrent remover?")
                    }
                }
            }
        }
    }

    /**
     * Gracefully terminates by shutting down containers upon SIGTERM.
     * If one desires to kill the invoker without this, send it SIGKILL.
     */
    private def shutdown() = {
        implicit val id = TransactionId.invokerWarmup
        shuttingDown = true
        killStragglers(listAll())
    }

    /**
     * All docker operations from the pool must pass through here (except for pull).
     */
    private def runDockerOp[T](dockerOp: => T)(implicit transid: TransactionId): T = {
        runDockerOpWithLock(serializeDockerOp, dockerLock, dockerOp)
    }

    /**
     * All docker pull operations from the pool must pass through here.
     */
    private def runDockerPull[T](dockerOp: => T)(implicit transid: TransactionId): T = {
        runDockerOpWithLock(serializeDockerPull, dockerPullLock, dockerOp)
    }

    /**
     * All docker operations from the pool must pass through here (except for pull).
     */
    private def runDockerOpWithLock[T](useLock: Boolean, lock: ReentrantLock, dockerOp: => T)(implicit transid: TransactionId): T = {
        if (useLock) {
            lock.lock()
        }
        try {
            val (elapsed, result) = TimingUtil.time { dockerOp }
            if (elapsed > slowDockerThreshold) {
                logging.warn(this, s"Docker operation took $elapsed")
            }
            result
        } finally {
            if (useLock) {
                lock.unlock()
            }
        }
    }

    /*
     * This method will introduce a stem cell container into the system.
     * If container creation fails, the container will not be entered into the pool.
     */
    private def addStemCellNodejsContainer()(implicit transid: TransactionId) = Future {
        val imageName = NODEJS6_IMAGE.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
        val limits = ActionLimits(TimeLimit(), defaultMemoryLimit, LogLimit())
        val containerName = makeContainerName("warmJsContainer")
        logging.info(this, "Starting warm nodejs container")
        val con = makeGeneralContainer(stemCellNodejsKey, containerName, imageName, limits, false)
        this.synchronized {
            introduceContainer(stemCellNodejsKey, con)
        }
        logging.info(this, s"Started warm nodejs container ${con.id}: ${con.containerId}")
    } andThen {
        case Failure(t) => logging.warn(this, s"addStemCellNodejsContainer encountered an exception: ${t.getMessage}")
    }

    private def getStemCellNodejsContainer(key: ActionContainerId)(implicit transid: TransactionId): Option[WhiskContainer] =
        retrieve(stemCellNodejsKey) match {
            case CacheHit(con) =>
                logging.info(this, s"Obtained a pre-warmed container")
                con.transid = transid
                val Some(ci) = containerMap.get(con)
                changeKey(ci, stemCellNodejsKey, key)
                Some(con)
            case _ => None
        }

    // Obtain a container (by creation or promotion) and initialize by sending code.
    private def makeWhiskContainer(action: WhiskAction, auth: AuthKey)(implicit transid: TransactionId): WhiskContainer = {
        val imageName = getDockerImageName(action)
        val limits = action.limits
        val nodeImageName = NODEJS6_IMAGE.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
        val key = ActionContainerId(auth.uuid, action.fullyQualifiedName(true).toString, action.rev)
        val warmedContainer = if (limits.memory == defaultMemoryLimit && imageName == nodeImageName) getStemCellNodejsContainer(key) else None
        val containerName = makeContainerName(action)
        warmedContainer getOrElse {
            try {
                logging.info(this, s"making new container because none available")
                startingCounter.next()
                // only Exec instances that are subtypes of CodeExec reach the invoker
                makeGeneralContainer(key, containerName, imageName, limits, action.exec.asInstanceOf[CodeExec[_]].pull)
            } finally {
                val newCount = startingCounter.prev()
                logging.info(this, s"finished trying to make container, $newCount more containers to start")
            }
        }
    }

    // Make a container somewhat generically without introducing into data structure.
    // There is access to global settings (docker registry)
    // and generic settings (image name - static limits) but without access to WhiskAction.
    private def makeGeneralContainer(
        key: ActionContainerId, containerName: ContainerName,
        imageName: String, limits: ActionLimits, pull: Boolean)(
            implicit transid: TransactionId): WhiskContainer = {

        val network = config.invokerContainerNetwork
        val cpuShare = ContainerPool.cpuShare(config)
        val policy = config.invokerContainerPolicy
        val dnsServers = config.invokerContainerDns
        val env = getContainerEnvironment()

        // distinguishes between a black box container vs a whisk container
        def disambiguateContainerError[T](op: => T) = {
            try { op } catch {
                case e: ContainerError =>
                    throw if (pull) BlackBoxContainerError(e.msg) else WhiskContainerError(e.msg)
            }
        }

        if (pull) runDockerPull {
            disambiguateContainerError {
                // if pull fails, the transaction is aborted by throwing an exception;
                // a pull is only done for black box container
                ContainerUtils.pullImage(dockerhost, imageName)
            }
        }

        // This will start up the container
        runDockerOp {
            disambiguateContainerError {
                // because of the docker lock, by the time the container gets around to be started
                // there could be a container to reuse (from a previous run of the same action, or
                // from a stem cell container); should revisit this logic
                new WhiskContainer(transid, useRunc, this.dockerhost, mounted, key, containerName, imageName,
                    network, cpuShare, policy, dnsServers, env, limits)
            }
        }
    }

    // We send the payload here but eventually must also handle morphing a pre-allocated container into the right state.
    private def initWhiskContainer(action: WhiskAction, con: WhiskContainer)(implicit transid: TransactionId): RunResult = {
        // only Exec instances that are subtypes of CodeExec reach the invoker
        val Some(initArg) = action.containerInitializer
        // Then send it the init payload which is code for now
        con.init(initArg, action.limits.timeout.duration)
    }

    /**
     * Used through testing only. Creates a container running the command in `args`.
     */
    private def makeContainer(key: ActionContainerId, imageName: String, args: Array[String])(implicit transid: TransactionId): WhiskContainer = {
        runDockerOp {
            new WhiskContainer(transid, useRunc, this.dockerhost, mounted,
                key, makeContainerName("testContainer"), imageName,
                config.invokerContainerNetwork, ContainerPool.cpuShare(config),
                config.invokerContainerPolicy, config.invokerContainerDns, Map(), ActionLimits(), args)
        }
    }

    /**
     * Adds the container into the data structure in an Idle state.
     * The caller must have synchronized to maintain data structure atomicity.
     */
    private def introduceContainer(key: ActionContainerId, container: WhiskContainer)(implicit transid: TransactionId): ContainerInfo = {
        val ci = new ContainerInfo(key, container)
        keyMap.getOrElseUpdate(key, ListBuffer()) += ci
        containerMap += container -> ci
        dumpState("introduceContainer")
        ci
    }

    private def dumpState(prefix: String)(implicit transid: TransactionId) = {
        logging.debug(this, s"$prefix: keyMap = ${keyMapToString()}")
    }

    private def getDockerImageName(action: WhiskAction)(implicit transid: TransactionId): String = {
        // only Exec instances that are subtypes of CodeExec reach the invoker
        val exec = action.exec.asInstanceOf[CodeExec[_]]
        val imageName = if (!exec.pull) {
            exec.image.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
        } else exec.image.publicImageName
        logging.debug(this, s"Using image ${imageName}")
        imageName
    }

    private def getContainerEnvironment(): Map[String, String] = {
        Map("__OW_API_HOST" -> config.wskApiHost)
    }

    private val defaultMaxIdle = 10
    private val defaultGCThreshold = 600.seconds
    private val slowDockerThreshold = 500.millis
    private val slowDockerPullThreshold = 5.seconds

    val gcFrequency = 1000.milliseconds // this should not be leaked but a test needs this until GC count is implemented
    private var _maxIdle = defaultMaxIdle
    private var _maxActive = 0
    private var _gcThreshold = defaultGCThreshold
    private var gcOn = true
    private val gcSync = new Object()
    resetMaxActive()

    private val timer = new Timer()
    private val gcTask = new TimerTask {
        def run() {
            performGC()(TransactionId.invoker)
        }
    }
    timer.scheduleAtFixedRate(gcTask, 0, gcFrequency.toMillis)

    /**
     * Removes all idle containers older than the threshold.
     */
    private def performGC()(implicit transid: TransactionId) = {
        val expiration = System.currentTimeMillis() - gcThreshold.toMillis
        removeAllIdle({ containerInfo => containerInfo.lastUsed <= expiration })
        dumpState("performGC")
    }

    /**
     * Collects all containers that are in the idle state and pass the predicate.
     * gcSync is used to prevent multiple GC's.
     */
    private def removeAllIdle(pred: ContainerInfo => Boolean)(implicit transid: TransactionId) = {
        gcSync.synchronized {
            val idleInfo = this.synchronized {
                val idle = containerMap filter { case (container, ci) => ci.isIdle && pred(ci) }
                idle.keys foreach { con =>
                    logging.info(this, s"removeAllIdle removing container ${con.id}")
                }
                containerMap --= idle.keys
                keyMap foreach { case (key, ciList) => ciList --= idle.values }
                keyMap retain { case (key, ciList) => !ciList.isEmpty }
                idle.values
            }
            idleInfo.foreach { idleCi => toBeRemoved.offer(RemoveJob(!idleCi.isStemCell, idleCi)) }
        }
    }

    // Remove containerInfo from data structures but does not perform actual container operation.
    // Caller must establish synchronization
    private def removeContainerInfo(conInfo: ContainerInfo)(implicit transid: TransactionId) = {
        containerMap -= conInfo.container
        keyMap foreach { case (key, ciList) => ciList -= conInfo }
        keyMap retain { case (key, ciList) => !ciList.isEmpty }
    }

    private def removeOldestIdle()(implicit transid: TransactionId) = {
        // Note that the container removal - if any - is done outside the synchronized block
        val oldestIdle = this.synchronized {
            val idle = (containerMap filter { case (container, ci) => ci.isIdle })
            if (idle.isEmpty)
                List()
            else {
                val oldestConInfo = idle.minBy(_._2.lastUsed)._2
                logging.info(this, s"removeOldestIdle removing container ${oldestConInfo.container.id}")
                removeContainerInfo(oldestConInfo)
                List(oldestConInfo)
            }
        }
        oldestIdle.foreach { ci => toBeRemoved.offer(RemoveJob(!ci.isStemCell, ci)) }
    }

    // Getter/setter for this are above.
    private var _logDir = "/logs"
    private val actionContainerPrefix = "wsk"

    /**
     * Actually deletes the containers.
     */
    private def teardownContainer(removeJob: RemoveJob)(implicit transid: TransactionId) = try {
        val container = removeJob.containerInfo.container
        if (saveContainerLog) {
            val size = this.getLogSize(container, mounted)
            val rawLogBytes = container.synchronized {
                this.getDockerLogContent(container.containerId, 0, size, mounted)
            }
            val filename = s"${_logDir}/${container.nameAsString}.log"
            Files.write(Paths.get(filename), rawLogBytes)
            logging.info(this, s"teardownContainers: wrote docker logs to $filename")
        }
        container.transid = transid
        runDockerOp { container.remove(removeJob.needUnpause) }
    } catch {
        case t: Throwable => logging.warn(this, s"teardownContainer encountered an exception: ${t.getMessage}")
    }

    /**
     * Removes all containers with the actionContainerPrefix to kill leftover action containers.
     * This is needed for clean startup and shutdown.
     * Concurrent access from clients must be prevented externally.
     */
    private def killStragglers(allContainers: Seq[ContainerState])(implicit transid: TransactionId) = try {
        val candidates = allContainers.filter { _.name.name.startsWith(actionContainerPrefix) }
        logging.info(this, s"killStragglers now removing ${candidates.length} leftover containers")
        candidates foreach { c =>
            if (useRunc) {
                RuncUtils.resume(c.id)
            } else {
                unpauseContainer(c.name)
            }
            rmContainer(c.name)
        }
        logging.info(this, s"killStragglers completed")
    } catch {
        case t: Throwable => logging.warn(this, s"killStragglers encountered an exception: ${t.getMessage}")
    }

    /**
     * Gets the size of the mounted file associated with this whisk container.
     */
    def getLogSize(con: Container, mounted: Boolean)(implicit transid: TransactionId): Long = {
        getDockerLogSize(con.containerId, mounted)
    }

    nannyThread(listAll()(TransactionId.invokerWarmup)).start
    if (mounted) {
        sys addShutdownHook {
            logging.warn(this, "Shutdown hook activated.  Starting container shutdown")
            shutdown()
            logging.warn(this, "Shutdown hook completed.")
        }
    }

}

/*
 * These methods are parameterized on the configuration but defined here as an instance of ContainerPool is not
 * always available from other call sites.
 */
object ContainerPool {
    def requiredProperties = wskApiHost ++ Map(
        selfDockerEndpoint -> "localhost",
        dockerRegistry -> "",
        dockerImagePrefix -> "",
        dockerImageTag -> "latest",
        invokerContainerNetwork -> "bridge",
        invokerNumCore -> "4",
        invokerCoreShare -> "2",
        invokerSerializeDockerOp -> "true",
        invokerSerializeDockerPull -> "true",
        invokerUseRunc -> "false",
        invokerContainerPolicy -> "",
        invokerContainerDns -> "",
        invokerContainerNetwork -> null)

    /*
     * Extract parameters from whisk config.  In the future, these may not be static but
     * dynamically updated.  They still serve as a starting point for downstream parameters.
     */
    def numCore(config: WhiskConfig) = config.invokerNumCore.toInt
    def shareFactor(config: WhiskConfig) = config.invokerCoreShare.toInt

    /*
     * The total number of containers is simply the number of cores dilated by the cpu sharing.
     */
    def getDefaultMaxActive(config: WhiskConfig) = numCore(config) * shareFactor(config)

    /* The shareFactor indicates the number of containers that would share a single core, on average.
     * cpuShare is a docker option (-c) whereby a container's CPU access is limited.
     * A value of 1024 is the full share so a strict resource division with a shareFactor of 2 would yield 512.
     * On an idle/underloaded system, a container will still get to use underutilized CPU shares.
     */
    private val totalShare = 1024.0 // This is a pre-defined value coming from docker and not our hard-coded value.
    def cpuShare(config: WhiskConfig) = (totalShare / getDefaultMaxActive(config)).toInt

}
