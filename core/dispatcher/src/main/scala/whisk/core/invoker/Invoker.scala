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

package whisk.core.invoker

import java.time.Clock
import java.time.Instant

import scala.Vector
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.matching.Regex.Match

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.actorRef2Scala
import akka.event.Logging.InfoLevel
import akka.event.Logging.LogLevel
import akka.japi.Creator
import spray.json.DefaultJsonProtocol
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Counter
import whisk.common.LoggingMarkers
import whisk.common.PrintStreamEmitter
import whisk.common.SimpleExec
import whisk.common.TransactionId
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.dockerRegistry
import whisk.core.WhiskConfig.dockerImagePrefix
import whisk.core.WhiskConfig.edgeHost
import whisk.core.WhiskConfig.kafkaHost
import whisk.core.WhiskConfig.logsDir
import whisk.core.WhiskConfig.servicePort
import whisk.core.WhiskConfig.whiskVersion
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.CompletionMessage
import whisk.core.container._
import whisk.core.dispatcher.ActivationFeed.ActivationNotification
import whisk.core.dispatcher.ActivationFeed.ContainerReleased
import whisk.core.dispatcher.ActivationFeed.FailedActivation
import whisk.core.dispatcher.DispatchRule
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ActivationResponse
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.LogLimit
import whisk.core.entity.Namespace
import whisk.core.entity.SemVer
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.core.entity.size.SizeLong
import whisk.core.entity.size.SizeString
import whisk.http.BasicHttpService
import whisk.utils.ExecutionContextFactory

/**
 * A kafka message handler that invokes actions as directed by message on topic "/actions/invoke".
 * The message path must contain a fully qualified action name and an optional revision id.
 *
 * @param config the whisk configuration
 * @param instance the invoker instance number
 * @param runningInContainer if false, invoker is run outside a container -- for testing
 */
