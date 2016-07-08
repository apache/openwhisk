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

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.matching.Regex.Match

import akka.actor.ActorSystem
import akka.japi.Creator
import spray.json.DefaultJsonProtocol.IntJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsArray
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.pimpAny
import spray.json.pimpString
import whisk.common.ConsulClient
import whisk.common.ConsulKV.InvokerKeys
import whisk.common.ConsulKVReporter
import whisk.common.Counter
import whisk.common.LoggingMarkers._
import whisk.common.SimpleExec
import whisk.common.TransactionId
import whisk.common.Verbosity
import whisk.connector.kafka.KafkaProducerConnector
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.dockerRegistry
import whisk.core.WhiskConfig.edgeHost
import whisk.core.WhiskConfig.logsDir
import whisk.core.WhiskConfig.whiskVersion
import whisk.core.connector.{ ActivationMessage => Message }
import whisk.core.connector.CompletionMessage
import whisk.core.container.ContainerPool
import whisk.core.container.WhiskContainer
import whisk.core.dispatcher.DispatchRule
import whisk.core.dispatcher.Dispatcher
import whisk.core.entity.ActivationId
import whisk.core.entity.ActivationLogs
import whisk.core.entity.ActivationResponse
import whisk.core.entity.DocId
import whisk.core.entity.DocInfo
import whisk.core.entity.DocRevision
import whisk.core.entity.EntityName
import whisk.core.entity.Namespace
import whisk.core.entity.NodeJSExec
import whisk.core.entity.NodeJS6Exec
import whisk.core.entity.SwiftExec
import whisk.core.entity.Swift3Exec
import whisk.core.entity.SemVer
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskActivation
import whisk.core.entity.WhiskActivationStore
import whisk.core.entity.WhiskAuth
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskEntityStore
import whisk.http.BasicHttpService
import whisk.utils.ExecutionContextFactory
import scala.concurrent.duration.Duration

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
    verbosity: Verbosity.Level = Verbosity.Loud,
    runningInContainer: Boolean = true)(implicit actorSystem: ActorSystem)
    extends DispatchRule("invoker", "/actions/invoke", s"""(.+)/(.+)/(.+),(.+)/(.+)""") {

    implicit private val executionContext: ExecutionContext = actorSystem.dispatcher

    /** This generates completion messages back to the controller */
    val producer = new KafkaProducerConnector(config.kafkaHost, executionContext)

    override def setVerbosity(level: Verbosity.Level) = {
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
        var initInterval: Option[(Instant, Instant)] = None
        var runInterval: Option[(Instant, Instant)] = None
    }

    /**
     * This is the handler for the kafka message
     *
     * @param msg is the kafka message payload as Json
     * @param matches contains the regex matches
     */
    override def doit(topic: String, msg: Message, matches: Seq[Match])(implicit transid: TransactionId): Future[DocInfo] = {
        Future {
            // conformance checks can terminate the future if a variance is detected
            require(matches != null && matches.size >= 1, "matches undefined")
            require(matches(0).groupCount >= 3, "wrong number of matches")
            require(matches(0).group(2).nonEmpty, "action namespace undefined") // fully qualified name (namespace:name)
            require(matches(0).group(3).nonEmpty, "action name undefined") // fully qualified name (namespace:name)
            require(msg != null, "message undefined")

            val regex = matches(0)
            val nanespace = Namespace(regex.group(2))
            val name = EntityName(regex.group(3))
            val version = if (regex.groupCount == 4) DocRevision(regex.group(4)) else DocRevision()
            val action = DocId(WhiskEntity.qualifiedName(nanespace, name)).asDocInfo(version)
            action
        } flatMap { fetchFromStoreAndInvoke(_, msg) }
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
        info(this, "", INVOKER_FETCH_ACTION_START)
        val actionFuture = WhiskAction.get(entityStore, action, action.rev != DocRevision())
        actionFuture onFailure {
            case t => error(this, s"failed to fetch action ${action.id}: ${t.getMessage}", INVOKER_FETCH_ACTION_FAILED)
        }

        info(this, "", INVOKER_FETCH_AUTH_START)
        // keys are immutable, cache them
        val authFuture = WhiskAuth.get(authStore, subject, true)
        authFuture onFailure {
            case t => error(this, s"failed to fetch auth key for $subject: ${t.getMessage}", INVOKER_FETCH_AUTH_FAILED)
        }

        // when records are fetched, invoke action
        val activationDocFuture =
            actionFuture flatMap { theAction =>
                // assume this future is done here
                info(this, "", INVOKER_FETCH_ACTION_DONE)
                authFuture flatMap { theAuth =>
                    // assume this future is done here
                    info(this, "", INVOKER_FETCH_AUTH_DONE)
                    info(this, "", INVOKER_ACTIVATION_START)
                    invokeAction(theAction, theAuth, payload, tran)
                }
            }
        activationDocFuture onComplete {
            case Success(activationDoc) =>
                info(this, s"recorded activation '$activationDoc'", INVOKER_ACTIVATION_DONE)
                activationDoc
            case Failure(t) =>
                info(this, s"failed to invoke action ${action.id} due to ${t.getMessage}", INVOKER_ACTIVATION_ERROR)
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
        completeTransaction(tran, activation)
    }

    /*
     * Action that must be taken when an activation completes (with or without error).
     *
     * Invariant: Only one call to here succeeds.  Even though the sync block wrap WhiskActivation.put,
     *            it is only blocking this transaction which is finishing anyway.
     */
    protected def completeTransaction(tran: Transaction, activation: WhiskActivation)(
        implicit transid: TransactionId): Future[DocInfo] = {
        tran.synchronized {
            tran.result match {
                case Some(res) => res
                case None => {
                    activationCounter.next() // this is the global invoker counter
                    incrementUserActivationCounter(tran.msg.subject)
                    // Since there is no active action taken for completion from the invoker, writing activation record is it.
                    info(this, "recording the activation result to the data store", INVOKER_RECORD_ACTIVATION_START)
                    val result = WhiskActivation.put(activationStore, activation)
                    info(this, "finished recording the activation result", INVOKER_RECORD_ACTIVATION_DONE)
                    tran.result = Some(result)
                    result
                }
            }
        }
    }

    // These are related to initialization
    private val RegularSlack = 100 // millis
    private val BlackBoxSlack = 200 // millis

    protected def invokeAction(action: WhiskAction, auth: WhiskAuth, payload: JsObject, tran: Transaction)(
        implicit transid: TransactionId): Future[DocInfo] = {
        val msg = tran.msg
        pool.getAction(action, auth) match {
            case Some((con, initResultOpt)) => Future {
                val params = con.mergeParams(payload)
                val timeoutMillis = action.limits.timeout.millis
                initResultOpt match {
                    case None => (false, con.run(params, msg.meta, auth.compact, timeoutMillis,
                        action.fullyQualifiedName, msg.activationId.toString)) // cached
                    case Some((start, end, Some((200, _)))) => { // successful init
                        // Try a little slack for json log driver to process
                        Thread.sleep(if (con.isBlackbox) BlackBoxSlack else RegularSlack)
                        tran.initInterval = Some(start, end)
                        (false, con.run(params, msg.meta, auth.compact, timeoutMillis,
                            action.fullyQualifiedName, msg.activationId.toString))
                    }
                    case _ => (true, initResultOpt.get) //  unsuccessful initialization
                }
            } flatMap {
                case (failedInit, (start, end, response)) =>
                    if (!failedInit) tran.runInterval = Some(start, end)
                    val activationResult = makeWhiskActivation(tran, con.isBlackbox, msg, action, payload, response)
                    val completeMsg = CompletionMessage(transid, activationResult)
                    producer.send("completed", completeMsg) map { status =>
                        info(this, s"posted completion of activation ${msg.activationId}")
                    }
                    val contents = getContainerLogs(con)
                    // Force delete the container instead of just pausing it iff the initialization failed or the container
                    // failed otherwise. An example of a ContainerError is the timeout of an action in which case the
                    // container is to be removed to prevent leaking
                    val res = completeTransaction(tran, activationResult withLogs ActivationLogs.serdes.read(contents))
                    val removeContainer = failedInit || response.exists { _._1 == ActivationResponse.ContainerError }
                    // We return the container last as that is slow and we want the activation to logically finish fast
                    pool.putBack(con, removeContainer)
                    res
            }
            case None => { // this corresponds to the container not even starting - not /init failing
                info(this, s"failed to start or get a container")
                val response = Some(420, "Error starting container")
                val contents = JsArray(JsString("Error starting container"))
                val activation = makeWhiskActivation(tran, false, msg, action, payload, response)
                completeTransaction(tran, activation withLogs ActivationLogs.serdes.read(contents))
            }
        }
    }

    // The nodeJsAction runner inserts this line in the logs at the end
    // of each activation
    private val LogRetryCount = 15
    private val LogRetry = 100 // millis
    private val LOG_ACTIVATION_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"
    private val nodejsImageName = WhiskAction.containerImageName(NodeJSExec("", None), config.dockerRegistry, config.dockerImageTag)
    private val nodejs6ImageName = WhiskAction.containerImageName(NodeJS6Exec("", None), config.dockerRegistry, config.dockerImageTag)
    private val swiftImageName = WhiskAction.containerImageName(SwiftExec(""), config.dockerRegistry, config.dockerImageTag)
    private val swift3ImageName = WhiskAction.containerImageName(Swift3Exec(""), config.dockerRegistry, config.dockerImageTag)

    /**
     * Waits for log cursor to advance. This will retry up to tries times
     * if the cursor has not yet advanced. This will penalize containers that
     * do not log. It is OK for nodejs containers because the runtime emits
     * the END_OF_ACTIVATION_MARKER automatically and that advances the cursor.
     *
     * Note: Updates the container's log cursor to indicate consumption of log.
     */
    private def getContainerLogs(con: WhiskContainer, tries: Int = LogRetryCount)(implicit transid: TransactionId): JsArray = {
        val size = con.getLogSize(runningInContainer)
        val advanced = size != con.lastLogSize
        if (tries <= 0 || advanced) {
            val rawLogBytes = con.getDockerLogContent(con.lastLogSize, size, runningInContainer)
            val rawLog = new String(rawLogBytes, "UTF-8")
            val isNodeJs = con.image == nodejsImageName || con.image == nodejs6ImageName
            var isSwift = con.image == swiftImageName || con.image == swift3ImageName
            val (complete, logArray) = processJsonDriverLogContents(rawLog, isNodeJs || isSwift)
            if (tries > 0 && !complete) {
                info(this, s"log cursor advanced but missing sentinel, trying $tries more times")
                Thread.sleep(LogRetry)
                getContainerLogs(con, tries - 1)
            } else {
                con.lastLogSize = size
                logArray
            }
        } else {
            info(this, s"log cursor has not advanced, trying $tries more times")
            Thread.sleep(LogRetry)
            getContainerLogs(con, tries - 1)
        }
    }

    /**
     * Given the json driver's raw incremental output of a docker container,
     * convert it into our own JSON format.  If asked, check for
     * sentinel markers (which are not included in the output).
     */
    private def processJsonDriverLogContents(logMsgs: String, requireSentinel: Boolean)(
        implicit transid: TransactionId): (Boolean, JsArray) = {

        if (logMsgs.nonEmpty) {
            val records: Vector[(String, String, String)] = logMsgs.split("\n").toVector flatMap {
                line =>
                    Try { line.parseJson.asJsObject } match {
                        case Success(t) =>
                            t.getFields("time", "stream", "log") match {
                                case Seq(JsString(t), JsString(s), JsString(l)) => Some(t, s, l.trim)
                                case _ => None
                            }
                        case Failure(t) =>
                            // Drop lines that did not parse to JSON objects.
                            // However, should not happen since we are using the json log driver.
                            error(this, s"log line skipped/did not parse: $t")
                            None
                    }
            }
            if (requireSentinel) {
                val (sentinels, regulars) = records.partition(_._3 == LOG_ACTIVATION_SENTINEL)
                val hasOut = sentinels.exists(_._2 == "stdout")
                val hasErr = sentinels.exists(_._2 == "stderr")
                (hasOut && hasErr, JsArray(regulars map formatLog))
            } else {
                (true, JsArray(records map formatLog))
            }
        } else {
            warn(this, s"log message is empty")
            (!requireSentinel, JsArray())
        }
    }

    private def formatLog(time_stream_msg: (String, String, String)) = {
        val (time, stream, msg) = time_stream_msg
        f"$time%-30s $stream: ${msg.trim}".toJson
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
        val interval = (transaction.initInterval, transaction.runInterval) match {
            case (None, Some(run))  => run
            case (Some(init), None) => init
            case (None, None)       => (Instant.now(Clock.systemUTC()), Instant.now(Clock.systemUTC()))
            case (Some(init), Some(run)) =>
                val durMilli = init._2.toEpochMilli() - init._1.toEpochMilli()
                (run._1.minusMillis(durMilli), run._2)
        }
        val duration = Duration.fromNanos(java.time.Duration.between(interval._1, interval._2).toNanos)
        val activationResponse = if (duration >= timeout) {
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
            start = interval._1,
            end = interval._2,
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
        { () =>
            getUserActivationCounts() ++
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
    def requiredProperties =
        Map(logsDir -> null, dockerRegistry -> null) ++
            WhiskAuthStore.requiredProperties ++
            WhiskEntityStore.requiredProperties ++
            WhiskActivationStore.requiredProperties ++
            ContainerPool.requiredProperties ++
            edgeHost ++
            consulServer ++
            whiskVersion
}

object InvokerService {
    /**
     * An object which records the environment variables required for this component to run.
     */
    def requiredProperties = Invoker.requiredProperties ++ Dispatcher.requiredProperties

    private class ServiceBuilder(invoker: Invoker)(
        implicit val ec: ExecutionContext)
        extends Creator[InvokerServer] {
        def create = new InvokerServer {
            override val invokerInstance = invoker
            override val executionContext = ec
        }
    }

    def main(args: Array[String]): Unit = {
        // load values for the required properties from the environment
        val config = new WhiskConfig(requiredProperties)

        if (config.isValid) {
            val instance = if (args.length > 0) args(1).toInt else 0
            val verbosity = Verbosity.Loud

            SimpleExec.setVerbosity(verbosity)

            implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
            implicit val system = ActorSystem(
                name = "invoker-actor-system",
                defaultExecutionContext = Some(ec))

            val dispatcher = new Dispatcher(config, s"invoke${instance}", "invokers")

            val invoker = new Invoker(config, instance, verbosity)
            dispatcher.setVerbosity(verbosity)
            dispatcher.addHandler(invoker, true)
            dispatcher.start()

            val port = config.servicePort.toInt
            BasicHttpService.startService(system, "invoker", "0.0.0.0", port, new ServiceBuilder(invoker))
        }
    }
}
