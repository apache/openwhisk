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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.Timer
import java.util.TimerTask

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer

import whisk.common.Counter
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dockerImageTag
import whisk.core.WhiskConfig.invokerContainerNetwork
import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.entity.ActionLimits
import whisk.core.entity.MemoryLimit
import whisk.core.entity.TimeLimit
import whisk.core.entity.NodeJSExec
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntityStore

/*
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
    useWarmContainers: Boolean = true)
    extends ContainerUtils {

    val dockerhost = config.selfDockerEndpoint
    private val datastore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)

    // Eventually, we will have a more sophisticated warmup strategy that does multiple sizes
    private val defaultMemoryLimit = MemoryLimit(MemoryLimit.STD_MEMORY)

    /*
     * Set verbosity of this and owned objects.
     */
    override def setVerbosity(level: Verbosity.Level) = {
        super.setVerbosity(level)
        datastore.setVerbosity(level)
        authStore.setVerbosity(level)
    }

    /*
     * Enable/disable GC.  If disabled, overrides other flags/methods.
     */
    def enableGC() = gcOn = true
    def disableGC() = gcOn = false

    /*
     * Perform a GC immediately of all idle containers, blocking the caller until completed.
     */
    def forceGC()(implicit transid: TransactionId) = removeAllIdle({ containerInfo => true })

    /*
     * Getter/Setter for various GC paramters.
     */
    def gcThreshold: Double = _gcThreshold // seconds
    def maxIdle: Int = _maxIdle // container count
    def maxActive: Int = _maxActive // container count
    def gcThreshold_=(value: Double): Unit = _gcThreshold = Math.max(0.0, value)
    def maxIdle_=(value: Int): Unit = _maxIdle = Math.max(0, value)
    def maxActive_=(value: Int): Unit = _maxActive = Math.max(0, value)

    def resetMaxIdle() = _maxIdle = defaultMaxIdle
    def resetMaxActive() = _maxActive = defaultMaxActive
    def resetGCThreshold() = _gcThreshold = defaultGCThreshold

    /*
     * Controls where docker container logs are put.
     */
    def logDir: String = _logDir // seconds
    def logDir_=(value: String): Unit = _logDir = value

    /*
     * How many containers are in the pool at the moment?
     * There are also counts of containers we are trying to start but have not inserted into the data structure.
     */
    def idleCount() = countByState(State.Idle)
    def activeCount() = countByState(State.Active)
    private val startingCounter = new Counter()

    /*
     * Convenience method to list _ALL_ containers at this docker point with "docker ps -a --no-trunc".
     * This could include containers not in this pool at all.
     */
    def listAll()(implicit transid: TransactionId): Array[ContainerState] = listContainers(true)

    type RunResult = ContainerPool.RunResult

    /*
     * Retrieve (possibly create) a container based on the subject and versioned action.
     * A flag is included to indicate whether initialization succeeded.
     * The invariant of returning the container back to the pool holds regardless of whether init succeeded or not.
     * In case of failure to start a container, None is returned.
     */
    def getAction(action: WhiskAction, auth: WhiskAuth)(implicit transid: TransactionId): Option[(WhiskContainer, Option[RunResult])] = {
        info(this, s"Getting container for ${action.fullyQualifiedName} with ${auth.uuid}")
        val key = makeKey(action, auth)
        getImpl(key, { () => makeWhiskContainer(action, auth) }) map {
            case (c, initResult) =>
                val cacheMsg = if (!initResult.isDefined) "(Cache Hit)" else "(Cache Miss)"
                info(this, s"ContainerPool.getAction obtained container ${c.id} ${cacheMsg}")
                (c.asInstanceOf[WhiskContainer], initResult)
        }
    }

    def getByImageName(imageName: String, args: Array[String])(implicit transid: TransactionId): Option[Container] = {
        info(this, s"Getting container for image $imageName with args " + args.mkString(" "))
        val key = makeKey(imageName, args)
        getImpl(key, { () => makeContainer(imageName, args) }) map { _._1 }
    }

    /*
     * Try to get/create a container via the thunk by delegating to getOrMake.
     * This method will apply retry so that the caller is blocked until retry succeeds.
     * 
     */
    def getImpl(key: String, conMaker: () => ContainerResult)(implicit transid: TransactionId): Option[(Container, Option[RunResult])] = {
        getOrMake(key, conMaker) match {
            case Success(con, initResult) =>
                info(this, s"""Obtained container ${con.containerId.getOrElse("unknown")}""")
                return Some(con, initResult)
            case Error(str) =>
                error(this, s"Error starting container: $str")
                return None
            case Busy() =>
                Thread.sleep(100)
                getImpl(key, conMaker)
        }
    }

    def getNumberOfIdleContainers(key: String)(implicit transid: TransactionId): Int = {
        this.synchronized {
            keyMap.get(key) map { bucket => bucket.count { _.isIdle() } } getOrElse 0
        }
    }

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
    def getOrMake(key: String, conMaker: () => ContainerResult)(implicit transid: TransactionId): ContainerResult = {
        retrieve(key) match {
            case CacheMiss() => {
                this.synchronized {
                    if (activeCount() + startingCounter.cur >= _maxActive) // Someone could have fully started a container
                        return Busy()
                    if (startingCounter.cur >= 1) // Limit concurrent starting of containers
                        return Busy()
                    startingCounter.next()
                }
                try {
                    conMaker() match { /* We make the container outside synchronization */
                        // Unfortuantely, variables are not allowed in pattern alternatives even when the types line up.
                        case res @ Success(con, initResult) =>
                            this.synchronized {
                                val ci = introduceContainer(key, con)
                                ci.state = State.Active
                                res
                            }
                        case res @ Error(_) => return res
                        case Busy() =>
                            assert(false)
                            null // conMaker only returns Success or Error
                    }
                } finally {
                    startingCounter.prev()
                }
            }
            case s @ Success(con, initResult) =>
                con.unpause()
                s
            case other => other
        }
    }

    /*
     * Obtain a pre-existing container from the pool - transitioning it to Active state but without docker unpausing.
     * If we are over capacity, signal Busy.
     * If it does not exist ready to do, indicate a miss.
     */
    def retrieve(key: String)(implicit transid: TransactionId): ContainerResult = {
        this.synchronized {
            if (activeCount() + startingCounter.cur >= _maxActive)
                return Busy()
            if (!keyMap.contains(key))
                keyMap += key -> new ListBuffer()
            val bucket = keyMap.get(key).getOrElse(null)
            bucket.find({ ci => ci.isIdle() }) match {
                case None => CacheMiss()
                case Some(ci) => {
                    ci.state = State.Active
                    Success(ci.container, None)
                }
            }
        }
    }

    /*
     * Move a container from one bucket (i.e. key) to a different one.
     * This operation is performed when we specialize a pre-warmed container to an action.
     * ContainerMap does not need to be updated as the Container <-> ContainerInfo relationship does not change.
     */
    def changeKey(ci: ContainerInfo, oldKey: String, newKey: String)(implicit transid: TransactionId) = {
        this.synchronized {
            assert(ci.state == State.Active)
            assert(keyMap.contains(oldKey))
            if (!keyMap.contains(newKey))
                keyMap += newKey -> new ListBuffer()
            val oldBucket = keyMap.get(oldKey).getOrElse(null)
            val newBucket = keyMap.get(newKey).getOrElse(null)
            oldBucket -= ci
            newBucket += ci
        }
    }

    /*
     * Return the container to the pool.
     */
    def putBack(container: Container, delete: Boolean = false)(implicit transid: TransactionId): Unit = {
        info(this, s"ContainerPool.putBack returning container ${container.id}")
        this.synchronized {
            // Always put back logically for consistency
            val Some(ci) = containerMap.get(container)
            assert(ci.state == State.Active)
            // Perform GC at this point.
            if (gcOn) {
                while (idleCount() >= _maxIdle) {
                    removeOldestIdle()
                }
            }
            container.pause()
            ci.state = State.Idle
            ci.lastUsed = System.currentTimeMillis()
            // Finally delete if requested
            if (delete) {
                removeContainerInfo(ci)
                teardownContainers(List(ci))
            }
            this.notify()
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    object State extends Enumeration {
        val Idle, Active = Value
    }

    /*
     * Wraps a Container to allow a ContainerPool-specific information.
     */
    class ContainerInfo(k: String, con: Container) {
        val key = k
        val container = con
        var state = State.Idle
        var lastUsed = System.currentTimeMillis()
        def isIdle() = state == State.Idle
    }

    // The result of trying to start a class.  Option is too weak to do it.
    abstract class ContainerResult
    case class Success(con: Container, initResult: Option[RunResult]) extends ContainerResult
    case class CacheMiss() extends ContainerResult
    case class Busy() extends ContainerResult
    case class Error(string: String) extends ContainerResult

    private val LOG_SLACK = 150 // Delay for XX msec for docker output to show up.  Relevant only for teardown not regular running.
    private val containerMap = new TrieMap[Container, ContainerInfo]
    private val keyMap = new TrieMap[String, ListBuffer[ContainerInfo]]

    // Note that the prefix seprates the name space of this from regular keys.
    // TODO: Generalize across language by storing image name when we generalize to other languages
    //       Better heuristic for # of containers to keep warm - make sensitive to idle capacity
    private val warmNodejsKey = "warm.nodejs"
    private val nodejsExec = NodeJSExec("", None)
    private val WARM_NODEJS_CONTAINERS = 2

    private def makeKey(action: WhiskAction, auth: WhiskAuth) = {
        s"instantiated.${auth.uuid}.${action.fullyQualifiedName}.${action.rev}"
    }

    private def makeKey(imageName: String, args: Array[String]) = {
        "instantiated." + imageName + args.mkString("_")
    }

    private def keyMapToString(): String = {
        keyMap.foldLeft("") { case (acc, (key, ciList)) => acc + s"[$key -> $ciList]  " }
    }

    // Easier to walk containerMap than keyMap
    private def countByState(state: State.Value) = this.synchronized { containerMap.count({ case (_, ci) => ci.state == state }) }


    // Sample container name: wsk1_1_joeibmcomhelloWorldDemo_20150901T202701852Z
    private def makeContainerName(localName : String): String =
        ContainerCounter.containerName(invokerInstance.toString(), localName)

    private def makeContainerName(action: WhiskAction): String =
        makeContainerName(action.fullyQualifiedName)

    // A background thread that re-populates the container pool with fresh (un-instantiated) nodejs containers.
    private val warmupThread = new Thread {
        override def run {
            val tid = TransactionId.dontcare
            while (true) {
                if (getNumberOfIdleContainers(warmNodejsKey)(tid) < WARM_NODEJS_CONTAINERS) {
                    makeWarmNodejsContainer()(tid)
                }
                Thread.sleep(100)
            }
        }
    }

    private def makeWarmNodejsContainer()(implicit transid: TransactionId): WhiskContainer = {
        val imageName = WhiskAction.containerImageName(nodejsExec, config.dockerRegistry, config.dockerImageTag)
        val limits = ActionLimits(TimeLimit(), defaultMemoryLimit)
        val containerName = makeContainerName("warmJsContainer")
        val con = makeGeneralContainer(warmNodejsKey, containerName, imageName, limits)
        con.pause()
        this.synchronized {
            introduceContainer(warmNodejsKey, con)
        }
        info(this, s"ContainerPool: started warm nodejs container")
        con
    }

    private def getWarmNodejsContainer(key: String)(implicit transid: TransactionId): Option[WhiskContainer] = 
        retrieve(warmNodejsKey) match {
            case Success(con, _) =>
                info(this, s"Obtained a pre-warmed container")
                val Some(ci) = containerMap.get(con)
                changeKey(ci, warmNodejsKey, key)
                con.unpause()
                Some(con.asInstanceOf[WhiskContainer])
            case _ => None
        }

    // Obtain a container (by creation or promotion) and initialize by sending code.
    private def makeWhiskContainer(action: WhiskAction, auth: WhiskAuth)(implicit transid: TransactionId): ContainerResult = {
        val imageName = getDockerImageName(action)
        val limits = action.limits
        val nodeImageName = WhiskAction.containerImageName(nodejsExec, config.dockerRegistry, config.dockerImageTag)
        val key = makeKey(action, auth)
        val warmedContainer = if (limits.memory == defaultMemoryLimit && imageName == nodeImageName) getWarmNodejsContainer(key) else None
        val containerName = makeContainerName(action)
        val con = warmedContainer getOrElse makeGeneralContainer(key, containerName, imageName, limits)
        initWhiskContainer(action, con)
    }

    // Make a container somewhat generically without introducing into data structure.  
    // There is access to global settings (docker registry)
    // and generic settings (image name - static limits) but without access to WhiskAction.
    private def makeGeneralContainer(key : String, containerName : String, 
                                     imageName: String, limits : ActionLimits)(implicit transid: TransactionId): WhiskContainer = {
        val network = config.invokerContainerNetwork
        val env = getContainerEnvironment()
        val pull = !imageName.contains("whisk/")
        // This will start up the container
        val con = new WhiskContainer(this, key, containerName, imageName, network, pull, env, limits)
        info(this, s"ContainerPool: started container - about to send init")
        con
    }

    // We send the payload here but eventually must also handle morphing a pre-allocated container into the right state.
    private def initWhiskContainer(action: WhiskAction, con: WhiskContainer)(implicit transid: TransactionId): ContainerResult = {
        con.boundParams = action.parameters.toJsObject
        if (con.containerId.isDefined) {
            // Then send it the init payload which is code for now
            val initArg = action.containerInitializer
            val initResult = con.init(initArg)
            Success(con, Some(initResult))
        } else Error("failed to get id for container")
    }

    private def makeContainer(imageName: String, args: Array[String])(implicit transid: TransactionId): ContainerResult = {
        val con = new Container(this, makeKey(imageName, args), None, imageName, config.invokerContainerNetwork, false, ActionLimits(), Map(), args)
        con.setVerbosity(getVerbosity())
        Success(con, None)
    }

    /*
     * The caller must have synchronized to maintain data structure atomicity.
     * 
     * Add the container into the data structure in an Idle state.
     */
    private def introduceContainer(key: String, container: Container)(implicit transid: TransactionId): ContainerInfo = {
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
        val imageName = action.containerImageName(config.dockerRegistry, config.dockerImageTag)
        info(this, s"Using image ${imageName}")
        imageName
    }

    private def getContainerEnvironment(): Map[String, String] = {
        Map(WhiskConfig.asEnvVar(WhiskConfig.edgeHostName) -> config.edgeHost,
            WhiskConfig.asEnvVar(WhiskConfig.whiskVersionName) -> config.whiskVersion)
    }

    private val defaultMaxIdle = 10
    private val defaultMaxActive = 4
    private val defaultGCThreshold = 600.0 // seconds

    val gcFreqMilli = 1000 // this should not be leaked but a test needs this until GC count is implemented
    private var _maxIdle = defaultMaxIdle
    private var _maxActive = defaultMaxActive
    private var _gcThreshold = defaultGCThreshold
    private var gcOn = true
    private val gcSync = new Object()

    private val timer = new Timer()
    private val gcTask = new TimerTask {
        def run() {
            performGC()(TransactionId.dontcare)
        }
    }
    timer.scheduleAtFixedRate(gcTask, 0, gcFreqMilli)

    /*
     * Remove all idle containers older than the threshold.
     */
    private def performGC()(implicit transid: TransactionId) = {
        val expiration = System.currentTimeMillis() - (gcThreshold * 1000.0).toLong
        removeAllIdle({ containerInfo => containerInfo.lastUsed <= expiration })
        dumpState("performGC")
    }

    /*
     * Collect all containers that are in the idle state and pass the predicate.
     * gcSync is used to prevent multiple GC's.
     */
    private def removeAllIdle(pred: ContainerInfo => Boolean)(implicit transid: TransactionId) = {
        gcSync.synchronized {
            val idleInfo = this.synchronized {
                val idle = containerMap filter { case (container, ci) => ci.isIdle() && pred(ci) }
                idle.keys foreach { con =>
                    info(this, s"ContainerPool.removeAllIdle removing container ${con.id}")
                }
                containerMap --= idle.keys
                keyMap foreach { case (key, ciList) => ciList --= idle.values }
                keyMap retain { case (key, ciList) => !ciList.isEmpty }
                idle.values
            }
            teardownContainers(idleInfo)
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
                info(this, s"ContainerPool.removeOldestIdle removing container ${oldestConInfo.container.id}")
                removeContainerInfo(oldestConInfo)
                List(oldestConInfo)
            }
        }
        teardownContainers(oldestIdle)
    }

    // Getter/setter for this are above.
    private var _logDir = "/logs"
    private val actionContainerPrefix = "wsk"

    /*
     * Actually delete the containers.
     * The delay is needed for the forwarders.
     * TODO: Maybe use a Future so caller is not blocked.
     */
    private def teardownContainers(containers: Iterable[ContainerInfo])(implicit transid: TransactionId) = {
        Thread.sleep(LOG_SLACK)
        containers foreach { ci =>
            val logs = ci.container.getLogs()
            val conName = ci.container.name
            val filename = s"${_logDir}/${conName}.log"
            Files.write(Paths.get(filename), logs.getBytes(StandardCharsets.UTF_8))
            info(this, s"teardownContainers: wrote docker logs to $filename")
            ci.container.remove()
        }
    }

    /*
     * Remove all containers with the actionContainerPrefix to kill leftover action containers.
     * Useful for a hotswap.
     */
    def killStragglers()(implicit transid: TransactionId) =
        listAll.foreach({
            case ContainerState(id, image, name) => {
                if (name.startsWith(actionContainerPrefix)) {
                    unpauseContainer(name)
                    killContainer(name)
                }
            }
        })

    /*
     * Get the size of the mounted file associated with this whisk container.
     */
    def getLogSize(con: WhiskContainer, mounted: Boolean)(implicit transid: TransactionId): Long = {
        con.containerId map { id => getDockerLogSize(id, mounted) } getOrElse 0
    }

    if (useWarmContainers)
        warmupThread.start
}

object ContainerPool {
    def requiredProperties = Map(selfDockerEndpoint -> "localhost") ++ Map(dockerImageTag -> "latest") ++ Map(invokerContainerNetwork -> "bridge")
    type RunResult = (Instant, Instant, Option[(Int, String)])
}
