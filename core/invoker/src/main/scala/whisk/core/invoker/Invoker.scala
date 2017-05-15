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

import java.nio.charset.StandardCharsets

import java.time.{ Clock, Instant }

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.Promise
import scala.concurrent.duration.{ Duration, DurationInt }
import scala.language.postfixOps
import scala.util.{ Failure, Success }

import akka.actor.{ ActorRef, ActorSystem, actorRef2Scala }
import akka.japi.Creator
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.{ Counter, Logging, LoggingMarkers, TransactionId }
import whisk.common.AkkaLogging
import whisk.connector.kafka.{ KafkaConsumerConnector, KafkaProducerConnector }
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{ consulServer, dockerImagePrefix, dockerRegistry, kafkaHost, logsDir, servicePort, whiskVersion, invokerUseReactivePool }
import whisk.core.connector.{ ActivationMessage, CompletionMessage }
import whisk.core.container._
import whisk.core.dispatcher.{ Dispatcher, MessageHandler }
import whisk.core.dispatcher.ActivationFeed.{ ActivationNotification, ContainerReleased, FailedActivation }
import whisk.core.entity._
import whisk.http.BasicHttpService
import whisk.http.Messages
import whisk.utils.ExecutionContextFactory
import whisk.common.Scheduler
import whisk.core.connector.PingMessage
import scala.util.Try
import whisk.core.connector.MessageProducer

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
    producer: MessageProducer,
    runningInContainer: Boolean = true)(implicit actorSystem: ActorSystem, logging: Logging)
    extends MessageHandler(s"invoker$instance")
    with ActionLogDriver {

    private implicit val executionContext: ExecutionContext = actorSystem.dispatcher

    TransactionId.invoker.mark(this, LoggingMarkers.INVOKER_STARTUP(instance), s"starting invoker instance ${instance}")

    /**
     * This is the handler for the kafka message
     *
     * @param msg is the kafka message payload as Json
     * @param matches contains the regex matches
     */
    override def onMessage(msg: ActivationMessage)(implicit transid: TransactionId): Future[DocInfo] = {
        require(msg != null, "message undefined")
        require(msg.action.version.isDefined, "action version undefined")

        val start = transid.started(this, LoggingMarkers.INVOKER_ACTIVATION)
        val namespace = msg.action.path
        val name = msg.action.name
        val actionid = FullyQualifiedEntityName(namespace, name).toDocId.asDocInfo(msg.revision)
        val tran = Transaction(msg)
        val subject = msg.user.subject

        logging.info(this, s"${actionid.id} $subject ${msg.activationId}")

        // the activation must terminate with only one attempt to write an activation record to the datastore
        // hence when the transaction is fully processed, this method will complete a promise with the datastore
        // future writing back the activation record and for which there are three cases:
        // 1. success: there were no exceptions and hence the invoke path operated normally,
        // 2. error during invocation: an exception occurred while trying to run the action,
        // 3. error fetching action: an exception occurred reading from the db, didn't get to run.
        val transactionPromise = Promise[DocInfo]

        // caching is enabled since actions have revision id and an updated
        // action will not hit in the cache due to change in the revision id;
        // if the doc revision is missing, then bypass cache
        if (actionid.rev == DocRevision.empty) {
            logging.error(this, s"revision was not provided for ${actionid.id}")
        }
        WhiskAction.get(entityStore, actionid.id, actionid.rev, fromCache = actionid.rev != DocRevision.empty) onComplete {
            case Success(action) =>
                // only Exec instances that are subtypes of CodeExec reach the invoker
                assume(action.exec.isInstanceOf[CodeExec[_]])

                invokeAction(tran, action) onComplete {
                    case Success(activation) =>
                        transactionPromise.completeWith {
                            // this completes the successful activation case (1)
                            completeTransaction(tran, activation, ContainerReleased)
                        }

                    case Failure(t) =>
                        logging.info(this, s"activation failed")
                        val failure = disambiguateActivationException(t, action)
                        transactionPromise.completeWith {
                            // this completes the failed activation case (2)
                            completeTransactionWithError(action.docid, action.version, tran, failure.activationResponse, Some(action.limits))
                        }
                }

            case Failure(t) =>
                logging.error(this, s"failed to fetch action from db: ${t.getMessage}")
                val failureResponse = ActivationResponse.whiskError(s"Failed to fetch action.")
                transactionPromise.completeWith {
                    // this completes the failed to fetch case (3)
                    completeTransactionWithError(actionid.id, msg.action.version.get, tran, failureResponse, None)
                }
        }

        transactionPromise.future
    }

    /*
     * Creates a whisk activation out of the errorMsg and finish the transaction.
     * Failing with an error can involve multiple futures but the effecting call is completeTransaction which is guarded.
     */
    protected def completeTransactionWithError(name: DocId, version: SemVer, tran: Transaction, response: ActivationResponse, limits: Option[ActionLimits])(
        implicit transid: TransactionId): Future[DocInfo] = {
        val msg = tran.msg
        val interval = computeActivationInterval(tran)
        val activation = makeWhiskActivation(msg, EntityPath(name.id), version, response, interval, limits)
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
                    // Send a message to the activation feed indicating there is a free resource to handle another activation.
                    // Since all transaction completions flow through this method and the invariant is that the transaction is
                    // completed only once, there is only one completion message sent to the feed as a result.
                    activationFeed ! releaseResource
                    // Since there is no active action taken for completion from the invoker, writing activation record is it.
                    logging.info(this, "recording the activation result to the data store")
                    val result = WhiskActivation.put(activationStore, activation) andThen {
                        case Success(id) => logging.info(this, s"recorded activation")
                        case Failure(t)  => logging.error(this, s"failed to record activation")
                    }
                    tran.result = Some(result)
                    result
                }
            }
        }
    }

    /**
     * Executes the action: gets a container (new or recycled), initializes it if necessary, and runs the action.
     *
     * @return WhiskActivation
     */
    protected def invokeAction(tran: Transaction, action: WhiskAction)(
        implicit transid: TransactionId): Future[WhiskActivation] = {
        Future { pool.getAction(action, tran.msg.user.authkey) } map {
            case (con, initResultOpt) => runAction(tran, action, con, initResultOpt)
        } map {
            case (failedInit, con, result) =>
                // process the result and send active ack message
                val activationResult = sendActiveAck(tran, action, failedInit, result)

                // after sending active ack, drain logs and return container
                val contents = getContainerLogs(con, action.exec.asInstanceOf[CodeExec[_]].sentinelledLogs, action.limits.logs)

                Future {
                    // Force delete the container instead of just pausing it iff the initialization failed or the container
                    // failed otherwise. An example of a ContainerError is the timeout of an action in which case the
                    // container is to be removed to prevent leaking of an activation across to new activations.
                    // Since putting back the container involves pausing, run this in a Future so as not to block transaction
                    // completion but also return resources promptly.
                    // Note: using infinite thread pool so using a future here for a long/blocking operation is acceptable.
                    val deleteContainer = failedInit || result.errored
                    pool.putBack(con, deleteContainer)
                }

                activationResult withLogs ActivationLogs(contents)
        }
    }

    /**
     * Runs the action in the container if the initialization succeeded and returns a triple
     * (initialization failed?, the container, the init result if initialization failed else the run result)
     */
    private def runAction(tran: Transaction, action: WhiskAction, con: WhiskContainer, initResultOpt: Option[RunResult])(
        implicit transid: TransactionId): (Boolean, WhiskContainer, RunResult) = {
        def run() = {
            val msg = tran.msg
            val auth = msg.user.authkey
            val payload = msg.content getOrElse JsObject()
            val boundParams = action.parameters.toJsObject
            val params = JsObject(boundParams.fields ++ payload.fields)
            val timeout = action.limits.timeout.duration
            con.run(msg, params, timeout)
        }

        initResultOpt match {
            // cached container
            case None => (false, con, run())

            // new container
            case Some(init @ RunResult(interval, response)) =>
                tran.initInterval = Some(interval)
                if (init.ok) {
                    (false, con, run())
                } else {
                    (true, con, initResultOpt.get)
                }
        }
    }

    /**
     * Creates WhiskActivation for the "run result" (which could be a failed initialization) and send
     * ActiveAck message.
     *
     * @return WhiskActivation
     */
    private def sendActiveAck(tran: Transaction, action: WhiskAction, failedInit: Boolean, result: RunResult)(
        implicit transid: TransactionId): WhiskActivation = {
        if (!failedInit) tran.runInterval = Some(result.interval)

        val msg = tran.msg
        val activationInterval = computeActivationInterval(tran)
        val activationResponse = getActivationResponse(activationInterval, action.limits.timeout.duration, result, failedInit)
        val activationResult = makeWhiskActivation(msg, EntityPath(action.fullyQualifiedName(false).toString), action.version, activationResponse, activationInterval, Some(action.limits))
        val completeMsg = CompletionMessage(transid, activationResult, this.name)

        producer.send("completed", completeMsg) map { status =>
            logging.info(this, s"posted completion of activation ${msg.activationId}")
        }

        activationResult
    }

    // The nodeJsAction runner inserts this line in the logs at the end
    // of each activation
    private val LogRetryCount = 15
    private val LogRetry = 100 // millis

    /**
     * Waits for log cursor to advance. This will retry up to tries times
     * if the cursor has not yet advanced. This will penalize docker actions
     * that do not log. It is OK for proxied containers because the runtime emits
     * the END_OF_ACTIVATION_MARKER automatically and that advances the cursor.
     *
     * Note: Updates the container's log cursor to indicate consumption of log.
     * It is possible that log messages form one activation spill over into the
     * next activation if the marker is not observed but the log limit is reached.
     */
    private def getContainerLogs(con: WhiskContainer, sentinelled: Boolean, loglimit: LogLimit, tries: Int = LogRetryCount)(
        implicit transid: TransactionId): Vector[String] = {
        val size = pool.getLogSize(con, runningInContainer)
        val advanced = size != con.lastLogSize
        if (tries <= 0 || advanced) {
            val rawLogBytes = con.synchronized {
                pool.getDockerLogContent(con.containerId, con.lastLogSize, size, runningInContainer)
            }

            val rawLog = new String(rawLogBytes, StandardCharsets.UTF_8)

            val (complete, isTruncated, logs) = processJsonDriverLogContents(rawLog, sentinelled, loglimit.asMegaBytes)

            if (tries > 0 && !complete && !isTruncated) {
                logging.info(this, s"log cursor advanced but missing sentinel, trying $tries more times")
                Thread.sleep(LogRetry)
                // note this is not an incremental read - will re-process the entire log file
                getContainerLogs(con, sentinelled, loglimit, tries - 1)
            } else {
                con.lastLogSize = size
                logs
            }
        } else {
            logging.info(this, s"log cursor has not advanced, trying $tries more times")
            Thread.sleep(LogRetry)
            getContainerLogs(con, sentinelled, loglimit, tries - 1)
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Interprets the responses from the container and maps it to an appropriate ActivationResponse.
     * Note: it is possible for result.response to be None if the container timed out.
     */
    private def getActivationResponse(
        interval: Interval,
        timeout: Duration,
        runResult: RunResult,
        failedInit: Boolean)(
            implicit transid: TransactionId): ActivationResponse = {
        if (interval.duration >= timeout) {
            ActivationResponse.applicationError(Messages.timedoutActivation(timeout, failedInit))
        } else if (!failedInit) {
            ActivationResponse.processRunResponseContent(runResult.response, logging)
        } else {
            ActivationResponse.processInitResponseContent(runResult.response, logging)
        }
    }

    /**
     * Creates a WhiskActivation for the given action, response and duration.
     */
    private def makeWhiskActivation(
        msg: ActivationMessage,
        actionName: EntityPath,
        actionVersion: SemVer,
        activationResponse: ActivationResponse,
        interval: Interval,
        limits: Option[ActionLimits]) = {
        val causedBy = if (msg.causedBySequence) Parameters("causedBy", "sequence".toJson) else Parameters()
        WhiskActivation(
            namespace = msg.activationNamespace,
            name = actionName.last,
            version = actionVersion,
            publish = false,
            subject = msg.user.subject,
            activationId = msg.activationId,
            cause = msg.cause,
            start = interval.start,
            end = interval.end,
            response = activationResponse,
            logs = ActivationLogs(),
            annotations = {
                limits.map(l => Parameters("limits", l.toJson)).getOrElse(Parameters()) ++
                    Parameters("path", actionName.toJson) ++ causedBy
            },
            duration = Some(interval.duration.toMillis))

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

    /**
     * Rewrites exceptions during invocation into new exceptions.
     */
    private def disambiguateActivationException(t: Throwable, action: WhiskAction)(
        implicit transid: TransactionId): ActivationException = {
        t match {
            // in case of container pull/run operations that fail to execute, assign an appropriate error response
            case BlackBoxContainerError(msg) => ActivationException(msg, internalError = false)
            case WhiskContainerError(msg)    => ActivationException(msg)
            case _ =>
                logging.error(this, s"failed during invoke: $t")
                ActivationException(s"Failed to run action '${action.docid}': ${t.getMessage}")
        }
    }

    private val entityStore = WhiskEntityStore.datastore(config)
    private val authStore = WhiskAuthStore.datastore(config)
    private val activationStore = WhiskActivationStore.datastore(config)
    private val pool = new ContainerPool(config, instance)
    private val activationCounter = new Counter() // global activation counter
}

object Invoker {
    /**
     * An object which records the environment variables required for this component to run.
     */
    def requiredProperties = Map(
        servicePort -> 8080.toString(),
        logsDir -> null,
        dockerRegistry -> null,
        dockerImagePrefix -> null,
        invokerUseReactivePool -> false.toString) ++
        ExecManifest.requiredProperties ++
        WhiskAuthStore.requiredProperties ++
        WhiskEntityStore.requiredProperties ++
        WhiskActivationStore.requiredProperties ++
        ContainerPool.requiredProperties ++
        kafkaHost ++
        consulServer ++
        whiskVersion

    def main(args: Array[String]): Unit = {
        require(args.length == 1, "invoker instance required")
        val instance = args(0).toInt

        implicit val ec = ExecutionContextFactory.makeCachedThreadPoolExecutionContext()
        implicit val actorSystem: ActorSystem = ActorSystem(
            name = "invoker-actor-system",
            defaultExecutionContext = Some(ec))
        implicit val logger = new AkkaLogging(akka.event.Logging.getLogger(actorSystem, this))

        // load values for the required properties from the environment
        val config = new WhiskConfig(requiredProperties)

        // if configuration is valid, initialize the runtimes manifest
        if (config.isValid && ExecManifest.initialize(config)) {
            val topic = s"invoker$instance"
            val groupid = "invokers"
            val maxdepth = ContainerPool.getDefaultMaxActive(config)
            val consumer = new KafkaConsumerConnector(config.kafkaHost, groupid, topic, maxdepth)
            val producer = new KafkaProducerConnector(config.kafkaHost, ec)
            val dispatcher = new Dispatcher(consumer, 500 milliseconds, 2 * maxdepth, actorSystem)

            val invoker = if (Try(config.invokerUseReactivePool.toBoolean).getOrElse(false)) {
                new InvokerReactive(config, instance, dispatcher.activationFeed, producer)
            } else {
                new Invoker(config, instance, dispatcher.activationFeed, producer)
            }
            logger.info(this, s"using $invoker")

            dispatcher.addHandler(invoker, true)
            dispatcher.start()

            Scheduler.scheduleWaitAtMost(1.seconds)(() => {
                producer.send("health", PingMessage(s"invoker$instance")).andThen {
                    case Failure(t) => logger.error(this, s"failed to ping the controller: $t")
                }
            })

            val port = config.servicePort.toInt
            BasicHttpService.startService(actorSystem, "invoker", "0.0.0.0", port, new Creator[InvokerServer] {
                def create = new InvokerServer {
                    override implicit val logging = logger
                }
            })
        } else {
            logger.error(this, "Bad configuration, cannot start.")
            actorSystem.terminate()
            Await.result(actorSystem.whenTerminated, 30.seconds)
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
private case class Transaction(msg: ActivationMessage) {
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