class Invoker(
    config: WhiskConfig,
    instance: Int,
    activationFeed: ActorRef,
    verbosity: LogLevel = InfoLevel,
    runningInContainer: Boolean = true)(implicit actorSystem: ActorSystem)
    extends DispatchRule("invoker", "/actions/invoke", s"""(.+)/(.+)/(.+),(.+)/(.+)""") {

    private implicit val executionContext: ExecutionContext = actorSystem.dispatcher
    private implicit val emitter: PrintStreamEmitter = this

    /** This generates completion messages back to the controller */
    val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    override def setVerbosity(level: LogLevel) = {
        super.setVerbosity(level)
        pool.setVerbosity(level)
        entityStore.setVerbosity(level)
        authStore.setVerbosity(level)
        activationStore.setVerbosity(level)
        producer.setVerbosity(level)
    }

    /*
     * We track the state of the transaction by wrapping the Message object.
     * Note that var fields cannot be added to Message as it leads to serialization issues and
     * doesn't make sense to mix local mutable state with the value being passed around.
     *
     * See completeTransaction for why complete is needed.
     */
    case class Transaction(msg: Message) {
        var result: Option[Future[DocInfo]] = None
        var initInterval: Option[Interval] = None
        var runInterval: Option[Interval] = None
    }

    /**
     * This is the handler for the kafka message
     *
     * @param msg is the kafka message payload as Json
     * @param matches contains the regex matches
     */
    override def doit(topic: String, msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[DocInfo] = {
        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION)
        Future {
            // conformance checks can terminate the future if a variance is detected
            require(matches != null && matches.size >= 1, "matches undefined")
            require(matches(0).groupCount >= 3, "wrong number of matches")
            require(matches(0).group(2).nonEmpty, "action namespace undefined") // fully qualified name (namespace:name)
            require(matches(0).group(3).nonEmpty, "action name undefined") // fully qualified name (namespace:name)
            require(msg != null, "message undefined")

            val regex = matches(0)
            val namespace = Namespace(regex.group(2))
            val name = EntityName(regex.group(3))
            val version = if (regex.groupCount == 4) DocRevision(regex.group(4)) else DocRevision()
            val action = DocId(WhiskEntity.qualifiedName(namespace, name)).asDocInfo(version)
            action
        } flatMap {
            fetchFromStoreAndInvoke(_, msg) map { docInfo =>
                transid.finished(this, start)
                docInfo
            }
        }
    }

    /**
     * Common point for injection from Kafka or InvokerServer.
     */
    def fetchFromStoreAndInvoke(action: DocInfo, msg: Message)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val tran = Transaction(msg)
        val subject = msg.subject
        val payload = msg.content getOrElse JsObject()
        info(this, s"${action.id} $subject ${msg.activationId}")

        // caching is enabled since actions have revision id and an updated
        // action will not hit in the cache due to change in the revision id;
        // if the doc revision is missing, then bypass cache
        val actionFuture = WhiskAction.get(entityStore, action, action.rev != DocRevision())
        actionFuture onFailure {
            case t => error(this, s"failed to fetch action ${action.id}: ${t.getMessage}")
        }

        // keys are immutable, cache them
        val authFuture = WhiskAuth.get(authStore, subject, true)
        authFuture onFailure {
            case t => error(this, s"failed to fetch auth key for $subject: ${t.getMessage}")
        }

        // when records are fetched, invoke action
        val activationDocFuture = actionFuture flatMap { theAction =>
            // assume this future is done here
            authFuture flatMap { theAuth =>
                // assume this future is done here
                invokeAction(theAction, theAuth, payload, tran)
            }
        }

        activationDocFuture onComplete {
            case Success(activationDoc) =>
                info(this, s"recorded activation '$activationDoc'")
                activationDoc
            case Failure(t) =>
                info(this, s"failed to invoke action ${action.id} due to ${t.getMessage}")
                completeTransactionWithError(action, tran, s"failed to invoke action ${action.id}: ${t.getMessage}")
        }

        activationDocFuture
    }

    /*
     * Create a whisk activation out of the errorMsg and finish the transaction.
     * Failing with an error can involve multiple futures but the effecting call is completeTransaction which is guarded.
     */
    protected def completeTransactionWithError(actionDocInfo: DocInfo, tran: Transaction, errorMsg: String)(
        implicit transid: TransactionId): Unit = {
        error(this, errorMsg)
        val msg = tran.msg
        val name = EntityName(actionDocInfo.id().split(Namespace.PATHSEP)(1))
        val version = SemVer() // TODO: this is wrong, when the semver is passed from controller, fix this
        val payload = msg.content getOrElse JsObject()
        val response = Some(404, JsObject(ActivationResponse.ERROR_FIELD -> errorMsg.toJson).compactPrint)
        val activation = makeWhiskActivation(tran, false, msg, name, version, payload, response)
        completeTransaction(tran, activation, FailedActivation(transid))
    }

    /*
     * Action that must be taken when an activation completes (with or without error).
     *
     * Invariant: Only one call to here succeeds.  Even though the sync block wrap WhiskActivation.put,
     *            it is only blocking this transaction which is finishing anyway.
     */
    protected def completeTransaction(tran: Transaction, activation: WhiskActivation, releaseResource: ActivationNotification)(
        implicit transid: TransactionId): Future[DocInfo] = {
        tran.synchronized {
            tran.result match {
                case Some(res) => res
                case None => {
                    activationCounter.next() // this is the global invoker counter
                    incrementUserActivationCounter(tran.msg.subject)
                    // Send a message to the activation feed indicating there is a free resource to handle another activation.
                    // Since all transaction completions flow through this method and the invariant is that the transaction is
                    // completed only once, there is only one completion message sent to the feed as a result.
                    activationFeed ! releaseResource
                    // Since there is no active action taken for completion from the invoker, writing activation record is it.
                    info(this, "recording the activation result to the data store")
                    val result = WhiskActivation.put(activationStore, activation)
                    tran.result = Some(result)
                    result
                }
            }
        }
    }

    // These are related to initialization
    private val RegularSlack = 100.milliseconds
    private val BlackBoxSlack = 200.milliseconds

    protected def invokeAction(action: WhiskAction, auth: WhiskAuth, payload: JsObject, tran: Transaction)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val msg = tran.msg

        pool.getAction(action, auth) match {
            case Some((con, initResultOpt)) => Future {
                val params = con.mergeParams(payload)
                val timeout = action.limits.timeout.duration

                initResultOpt match {
                    case None => (false, con.run(params, msg.meta, auth.compact, timeout,
                        action.fullyQualifiedName, msg.activationId.toString)) // cached

                    case Some(RunResult(interval, Some((200, _)))) => { // successful init

                        // TODO:  @perryibm update comment if this is still necessary else remove
                        Thread.sleep((if (con.isBlackbox) BlackBoxSlack else RegularSlack).toMillis)

                        tran.initInterval = Some(interval)

                        (false, con.run(params, msg.meta, auth.compact, timeout,
                            action.fullyQualifiedName, msg.activationId.toString))
                    }
                    case _ => (true, initResultOpt.get) //  unsuccessful initialization
                }
            } flatMap {
                case (failedInit, RunResult(interval, response)) =>
                    if (!failedInit) tran.runInterval = Some(interval)

                    val activationResult = makeWhiskActivation(tran, con.isBlackbox, msg, action, payload, response)
                    val completeMsg = CompletionMessage(transid, activationResult)

                    producer.send("completed", completeMsg) map { status =>
                        info(this, s"posted completion of activation ${msg.activationId}")
                    }

                    val contents = getContainerLogs(con, action.exec.sentinelledLogs, action.limits.logs)

                    // Force delete the container instead of just pausing it iff the initialization failed or the container
                    // failed otherwise. An example of a ContainerError is the timeout of an action in which case the
                    // container is to be removed to prevent leaking
                    val removeContainer = failedInit || response.exists { _._1 == ActivationResponse.ContainerError }

                    pool.putBack(con, removeContainer)

                    completeTransaction(tran, activationResult withLogs ActivationLogs.serdes.read(contents), ContainerReleased(transid))
            }

            case None => { // this corresponds to the container not even starting - not /init failing
                info(this, s"failed to start or get a container")
                val response = Some(420, "Error starting container")
                val contents = JsArray(JsString("Error starting container"))
                val activation = makeWhiskActivation(tran, false, msg, action, payload, response)
                completeTransaction(tran, activation withLogs ActivationLogs.serdes.read(contents), FailedActivation(transid))
            }
        }
    }

    // The nodeJsAction runner inserts this line in the logs at the end
    // of each activation
    private val LogRetryCount = 15
    private val LogRetry = 100 // millis
    private val LOG_ACTIVATION_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"

    /**
     * Waits for log cursor to advance. This will retry up to tries times
     * if the cursor has not yet advanced. This will penalize containers that
     * do not log. It is OK for nodejs containers because the runtime emits
     * the END_OF_ACTIVATION_MARKER automatically and that advances the cursor.
     *
     * Note: Updates the container's log cursor to indicate consumption of log.
     */
    private def getContainerLogs(con: WhiskContainer, sentinelled: Boolean, limit: LogLimit, tries: Int = LogRetryCount)(implicit transid: TransactionId): JsArray = {
        val size = con.getLogSize(runningInContainer)
        val advanced = size != con.lastLogSize
        if (tries <= 0 || advanced) {
            val rawLogBytes = con.getDockerLogContent(con.lastLogSize, size, runningInContainer)
            val rawLog = new String(rawLogBytes, "UTF-8")

            val (complete, isTruncated, logs) = processJsonDriverLogContents(rawLog, sentinelled, limit)

            if (tries > 0 && !complete && !isTruncated) {
                info(this, s"log cursor advanced but missing sentinel, trying $tries more times")
                Thread.sleep(LogRetry)
                getContainerLogs(con, sentinelled, limit, tries - 1)
            } else {
                con.lastLogSize = size
                val formattedLogs = logs.map(_.toFormattedString)

                val finishedLogs = if (isTruncated) {
                    formattedLogs ++ Vector(s"Logs have been truncated because they exceeded the limit of ${limit.megabytes} megabytes")
                } else formattedLogs

                JsArray(finishedLogs.map(_.toJson))
            }
        } else {
            info(this, s"log cursor has not advanced, trying $tries more times")
            Thread.sleep(LogRetry)
            getContainerLogs(con, sentinelled, limit, tries - 1)
        }
    }

    /**
     * Represents a single log line as read from a docker log
     */
    private case class LogLine(time: String, stream: String, log: String) {
        def toFormattedString = f"$time%-30s $stream: ${log.trim}"
    }
    private object LogLine extends DefaultJsonProtocol {
        implicit val serdes = jsonFormat3(LogLine.apply)
    }

    /**
     * Given the JSON driver's raw output of a docker container, convert it into our own
     * JSON format. If asked, check for sentinel markers (which are not included in the output).
     *
     * Only parses and returns so much logs to fit into the LogLimit passed.
     *
     * @param logMsgs raw String read from a JSON log-driver written file
     * @param requireSentinel determines if the processor should wait for a sentinel to appear
     * @param limit the limit to apply to the log size
     *
     * @return Tuple containing (isComplete, isTruncated, logs)
     */
    private def processJsonDriverLogContents(logMsgs: String, requireSentinel: Boolean, limit: LogLimit)(
        implicit transid: TransactionId): (Boolean, Boolean, Vector[LogLine]) = {

        if (logMsgs.nonEmpty) {
            val records = logMsgs.split("\n").toStream flatMap {
                line =>
                    Try { line.parseJson.convertTo[LogLine] } match {
                        case Success(t) => Some(t)
                        case Failure(t) =>
                            // Drop lines that did not parse to JSON objects.
                            // However, should not happen since we are using the json log driver.
                            error(this, s"log line skipped/did not parse: $t")
                            None
                    }
            }

            val cumulativeSizes = records.scanLeft(0.bytes) { (acc, current) => acc + current.log.sizeInBytes }.tail
            val truncatedLogs = records.zip(cumulativeSizes).takeWhile(_._2 < limit().megabytes).map(_._1).toVector
            val isTruncated = truncatedLogs.size < records.size

            if (isTruncated) {
                (true, true, truncatedLogs)
            } else if (requireSentinel) {
                val (sentinels, regulars) = truncatedLogs.partition(_.log.trim == LOG_ACTIVATION_SENTINEL)
                val hasOut = sentinels.exists(_.stream == "stdout")
                val hasErr = sentinels.exists(_.stream == "stderr")
                (hasOut && hasErr, false, regulars)
            } else {
                (true, false, truncatedLogs)
            }
        } else {
            warn(this, s"log message is empty")
            (!requireSentinel, false, Vector())
        }
    }

    private def processResponseContent(isBlackbox: Boolean, response: Option[(Int, String)])(
        implicit transid: TransactionId): ActivationResponse = {
        response map {
            case (code, contents) =>
                debug(this, s"response: '$contents'")
                val json = Try { contents.parseJson.asJsObject }

                json match {
                    case Success(result @ JsObject(fields)) =>
                        // The 'error' field of the object, if it exists.
                        val errorOpt = fields.get(ActivationResponse.ERROR_FIELD)

                        if (code == 200) {
                            errorOpt map { error =>
                                ActivationResponse.applicationError(error)
                            } getOrElse {
                                // The happy path.
                                ActivationResponse.success(Some(result))
                            }
                        } else {
                            // Any non-200 code is treated as a container failure. We still need to check whether
                            // there was a useful error message in there.
                            val errorContent = errorOpt getOrElse {
                                JsString(s"The action invocation failed with the output: ${result.toString}")
                            }
                            ActivationResponse.containerError(errorContent)
                        }

                    case Success(notAnObj) =>
                        // This should affect only blackbox containers, since our own containers should already test for that.
                        ActivationResponse.containerError(s"the action 'result' value is not an object: ${notAnObj.toString}")

                    case Failure(t) =>
                        if (isBlackbox)
                            warn(this, s"response did not json parse: '$contents' led to $t")
                        else
                            error(this, s"response did not json parse: '$contents' led to $t")
                        ActivationResponse.containerError("the action did not produce a valid JSON response")
                }
        } getOrElse ActivationResponse.whiskError("failed to obtain action invocation response")
    }

    private def incrementUserActivationCounter(user: Subject): Int = {
        val count = userActivationCounter get user() match {
            case Some(counter) => counter.next()
            case None =>
                val counter = new Counter()
                counter.next()
                userActivationCounter(user()) = counter
                counter.cur
        }
        info(this, s"'${user}' has '$count' activations processed")
        count
    }

    private def getUserActivationCounts(): Map[String, JsObject] = {
        val subjects = userActivationCounter.keySet toList
        val groups = subjects.groupBy { user => user.substring(0, 1) } // Any sort of partitioning will be ok wrt load balancer
        groups.keySet map { prefix =>
            val key = InvokerKeys.userActivationCount(instance) + "/" + prefix
            val users = groups.getOrElse(prefix, Set())
            val items = users map { u => (u, JsNumber(userActivationCounter.get(u) map { c => c.cur } getOrElse 0)) }
            key -> JsObject(items toMap)
        } toMap
    }

    // -------------------------------------------------------------------------------------------------------------
    private def makeWhiskActivation(transaction: Transaction, isBlackbox: Boolean, msg: Message, action: WhiskAction,
                                    payload: JsObject, response: Option[(Int, String)])(
                                        implicit transid: TransactionId): WhiskActivation = {
        makeWhiskActivation(transaction, isBlackbox, msg, action.name, action.version, payload, response, action.limits.timeout.duration)
    }

    private def makeWhiskActivation(transaction: Transaction, isBlackbox: Boolean, msg: Message, actionName: EntityName,
                                    actionVersion: SemVer, payload: JsObject, response: Option[(Int, String)], timeout: Duration = Duration.Inf)(
                                        implicit transid: TransactionId): WhiskActivation = {

        // We reconstruct a plausible interval based on the time spent in the various operations.
        // The goal is for the interval to have a duration corresponding to the sum of all durations
        // and an endtime corresponding to the latest endtime.
        val interval: Interval = (transaction.initInterval, transaction.runInterval) match {
            case (None, Some(run))  => run
            case (Some(init), None) => init
            case (None, None)       => Interval(Instant.now(Clock.systemUTC()), Instant.now(Clock.systemUTC()))
            case (Some(init), Some(Interval(runStart, runEnd))) =>
                Interval(runStart.minusMillis(init.duration.toMillis), runEnd)
        }

        val activationResponse = if (interval.duration >= timeout) {
            ActivationResponse.applicationError(s"action exceeded its time limits of ${timeout.toMillis} milliseconds")
        } else {
            processResponseContent(isBlackbox, response)
        }

        WhiskActivation(
            namespace = msg.subject.namespace,
            name = actionName,
            version = actionVersion,
            publish = false,
            subject = msg.subject,
            activationId = msg.activationId,
            cause = msg.cause,
            start = interval.start,
            end = interval.end,
            response = activationResponse,
            logs = ActivationLogs())
    }

    private val entityStore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)
    private val activationStore = WhiskActivationStore.datastore(config)
    private val pool = new ContainerPool(config, instance, verbosity)
    private val activationCounter = new Counter() // global activation counter
    private val userActivationCounter = new TrieMap[String, Counter]

    /**
     * Repeatedly updates the KV store as to the invoker's last check-in.
     */
    private val kv = new ConsulClient(config.consulServer)
    private val reporter = new ConsulKVReporter(kv, 3 seconds, 2 seconds,
        InvokerKeys.hostname(instance),
        InvokerKeys.start(instance),
        InvokerKeys.status(instance),
        { index =>
            (if (index % 5 == 0) getUserActivationCounts() else Map[String, JsValue]()) ++
                Map(InvokerKeys.activationCount(instance) -> activationCounter.cur.toJson)
        })

    // This is used for the getContainer endpoint used in perfContainer testing it is not real state
    private val activationIdMap = new TrieMap[ActivationId, String]
    def putContainerName(activationId: ActivationId, containerName: String) = activationIdMap += (activationId -> containerName)
    def getContainerName(activationId: ActivationId): Option[String] = activationIdMap get activationId

    setVerbosity(verbosity)
}

