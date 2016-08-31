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

package whisk.core.container

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.annotation.tailrec
import akka.actor.ActorSystem
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TimingUtil
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dockerImageTag
import whisk.core.WhiskConfig.invokerContainerNetwork
import whisk.core.WhiskConfig.invokerContainerPolicy
import whisk.core.WhiskConfig.invokerCoreShare
import whisk.core.WhiskConfig.invokerNumCore
import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.entity.ActionLimits
import whisk.core.entity.MemoryLimit
import whisk.core.entity.LogLimit
import whisk.core.entity.TimeLimit
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.NodeJS6Exec
import akka.event.Logging.LogLevel
import akka.event.Logging.InfoLevel
import whisk.core.entity.BlackBoxExec


/**
 * A thread-safe container pool that internalizes container creation/teardown and allows users
 * to check out a container.
 *
 * Synchronization via "this" is used to maintain integrity of the data structures.
 * A separate object "gcSync" is used to prevent multiple GC's from occurring.
 *
 * TODO: for now supports only one container per key
 * TODO: for now does not allow concurrent container creation
 */
class ContainerPool(
    config: WhiskConfig,
    invokerInstance: Integer = 0,
    verbosity: LogLevel = InfoLevel,
    standalone: Boolean = false)(implicit actorSystem: ActorSystem)
    extends ContainerUtils {

    // These must be defined before verbosity is set
    private val datastore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)
    setVerbosity(verbosity)

    val dockerhost = config.selfDockerEndpoint

    // Eventually, we will have a more sophisticated warmup strategy that does multiple sizes
    private val defaultMemoryLimit = MemoryLimit(MemoryLimit.STD_MEMORY)

    /**
     * Sets verbosity of this and owned objects.
     */
    override def setVerbosity(level: LogLevel) = {
        super.setVerbosity(level)
        datastore.setVerbosity(level)
        authStore.setVerbosity(level)
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
        info(this, s"maxActive set to ${_maxActive}")
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
    def listAll()(implicit transid: TransactionId): Array[ContainerState] = listContainers(true)

    /**
     * Retrieves (possibly create) a container based on the subject and versioned action.
     * A flag is included to indicate whether initialization succeeded.
     * The invariant of returning the container back to the pool holds regardless of whether init succeeded or not.
     * In case of failure to start a container, None is returned.
     */
    def getAction(action: WhiskAction, auth: WhiskAuth)(implicit transid: TransactionId): Option[(WhiskContainer, Option[RunResult])] =
        if (shuttingDown) {
            info(this, s"Shutting down: Not getting container for ${action.fullyQualifiedName} with ${auth.uuid}")
            None
        } else {
            try {
                val myPos = nextPosition.next()
                info(this, s"""Getting container for ${action.fullyQualifiedName} of kind ${action.exec.kind} with ${auth.uuid}:
                              | myPos = $myPos
                              | completed = ${completedPosition.cur}
                              | slack = ${slack()}
                              | startingCounter = ${startingCounter.cur}""".stripMargin)
                val key = ActionContainerId(auth.uuid, action.fullyQualifiedName, action.rev)
                getImpl(0, myPos, key, () => makeWhiskContainer(action, auth)) map {
                    case (c, initResult) =>
                        val cacheMsg = if (!initResult.isDefined) "(Cache Hit)" else "(Cache Miss)"
                        (c.asInstanceOf[WhiskContainer], initResult)
                }
            } finally {
                completedPosition.next()
            }
        }

    /*
     * For testing
     */
    def getByImageName(imageName: String, args: Array[String])(implicit transid: TransactionId): Option[Container] = {
        info(this, s"Getting container for image $imageName with args " + args.mkString(" "))
        // Not a regular key. Doesn't matter in testing.
        val key = new ActionContainerId(s"instantiated." + imageName + args.mkString("_"))
        getImpl(0, 0, key, () => makeContainer(key, imageName, args)) map { _._1 }
    }

    /**
     * Tries to get/create a container via the thunk by delegating to getOrMake.
     * This method will apply retry so that the caller is blocked until retry succeeds.
     */
    @tailrec
    final def getImpl(tryCount: Int, position: Int, key: ActionContainerId, conMaker: () => FinalContainerResult)(implicit transid: TransactionId): Option[(Container, Option[RunResult])] = {
        val positionInLine = position - completedPosition.cur // this will be 1 if at the front of the line
        val available = slack()
        if (tryCount == 100) {
            warn(this, s"""getImpl possibly stuck because still in line:
                          | position = $position
                          | completed = ${completedPosition.cur}")
                          | slack = $available
                          | maxActive = ${_maxActive}
                          | activeCount = ${activeCount()}
                          | startingCounter = ${startingCounter.cur}""".stripMargin)
        }
        if (positionInLine > available) { // e.g. if there is 1 available, then I wait if I am second in line (positionInLine = 2)
            Thread.sleep(50) // TODO: replace with wait/notify but tricky to get right because of desire for maximal concurrency
        } else getOrMake(key, conMaker) match {
            case Success(con, initResult) =>
                info(this, s"Obtained container ${con.containerId.getOrElse("unknown")}")
                return Some(con, initResult)
            case Error(str) =>
                error(this, s"Error starting container: $str")
                return None
            case Busy =>
            // This will not cause a busy loop because only those that could be productive will get a chance
        }
        getImpl(tryCount + 1, position, key, conMaker)
    }

    def getNumberOfIdleContainers(key: ActionContainerId)(implicit transid: TransactionId): Int = {
        this.synchronized {
            keyMap.get(key) map { bucket => bucket.count { _.isIdle() } } getOrElse 0
        }
    }

    /*
     * How many containers can we start?  Someone could have fully started a container so we must include startingCounter.
     * The use of a method rather than a getter is meant to signify the synchronization in the implementation.
     */
    private def slack() = _maxActive - (activeCount() + startingCounter.cur)

    /*
     * Try to get or create a container, returning None if there are too many
     * active containers.
     *
     * The multiple synchronization block, and the use of startingCounter,
     * is needed to make sure container count is accurately tracked,
     * data structure maintains integrity, but to keep all length operations
     * outside of the lock.
     *
     * The returned container will be active (not pause).
     */
    def getOrMake(key: ActionContainerId, conMaker: () => FinalContainerResult)(implicit transid: TransactionId): FinalContainerResult = {
        retrieve(key) match {
            case CacheMiss => {
                startingCounter.next()
                try {
                    conMaker() match { /* We make the container outside synchronization */
                        // Unfortunately, variables are not allowed in pattern alternatives even when the types line up.
                        case res @ Success(con, initResult) =>
                            this.synchronized {
                                val ci = introduceContainer(key, con)
                                ci.state = State.Active
                                res
                            }
                        case res @ Error(_) => return res
                        case Busy =>
                            assert(false)
                            null // conMaker only returns Success or Error
                    }
                } finally {
                    startingCounter.prev()
                }
            }
            case s @ Success(con, initResult) =>
                con.transid = transid
                runDockerOp { con.unpause() }
                s
            case b @ Busy     => b
            case e @ Error(_) => e
        }
    }

    /**
     * Obtains a pre-existing container from the pool - and putting it to Active state but without docker unpausing.
     * If we are over capacity, signal Busy.
     * If it does not exist ready to do, indicate a miss.
     */
    def retrieve(key: ActionContainerId)(implicit transid: TransactionId): ContainerResult = {
        this.synchronized {
            if (activeCount() + startingCounter.cur >= _maxActive) {
                Busy
            } else {
                if (!keyMap.contains(key))
                    keyMap += key -> new ListBuffer()

                val bucket = keyMap(key)
                bucket.find({ ci => ci.isIdle() }) match {
                    case None => CacheMiss
                    case Some(ci) => {
                        ci.state = State.Active
                        Success(ci.container, None)
                    }
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
            if (!keyMap.contains(newKey)) {
                keyMap += newKey -> new ListBuffer()
            }
            val oldBucket = keyMap(oldKey)
            val newBucket = keyMap(newKey)
            oldBucket -= ci
            newBucket += ci
        }
    }

    /**
     * Returns the container to the pool or delete altogether.
     * This call can be slow but not while locking data structure so it does not interfere with other activations.
     */
    def putBack(container: Container, delete: Boolean = false)(implicit transid: TransactionId): Unit = {
        info(this, s"putBack returning container ${container.id}  delete = $delete   slack = ${slack()}")
        // Docker operation outside sync block. Don't pause if we are deleting.
        if (!delete) {
            runDockerOp { container.pause() }
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
        toBeDeleted.foreach(toBeRemoved.offer(_))
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
    class ContainerInfo(k: ActionContainerId, con: Container) {
        val key = k
        val container = con
        var state = State.Idle
        var lastUsed = System.currentTimeMillis()
        def isIdle() = state == State.Idle
    }

    private val containerMap = new TrieMap[Container, ContainerInfo]
    private val keyMap = new TrieMap[ActionContainerId, ListBuffer[ContainerInfo]]

    // These are containers that are already removed from the data structure waiting to be docker-removed
    private val toBeRemoved = new ConcurrentLinkedQueue[ContainerInfo]()

    // Note that the prefix separates the name space of this from regular keys.
    // TODO: Generalize across language by storing image name when we generalize to other languages
    //       Better heuristic for # of containers to keep warm - make sensitive to idle capacity
    private val warmNodejsKey = WarmNodeJsActionContainerId
    private val nodejsExec = NodeJS6Exec("", None)
    private val WARM_NODEJS_CONTAINERS = 2

    private def keyMapToString(): String = {
        keyMap.map(p => s"[${p._1.stringRepr} -> ${p._2}]").mkString("  ")
    }

    // Easier to walk containerMap than keyMap
    private def countByState(state: State.Value) = this.synchronized { containerMap.count({ case (_, ci) => ci.state == state }) }

    // Sample container name: wsk1_1_joeibmcomhelloWorldDemo_20150901T202701852Z
    private def makeContainerName(localName: String): String =
        ContainerCounter.containerName(invokerInstance.toString(), localName)

    private def makeContainerName(action: WhiskAction): String =
        makeContainerName(action.fullyQualifiedName)

    /**
     * dockerLock is used to serialize all docker operations except pull.
     * However, a non-pull operation can run concurrently with a pull operation.
     */
    val dockerLock = new Object()

    /**
     * dockerPullLock is used to serialize all pull operations.
     */
    val dockerPullLock = new Object()

    /* A background thread that
     *   1. Kills leftover action containers on startup
     *   2. Periodically re-populates the container pool with fresh (un-instantiated) nodejs containers.
     *   3. Periodically tears down containers that have logically been removed from the system
     */
    private def nannyThread(allContainers: Array[ContainerState]) = new Thread {
        override def run {
            implicit val tid = TransactionId.invokerWarmup
            if (!standalone) killStragglers(allContainers)
            while (true) {
                Thread.sleep(100) // serves to prevent busy looping
                if (!standalone && getNumberOfIdleContainers(warmNodejsKey) < WARM_NODEJS_CONTAINERS) {
                    makeWarmNodejsContainer()(tid)
                }
                // We grab the size first so we know there has been enough delay for anything we are shutting down
                val size = toBeRemoved.size()
                1 to size foreach { _ =>
                    val ci = toBeRemoved.poll()
                    if (ci != null) { // should never happen but defensive
                        Thread.sleep(100) // serves to not hog docker lock and add slack
                        teardownContainer(ci.container)
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
    private def runDockerOp[T](dockerOp: => T): T = {
        val (elapsed, result) = TimingUtil.time {
            dockerLock.synchronized {
                dockerOp
            }
        }
        if (elapsed > slowDockerThreshold) {
            warn(this, s"Docker operation took $elapsed")
        }
        result
    }

    /**
     * All pull operations from the pool must pass through here.
     */
    private def runDockerPull[T](dockerOp: => T): T = {
        dockerPullLock.synchronized {
            dockerOp
        }
    }

    private def makeWarmNodejsContainer()(implicit transid: TransactionId): WhiskContainer = {
        val imageName = WhiskAction.containerImageName(nodejsExec, config.dockerRegistry, config.dockerImagePrefix, config.dockerImageTag)
        val limits = ActionLimits(TimeLimit(), defaultMemoryLimit, LogLimit())
        val containerName = makeContainerName("warmJsContainer")
        val con = makeGeneralContainer(warmNodejsKey, containerName, imageName, limits, false)
        if (con.containerId.isDefined) {
            this.synchronized {
                introduceContainer(warmNodejsKey, con)
            }
            info(this, "Started warm nodejs container")
        } else error(this, "Error starting warm container")
        con
    }

    private def getWarmNodejsContainer(key: ActionContainerId)(implicit transid: TransactionId): Option[WhiskContainer] =
        retrieve(warmNodejsKey) match {
            case Success(con, _) =>
                info(this, s"Obtained a pre-warmed container")
                con.transid = transid
                val Some(ci) = containerMap.get(con)
                changeKey(ci, warmNodejsKey, key)
                Some(con.asInstanceOf[WhiskContainer])
            case _ => None
        }

    // Obtain a container (by creation or promotion) and initialize by sending code.
    private def makeWhiskContainer(action: WhiskAction, auth: WhiskAuth)(implicit transid: TransactionId): FinalContainerResult = {
        val imageName = getDockerImageName(action)
        val limits = action.limits
        val nodeImageName = WhiskAction.containerImageName(nodejsExec, config.dockerRegistry, config.dockerImagePrefix, config.dockerImageTag)
        val key = ActionContainerId(auth.uuid, action.fullyQualifiedName, action.rev)
        val warmedContainer = if (limits.memory == defaultMemoryLimit && imageName == nodeImageName) getWarmNodejsContainer(key) else None
        val containerName = makeContainerName(action)
        val con = warmedContainer getOrElse makeGeneralContainer(key, containerName, imageName, limits, action.exec.kind == BlackBoxExec)
        initWhiskContainer(action, con)
    }

    // Make a container somewhat generically without introducing into data structure.
    // There is access to global settings (docker registry)
    // and generic settings (image name - static limits) but without access to WhiskAction.
    private def makeGeneralContainer(key: ActionContainerId, containerName: String,
                                     imageName: String, limits: ActionLimits, pull: Boolean)(implicit transid: TransactionId): WhiskContainer = {
        val network = config.invokerContainerNetwork
        val cpuShare = ContainerPool.cpuShare(config)
        val policy = config.invokerContainerPolicy
        val env = getContainerEnvironment()
        // This will start up the container
        if (pull) runDockerPull {
            ContainerUtils.pullImage(dockerhost, imageName)
        }
        runDockerOp {
            new WhiskContainer(transid, this, key, containerName, imageName, network, cpuShare, policy, env, limits, isBlackbox = pull)
        }
    }

    // We send the payload here but eventually must also handle morphing a pre-allocated container into the right state.
    private def initWhiskContainer(action: WhiskAction, con: WhiskContainer)(implicit transid: TransactionId): FinalContainerResult = {
        con.boundParams = action.parameters.toJsObject
        if (con.containerId.isDefined) {
            // Then send it the init payload which is code for now
            val initArg = action.containerInitializer
            val initResult = con.init(initArg, action.limits.timeout.duration)
            Success(con, Some(initResult))
        } else Error("failed to get id for container")
    }

    /**
     * Used through testing only. Creates a container running the command in `args`.
     */
    private def makeContainer(key: ActionContainerId, imageName: String, args: Array[String])(implicit transid: TransactionId): FinalContainerResult = {
        val con = runDockerOp {
            new Container(transid, this, key, None, imageName,
                config.invokerContainerNetwork, ContainerPool.cpuShare(config), config.invokerContainerPolicy, ActionLimits(), Map(), args)
        }
        con.setVerbosity(getVerbosity())
        Success(con, None)
    }

    /**
     * Adds the container into the data structure in an Idle state.
     * The caller must have synchronized to maintain data structure atomicity.
     */
    private def introduceContainer(key: ActionContainerId, container: Container)(implicit transid: TransactionId): ContainerInfo = {
        val ci = new ContainerInfo(key, container)
        if (keyMap.contains(key))
            keyMap.get(key).getOrElse(null) += ci // will not be null
        else
            keyMap += key -> ListBuffer(ci)
        containerMap += container -> ci
        dumpState("introduceContainer")
        ci
    }

    private def dumpState(prefix: String)(implicit transid: TransactionId) = {
        debug(this, s"$prefix: keyMap = ${keyMapToString()}")
    }

    private def getDockerImageName(action: WhiskAction): String = {
        val imageName = action.containerImageName(config.dockerRegistry, config.dockerImagePrefix, config.dockerImageTag)
        info(this, s"Using image ${imageName}")
        imageName
    }

    private def getContainerEnvironment(): Map[String, String] = {
        Map(WhiskConfig.asEnvVar(WhiskConfig.edgeHostName) -> config.edgeHost)
    }

    private val defaultMaxIdle = 10
    private val defaultGCThreshold = 600.seconds
    private val slowDockerThreshold = 500.millis

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
                val idle = containerMap filter { case (container, ci) => ci.isIdle() && pred(ci) }
                idle.keys foreach { con =>
                    info(this, s"removeAllIdle removing container ${con.id}")
                }
                containerMap --= idle.keys
                keyMap foreach { case (key, ciList) => ciList --= idle.values }
                keyMap retain { case (key, ciList) => !ciList.isEmpty }
                idle.values
            }
            idleInfo.foreach(toBeRemoved.offer(_))
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
            val idle = (containerMap filter { case (container, ci) => ci.isIdle() })
            if (idle.isEmpty)
                List()
            else {
                val oldestConInfo = idle.minBy(_._2.lastUsed)._2
                info(this, s"removeOldestIdle removing container ${oldestConInfo.container.id}")
                removeContainerInfo(oldestConInfo)
                List(oldestConInfo)
            }
        }
        oldestIdle.foreach(toBeRemoved.offer(_))
    }

    // Getter/setter for this are above.
    private var _logDir = "/logs"
    private val actionContainerPrefix = "wsk"

    /**
     * Actually deletes the containers.
     */
    private def teardownContainer(container: Container)(implicit transid: TransactionId) = {
        val size = container.getLogSize(!standalone)
        val rawLogBytes = container.getDockerLogContent(0, size, !standalone)
        val filename = s"${_logDir}/${container.name}.log"
        Files.write(Paths.get(filename), rawLogBytes)
        info(this, s"teardownContainers: wrote docker logs to $filename")
        runDockerOp { container.remove() }
    }

    /**
     * Removes all containers with the actionContainerPrefix to kill leftover action containers.
     * This is needed for startup and shutdown.
     * Concurrent access from clients must be prevented.
     */
    private def killStragglers(allContainers: Array[ContainerState])(implicit transid: TransactionId) = {
        val candidates = allContainers.filter { case ContainerState(id, image, name) => name.startsWith(actionContainerPrefix) }
        info(this, s"Now removing ${candidates.length} leftover containers")
        candidates foreach {
            case ContainerState(id, image, name) => {
                unpauseContainer(name)
                rmContainer(name)
            }
        }
        info(this, s"Leftover container removal completed")
    }

    /**
     * Gets the size of the mounted file associated with this whisk container.
     */
    def getLogSize(con: Container, mounted: Boolean)(implicit transid: TransactionId): Long = {
        con.containerId map { id => getDockerLogSize(id, mounted) } getOrElse 0
    }

    nannyThread(listAll()(TransactionId.invokerWarmup)).start
    if (!standalone) {
        sys addShutdownHook {
            warn(this, "Shutdown hook activated.  Starting container shutdown")
            shutdown()
            warn(this, "Shutdown hook completed.")
        }
    }

}


/*
 * These methods are parameterized on the configuration but defined here as an instance of ContainerPool is not
 * always available from other call sites.
 */
object ContainerPool extends Logging {
    def requiredProperties = Map(selfDockerEndpoint -> "localhost",
                                 dockerImageTag -> "latest",
                                 invokerContainerNetwork -> "bridge",
                                 invokerNumCore -> "4",
                                 invokerCoreShare -> "2",
                                 invokerContainerPolicy -> "")

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
    private val totalShare = 1024.0    // This is a pre-defined value coming from docker and not our hard-coded value.
    def cpuShare(config: WhiskConfig) = (totalShare / getDefaultMaxActive(config)).toInt

}
