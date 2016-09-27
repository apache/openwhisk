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

import java.time.{ Clock, Instant }

import scala.Vector
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.language.postfixOps
import scala.util.{ Failure, Success, Try }

import akka.actor.{ ActorRef, ActorSystem, actorRef2Scala }
import akka.event.Logging.{ InfoLevel, LogLevel }
import akka.japi.Creator
import spray.json.{ JsArray, JsNumber, JsObject, JsValue, pimpAny, pimpString }
import spray.json.DefaultJsonProtocol._
import spray.json.DefaultJsonProtocol
import whisk.common.{ ConsulKVReporter, Counter, Logging, LoggingMarkers, PrintStreamEmitter, SimpleExec, TransactionId }
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.connector.kafka.{ KafkaConsumerConnector, KafkaProducerConnector }
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{ consulServer, dockerImagePrefix, dockerRegistry, edgeHost, kafkaHost, logsDir, servicePort, whiskVersion }
import whisk.core.connector.{ ActivationMessage => Message, CompletionMessage }
import whisk.core.container.{ BlackBoxContainerError, ContainerPool, Interval, RunResult, WhiskContainer, WhiskContainerError }
import whisk.core.dispatcher.{ Dispatcher, MessageHandler }
import whisk.core.dispatcher.ActivationFeed.{ ActivationNotification, ContainerReleased, FailedActivation }
import whisk.core.entity.{ ActionLimits, ActivationLogs, ActivationResponse, AuthKey, DocId, DocInfo, DocRevision, EntityPath, Exec, LogLimit, Parameters, SemVer, Subject, WhiskAction, WhiskActivation, WhiskActivationStore, WhiskAuthStore, WhiskEntity, WhiskEntityStore }
import whisk.core.entity.size.{ SizeInt, SizeString }
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
    extends MessageHandler(s"invoker$instance")
    with Logging {

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

    /**
     * This is the handler for the kafka message
     *
     * @param msg is the kafka message payload as Json
     * @param matches contains the regex matches
     */
    override def onMessage(msg: Message)(implicit transid: TransactionId): Future[DocInfo] = {
        require(msg != null, "message undefined")
        require(msg.action.version.isDefined, "action version undefined")

        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION)
        val namespace = msg.action.path
        val name = msg.action.name
        val actionid = DocId(WhiskEntity.qualifiedName(namespace, name)).asDocInfo(msg.revision)
        val tran = Transaction(msg)
        val subject = msg.subject
        val payload = msg.content getOrElse JsObject()

        info(this, s"${actionid.id} $subject ${msg.activationId}")

        // caching is enabled since actions have revision id and an updated
        // action will not hit in the cache due to change in the revision id;
        // if the doc revision is missing, then bypass cache
        WhiskAction.get(entityStore, actionid.id, actionid.rev, actionid.rev != DocRevision()) recoverWith {
            case t =>
                error(this, s"failed to fetch action from db: ${t.getMessage}")
                Future.failed(ActivationException(s"Failed to fetch action."))
        } flatMap {
            invokeAction(_, msg.authkey, payload, tran)
        } recoverWith {
            case t =>
                val failure = t match {
                    // in case of container pull/run operations that fail to execute, assign an appropriate error response
                    case BlackBoxContainerError(msg) => ActivationException(msg, internalError = false)
                    case WhiskContainerError(msg)    => ActivationException(msg)
                    case _ =>
                        error(this, s"failed during invoke: $t")
                        ActivationException(s"Failed to run action '${actionid.id}': ${t.getMessage}")
                }
                info(this, s"activation failed")
                completeTransactionWithError(actionid.id, msg.action.version.get, tran, failure.activationResponse)
        }
    }

    /*
     * Creates a whisk activation out of the errorMsg and finish the transaction.
     * Failing with an error can involve multiple futures but the effecting call is completeTransaction which is guarded.
     */
    protected def completeTransactionWithError(name: DocId, version: SemVer, tran: Transaction, response: ActivationResponse)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val msg = tran.msg
        val interval = computeActivationInterval(tran)
        val activation = makeWhiskActivation(msg, EntityPath(name.id), version, response, interval, None)
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
                    val result = WhiskActivation.put(activationStore, activation) andThen {
                        case Success(id) => info(this, s"recorded activation")
                        case Failure(t)  => error(this, s"failed to record activation")
                    }
                    tran.result = Some(result)
                    result
                }
            }
        }
    }

    protected def invokeAction(action: WhiskAction, auth: AuthKey, payload: JsObject, tran: Transaction)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val msg = tran.msg

        pool.getAction(action, auth) match {
            case Some((con, initResultOpt)) => Future {
                val params = con.mergeParams(payload)
                val timeout = action.limits.timeout.duration
                def run() = con.run(params, msg.meta, auth.compact, timeout, action.fullyQualifiedName, msg.activationId.toString)

                initResultOpt match {
                    // cached container
                    case None => (false, run())

                    // new container
                    case Some(RunResult(interval, response)) =>
                        tran.initInterval = Some(interval)
                        response match {
                            // successful init
                            case Some((200, _)) => (false, run())
                            // unsuccessful initialization
                            case _              => (true, initResultOpt.get)
                        }
                }
            } flatMap {
                case (failedInit, RunResult(interval, response)) =>
                    // it is possible for response to be None if the container timed out
                    if (!failedInit) tran.runInterval = Some(interval)

                    val activationInterval = computeActivationInterval(tran)
                    val activationResponse = getActivationResponse(activationInterval, action.limits.timeout.duration, response, failedInit)
                    val activationResult = makeWhiskActivation(msg, EntityPath(action.docid.id), action.version, activationResponse, activationInterval, Some(action.limits))
                    val completeMsg = CompletionMessage(transid, activationResult)

                    producer.send("completed", completeMsg) map { status =>
                        info(this, s"posted completion of activation ${msg.activationId}")
                    }

                    val contents = getContainerLogs(con, action.exec.sentinelledLogs, action.limits.logs)

                    // Force delete the container instead of just pausing it iff the initialization failed or the container
                    // failed otherwise. An example of a ContainerError is the timeout of an action in which case the
                    // container is to be removed to prevent leaking
                    pool.putBack(con, failedInit)

                    completeTransaction(tran, activationResult withLogs ActivationLogs.serdes.read(contents), ContainerReleased(transid))
            }

            case None => { // this corresponds to the container not even starting - not /init failing
                info(this, s"failed to start or get a container")
                val response = if (action.exec.kind == Exec.BLACKBOX) {
                    ActivationResponse.containerError("The container did to start.")
                } else {
                    ActivationResponse.whiskError("Error starting container to run action.")
                }
                val interval = computeActivationInterval(tran)
                val activation = makeWhiskActivation(msg, EntityPath(action.docid.id), action.version, response, interval, Some(action.limits))
                completeTransaction(tran, activation, FailedActivation(transid))
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
    private def getContainerLogs(con: WhiskContainer, sentinelled: Boolean, loglimit: LogLimit, tries: Int = LogRetryCount)(
        implicit transid: TransactionId): JsArray = {
        val size = pool.getLogSize(con, runningInContainer)
        val advanced = size != con.lastLogSize
        if (tries <= 0 || advanced) {
            val rawLogBytes = con.synchronized {
                pool.getDockerLogContent(con.containerId, con.lastLogSize, size, runningInContainer)
            }
            val rawLog = new String(rawLogBytes, "UTF-8")

            val (complete, isTruncated, logs) = processJsonDriverLogContents(rawLog, sentinelled, loglimit)

            if (tries > 0 && !complete && !isTruncated) {
                info(this, s"log cursor advanced but missing sentinel, trying $tries more times")
                Thread.sleep(LogRetry)
                getContainerLogs(con, sentinelled, loglimit, tries - 1)
            } else {
                con.lastLogSize = size
                val formattedLogs = logs.map(_.toFormattedString)

                val finishedLogs = if (isTruncated) {
                    formattedLogs :+ loglimit.truncatedLogMessage
                } else formattedLogs

                JsArray(finishedLogs.map(_.toJson))
            }
        } else {
            info(this, s"log cursor has not advanced, trying $tries more times")
            Thread.sleep(LogRetry)
            getContainerLogs(con, sentinelled, loglimit, tries - 1)
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

    private def incrementUserActivationCounter(user: Subject)(implicit transid: TransactionId): Int = {
        val counter = userActivationCounter.getOrElseUpdate(user(), new Counter())
        val count = counter.next()
        info(this, s"'${user}' has $count activations processed")
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

    /**
     * Interprets the responses from the container and maps it to an appropriate ActivationResponse.
     */
    private def getActivationResponse(
        interval: Interval,
        timeout: Duration,
        response: Option[(Int, String)],
        failedInit: Boolean)(
            implicit transid: TransactionId): ActivationResponse = {
        if (interval.duration >= timeout) {
            ActivationResponse.applicationError(ActivationResponse.timedoutActivation(timeout, failedInit))
        } else if (!failedInit) {
            ActivationResponse.processRunResponseContent(response, this: Logging)
        } else {
            ActivationResponse.processInitResponseContent(response, this: Logging)
        }
    }

    /**
     * Creates a WhiskActivation for the given action, response and duration.
     */
    private def makeWhiskActivation(
        msg: Message,
        actionName: EntityPath,
        actionVersion: SemVer,
        activationResponse: ActivationResponse,
        interval: Interval,
        limits: Option[ActionLimits]) = {
        WhiskActivation(
            namespace = msg.activationNamespace,
            name = actionName.last,
            version = actionVersion,
            publish = false,
            subject = msg.subject,
            activationId = msg.activationId,
            cause = msg.cause,
            start = interval.start,
            end = interval.end,
            response = activationResponse,
            logs = ActivationLogs(),
            annotations = Parameters("limits", limits.toJson) ++ Parameters("path", actionName.toJson))
    }

    /**
     * Reconstructs an interval based on the time spent in the various operations.
     * The goal is for the interval to have a duration corresponding to the sum of all durations
     * and an endtime corresponding to the latest endtime.
     *
     * @param transaction the transaction object containing metadata
     * @return interval for the transaction with start/end times computed
     */
    private def computeActivationInterval(transaction: Transaction): Interval = {
        (transaction.initInterval, transaction.runInterval) match {
            case (None, Some(run))  => run
            case (Some(init), None) => init
            case (None, None)       => Interval(Instant.now(Clock.systemUTC()), Instant.now(Clock.systemUTC()))
            case (Some(init), Some(Interval(runStart, runEnd))) =>
                Interval(runStart.minusMillis(init.duration.toMillis), runEnd)
        }
    }

    private val entityStore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)
    private val activationStore = WhiskActivationStore.datastore(config)
    private val pool = new ContainerPool(config, instance, verbosity)
    private val activationCounter = new Counter() // global activation counter
    private val userActivationCounter = new TrieMap[String, Counter]

    private val consul = new ConsulClient(config.consulServer)

    // Restore old state from the same invoker instance
    consul.kv.getRecurse(InvokerKeys.userActivationCount(instance)).map { partitioned =>
        val flattened = partitioned.values.map { userCounts =>
            userCounts.parseJson.convertTo[Map[String, Int]]
        }.reduce(_ ++ _)

        val readActivationCounter = flattened.mapValues { readCount =>
            val cnt = new Counter()
            cnt.set(readCount)
            cnt
        }

        info(this, s"restored old counts from consul: $flattened")

        userActivationCounter.clear()
        userActivationCounter ++= readActivationCounter

        activationCounter.set(flattened.values.sum)
    } onComplete { _ =>
        // Repeatedly updates the KV store as to the invoker's last check-in.
        new ConsulKVReporter(consul, 3 seconds, 2 seconds,
            InvokerKeys.hostname(instance),
            InvokerKeys.start(instance),
            InvokerKeys.status(instance),
            { index =>
                (if (index % 5 == 0) getUserActivationCounts() else Map[String, JsValue]()) ++
                    Map(InvokerKeys.activationCount(instance) -> activationCounter.cur.toJson)
            })
    }

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

    def main(args: Array[String]): Unit = {
        require(args.length == 1, "invoker instance required")
        val instance = args(0).toInt
        val verbosity = InfoLevel

        implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
        implicit val system: ActorSystem = ActorSystem(
            name = "invoker-actor-system",
            defaultExecutionContext = Some(ec))

        // load values for the required properties from the environment
        val config = new WhiskConfig(requiredProperties)

        if (config.isValid) {
            SimpleExec.setVerbosity(verbosity)

            val topic = Message.invoker(instance)
            val groupid = "invokers"
            val maxdepth = ContainerPool.getDefaultMaxActive(config)
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

/**
 * Tracks the state of the transaction by wrapping the Message object.
 * Note that var fields cannot be added to Message as it leads to serialization issues and
 * doesn't make sense to mix local mutable state with the value being passed around.
 *
 * See completeTransaction for why complete is needed.
 */
private case class Transaction(msg: Message) {
    var result: Option[Future[DocInfo]] = None
    var initInterval: Option[Interval] = None
    var runInterval: Option[Interval] = None
}

private case class ActivationException(msg: String, internalError: Boolean = true) extends Throwable {
    val activationResponse = if (internalError) {
        ActivationResponse.whiskError(msg)
    } else {
        ActivationResponse.applicationError(msg)
    }
}