object Invoker {
    /**
     * An object which records the environment variables required for this component to run.
     */
    def requiredProperties = Map(
        servicePort -> 8080.toString(),
        logsDir -> null,
        dockerRegistry -> null,
        dockerImagePrefix -> null) ++
        WhiskAuthStore.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties ++
        ContainerPool.requiredProperties ++
        kafkaHost ++
        edgeHost ++
        consulServer ++
        whiskVersion
}

object InvokerService {
    /**
     * An object which records the environment variables required for this component to run.
     */
    def requiredProperties = Invoker.requiredProperties

    def main(args: Array[String]): Unit = {
        implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
        implicit val system: ActorSystem = ActorSystem(
            name = "invoker-actor-system",
            defaultExecutionContext = Some(ec))

        // load values for the required properties from the environment
        val config = new WhiskConfig(requiredProperties)

        if (config.isValid) {
            val instance = if (args.length > 0) args(1).toInt else 0
            val verbosity = InfoLevel

            SimpleExec.setVerbosity(verbosity)

            val topic = s"invoke${instance}"
            val groupid = "invokers"
            val maxdepth = ContainerPool.defaultMaxActive
            val consumer = new KafkaConsumerConnector(config.kafkaHost, groupid, topic, maxdepth)
            val dispatcher = new Dispatcher(verbosity, consumer, 500 milliseconds, 2 * maxdepth, system)

            val invoker = new Invoker(config, instance, dispatcher.activationFeed, verbosity)
            dispatcher.addHandler(invoker, true)
            dispatcher.start()

            val port = config.servicePort.toInt
            BasicHttpService.startService(system, "invoker", "0.0.0.0", port, new Creator[InvokerServer] {
                def create = new InvokerServer {}
            })
        }
    }
}
