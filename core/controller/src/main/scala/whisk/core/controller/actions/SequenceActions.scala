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

package whisk.core.controller.actions

import java.time.Clock
import java.time.Instant

import scala.Left
import scala.Right
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import spray.json._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.controller.WhiskActionsApi._
import whisk.core.controller.WhiskServices
import whisk.core.entity._
import whisk.core.entity.size.SizeInt
import whisk.core.entity.types._
import whisk.http.Messages._
import whisk.utils.ExecutionContextFactory.FutureExtensions

protected[actions] trait SequenceActions {
    /** The core collections require backend services to be injected in this trait. */
    services: WhiskServices =>

    /** An actor system for timed based futures. */
    protected implicit val actorSystem: ActorSystem

    /** An execution context for futures. */
    protected implicit val executionContext: ExecutionContext

    protected implicit val logging: Logging

    /** Database service to CRUD actions. */
    protected val entityStore: EntityStore

    /** Database service to get activations. */
    protected val activationStore: ActivationStore

    /** A method that knows how to invoke a single primitive action. */
    protected[actions] def invokeSingleAction(
        user: Identity,
        action: WhiskAction,
        payload: Option[JsObject],
        timeout: FiniteDuration,
        blocking: Boolean,
        cause: Option[ActivationId] = None)(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])]

    /**
     * Executes a sequence by invoking in a blocking fashion each of its components.
     *
     * @param user the user invoking the action
     * @param action the sequence action to be invoked
     * @param payload the dynamic arguments for the activation
     * @param blocking true iff this is a blocking invoke
     * @param topmost true iff this is the topmost sequence invoked directly through the api (not indirectly through a sequence)
     * @param components the actions in the sequence
     * @param cause the id of the activation that caused this sequence (defined only for inner sequences and None for topmost sequences)
     * @param atomicActionsCount the dynamic atomic action count observed so far since the start of invocation of the topmost sequence(0 if topmost)
     * @param transid a transaction id for logging
     * @return a future of type (ActivationId, Some(WhiskActivation), atomicActionsCount) if blocking; else (ActivationId, None, 0)
     */
    protected[actions] def invokeSequence(
        user: Identity,
        action: WhiskAction,
        payload: Option[JsObject],
        blocking: Boolean,
        topmost: Boolean,
        components: Vector[FullyQualifiedEntityName],
        cause: Option[ActivationId],
        atomicActionsCount: Int)(
            implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation], Int)] = {
        require(action.exec.kind == Exec.SEQUENCE, "this method requires an action sequence")

        // create new activation id that corresponds to the sequence
        val seqActivationId = activationIdFactory.make()
        logging.info(this, s"invoking sequence $action topmost $topmost activationid '$seqActivationId'")
        val start = Instant.now(Clock.systemUTC())
        val seqActivationPromise = Promise[Option[WhiskActivation]]
        // the cause for the component activations is the current sequence
        val futureSeqResult = invokeSequenceComponents(user, action, seqActivationId, payload, components, cause = Some(seqActivationId), atomicActionsCount)
        val response: Future[(ActivationId, Option[WhiskActivation], Int)] =
            if (topmost) { // need to deal with blocking and closing connection
                if (blocking) {
                    logging.info(this, s"invoke sequence blocking topmost!")
                    val timeout = maxWaitForBlockingActivation + blockingInvokeGrace

                    val futureSeqResultTimeout = futureSeqResult withTimeout (timeout, new BlockingInvokeTimeout(seqActivationId))
                    // if the future fails with a timeout, the failure is dealt with at the caller level
                    futureSeqResultTimeout map { accounting =>
                        // the execution of the sequence was successful, return the result
                        val end = Instant.now(Clock.systemUTC())
                        val seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, accounting, topmost, cause, start, end))
                        (seqActivationId, seqActivation, accounting.atomicActionCnt)
                    } andThen {
                        case Success((_, seqActivation, _)) => seqActivationPromise.success(seqActivation)
                        case Failure(t)                     => seqActivationPromise.success(None)
                    }
                } else {
                    // non-blocking sequence execution, return activation id
                    Future.successful((seqActivationId, None, 0)) andThen {
                        case _ => seqActivationPromise.success(None)
                    }
                }
            } else {
                // not topmost, no need to worry about terminating incoming request
                futureSeqResult map { accounting =>
                    // all activations are successful, the result of the sequence is the result of the last activation
                    val end = Instant.now(Clock.systemUTC())
                    val seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, accounting, topmost, cause, start, end))
                    (seqActivationId, seqActivation, accounting.atomicActionCnt)
                } andThen {
                    case Success((_, seqActivation, _)) => seqActivationPromise.success(seqActivation)
                    case Failure(t)                     => seqActivationPromise.success(None)
                }
            }

        // store result of sequence execution
        // if seqActivation is defined, use it; otherwise create it (e.g., for non-blocking activations)
        // the execution can reach here without a seqActivation due to non-blocking activations OR blocking activations that reach the blocking invoke timeout
        // futureSeqResult should always be successful, if failed, there is an error
        futureSeqResult flatMap { accounting => seqActivationPromise.future map { (accounting, _) } } onComplete {
            case Success((accounting, seqActivation)) =>
                // all activations were successful
                val activation = seqActivation getOrElse {
                    val end = Instant.now(Clock.systemUTC())
                    // the response of the sequence is the response of the very last activation
                    makeSequenceActivation(user, action, seqActivationId, accounting, topmost, cause, start, end)
                }
                storeSequenceActivation(activation)
            case Failure(t: Throwable) =>
                // consider this whisk error
                // TODO shall we attempt storing the activation if it exists or even inspect the futures?
                // this should be a pretty serious whisk errror if it gets here
                logging.error(this, s"Sequence activation failed: ${t.getMessage}")
        }

        response
    }

    /**
     * Stores sequence activation to database.
     */
    private def storeSequenceActivation(activation: WhiskActivation)(implicit transid: TransactionId): Unit = {
        logging.info(this, s"recording activation '${activation.activationId}'")
        WhiskActivation.put(activationStore, activation) onComplete {
            case Success(id) => logging.info(this, s"recorded activation")
            case Failure(t)  => logging.error(this, s"failed to record activation ${activation.activationId} with error ${t.getLocalizedMessage}")
        }
    }

    /**
     * Creates an activation for a sequence.
     */
    private def makeSequenceActivation(
        user: Identity,
        action: WhiskAction,
        activationId: ActivationId,
        accounting: SequenceAccounting,
        topmost: Boolean,
        cause: Option[ActivationId],
        start: Instant,
        end: Instant): WhiskActivation = {

        // compute max memory
        val sequenceLimits = accounting.maxMemory map {
            maxMemoryAcrossActionsInSequence =>
                Parameters("limits", ActionLimits(action.limits.timeout,
                    MemoryLimit(maxMemoryAcrossActionsInSequence MB),
                    action.limits.logs).toJson)
        } getOrElse (Parameters())

        // set causedBy if not topmost sequence
        val causedBy = if (!topmost) {
            Parameters("causedBy", JsString("sequence"))
        } else {
            Parameters()
        }

        // create the whisk activation
        WhiskActivation(
            namespace = user.namespace.toPath,
            name = action.name,
            user.subject,
            activationId = activationId,
            start = start,
            end = end,
            cause = if (topmost) None else cause, // propagate the cause for inner sequences, but undefined for topmost
            response = accounting.previousResponse.getAndSet(null),
            logs = accounting.finalLogs,
            version = action.version,
            publish = false,
            annotations = Parameters("topmost", JsBoolean(topmost)) ++
                Parameters("path", action.fullyQualifiedName(false).toString) ++
                Parameters("kind", "sequence") ++
                causedBy ++
                sequenceLimits,
            duration = Some(accounting.duration))
    }

    /**
     * Cumulative accounting of what happened during the execution of a sequence
     *
     */
    case class SequenceAccounting(atomicActionCnt: Int, previousResponse: java.util.concurrent.atomic.AtomicReference[ActivationResponse],
                                  logs: Array[ActivationId], duration: Long = 0, cursor: Int = 0, maxMemory: Option[Int] = None,
                                  shortcircuit: Boolean = false) {

        /** @return the ActivationLogs data structure for this sequence invocation */
        def finalLogs = ActivationLogs(logs.take(cursor).map(id => id.asString).toVector)

        /** the previous activation was successful */
        def success(activation: WhiskActivation, newCnt: Int, shortcircuit: Boolean = false) = {
          previousResponse.set(null)
          SequenceAccounting(
            prev = this,
            newCnt = newCnt,
            shortcircuit = shortcircuit,
            incrDuration = activation.duration,
            previousResponse = activation.response,
            previousActivationId = activation.activationId,
            previousMemoryLimit = activation.annotations.get("limits") map {
                limitsAnnotation => // we have a limits annotation
                    limitsAnnotation.asJsObject.getFields("memory") match {
                        case Seq(JsNumber(memory)) => Some(memory.toInt) // we have a numerical "memory" field in the "limits" annotation
                    }
            } getOrElse { None })
        }

        /** the previous activation failed */
        def fail(failureResponse: ActivationResponse) = SequenceAccounting(this, failureResponse)

        /** determine whether the previous activation succeeded or failed */
        def maybe(response: Either[ActivationResponse, WhiskActivation], newCnt: Int) = {
            response match {
                case Right(activation) =>
                    // check conditions on payload that may lead to interrupting the execution of the sequence
                    //     short-circuit the execution of the sequence iff the payload contains an error field
                    //     and is the result of an action return, not the initial payload
                    val outputPayload = activation.response.result.map(_.asJsObject)
                    val payloadContent = outputPayload getOrElse JsObject.empty
                    val errorFields = payloadContent.getFields(ActivationResponse.ERROR_FIELD)
                    val errorShortcircuit = !errorFields.isEmpty

                    if (newCnt > actionSequenceLimit) {
                        // oops, the dynamic count of actions exceeds the threshold
                        fail(ActivationResponse.applicationError(s"$sequenceIsTooLong"))
                    } else if (!errorShortcircuit) {
                        // all good with this action invocation!
                        success(activation, newCnt)
                    } else {
                        // there is an error field in the activation response. here, we treat this like success,
                        // in the sense of tallying up the accounting fields, but terminate the sequence early
                        success(activation, newCnt, shortcircuit = true)
                    }
                case Left(response) =>
                    // utter failure somewhere downstream
                    fail(response)
            }
        }
    }
    /**
     *  Three constructors for SequenceAccounting:
     *     - one for successful invocation of an action in the sequence,
     *     - one for failed invocation, and
     *     - one to initialize things
     */
    object SequenceAccounting {
        // constructor for successful invocations, or error'ing ones (where shortcircuit = true)
        def apply(prev: SequenceAccounting, newCnt: Int,
                  incrDuration: Option[Long],
                  previousResponse: ActivationResponse,
                  previousActivationId: ActivationId,
                  previousMemoryLimit: Option[Int],
                  shortcircuit: Boolean): SequenceAccounting = {

            // compute the new max memory
            val newMaxMemory = prev.maxMemory map {
                currentMax => // currentMax is Some
                    previousMemoryLimit map {
                        previousLimit => // previousMemoryLimit is Some
                            Some(Math.max(currentMax, previousLimit)) // so take the max of them
                    } getOrElse { prev.maxMemory } // currentMax is Some, previousMemoryLimit is None
            } getOrElse { previousMemoryLimit } // currentMax is None

            // append log entry
            prev.logs.update(prev.cursor, previousActivationId)

            SequenceAccounting(
                atomicActionCnt = newCnt,
                previousResponse = new java.util.concurrent.atomic.AtomicReference(previousResponse),
                logs = prev.logs,
                duration = incrDuration map { prev.duration + _ } getOrElse { prev.duration },
                cursor = prev.cursor + 1,
                maxMemory = newMaxMemory,
                shortcircuit = shortcircuit)
        }

        // constructor for failures, or limits exceeded
        def apply(prev: SequenceAccounting, failureResponse: ActivationResponse): SequenceAccounting =
            SequenceAccounting(prev.atomicActionCnt, new java.util.concurrent.atomic.AtomicReference(failureResponse),
                prev.logs, prev.duration,
                prev.cursor, prev.maxMemory,
                shortcircuit = true)

        // constructor for initial payload
        def apply(length: Int, atomicActionCnt: Int, initialPayload: ActivationResponse): SequenceAccounting =
            SequenceAccounting(atomicActionCnt, new java.util.concurrent.atomic.AtomicReference(initialPayload), new Array[ActivationId](length))

    }

    /**
     * Invokes the components of a sequence in a blocking fashion.
     * Returns a vector of successful futures containing the results of the invocation of all components in the sequence.
     * Unexpected behavior is modeled through an Either with activation(right) or activation response in case of error (left).
     *
     * Keeps track of the dynamic atomic action count.
     * @param user the user invoking the sequence
     * @param seqAction the sequence invoked
     * @param seqActivationId the id of the sequence
     * @param payload the payload passed to the first component in the sequence
     * @param components the components in the sequence
     * @param cause the activation id of the sequence that lead to invoking this sequence or None if this sequence is topmost
     * @param atomicActionCnt the dynamic atomic action count observed so far since the start of the execution of the topmost sequence
     * @return a vector of successful futures; each element contains a tuple with
     *         1. an either with activation(right) or activation response in case of error (left)
     *         2. the dynamic atomic action count after executing the components
     */
    private def invokeSequenceComponents(
        user: Identity,
        seqAction: WhiskAction,
        seqActivationId: ActivationId,
        inputPayload: Option[JsObject],
        components: Vector[FullyQualifiedEntityName],
        cause: Option[ActivationId],
        atomicActionCnt: Int)(
            implicit transid: TransactionId): Future[SequenceAccounting] = {
        // logging.info(this, s"invoke sequence $seqAction ($seqActivationId) with components $components")

        // first retrieve the information/entities on all actions
        // do not wait to successfully retrieve all the actions before starting the execution
        // start execution of the first action while potentially still retrieving entities
        // Note: the execution starts even if one of the futures retrieving an entity may fail
        // first components need to be resolved given any package bindings and the params need to be merged
        // NOTE: OLD-STYLE sequences may have default namespace in the names of the components, resolve default namespace first
        val resolvedFutureActions = resolveDefaultNamespace(components, user) map { c => WhiskAction.resolveActionAndMergeParameters(entityStore, c) }

        // this holds the initial value of the accounting structure, including an ActivationResponse to house the input payload
        val initialAccounting = Future.successful(SequenceAccounting(components.length, atomicActionCnt,
            ActivationResponse.payloadPlaceholder(inputPayload)))

        // execute the wskActions in sequential blocking fashion
        // TODO: double-check the package param policy
        resolvedFutureActions.foldLeft(initialAccounting) {
            (accountingFuture, futureAction) =>
                accountingFuture flatMap { accounting =>
                    if (accounting.shortcircuit) {
                        // this sequence has been short-circuited
                        Future.successful(accounting)
                    } else {
                        futureAction flatMap {
                            action =>
                                val inputPayload = accounting.previousResponse.getAndSet(null).result.map(_.asJsObject)
                                // logging.info(this, s"invoke sequence component of ($seqActivationId) component ${action.name} payload ${inputPayload}")
                                invokeSeqOneComponent(user, action, inputPayload, cause, accounting.atomicActionCnt) map {
                                    case (response, newCnt) => accounting.maybe(response, newCnt)
                                }

                        } recover {
                            // check any failure here and generate an activation response to encapsulate the failure mode
                            case t: Throwable =>
                                // consider this failure a whisk error
                                accounting.fail(ActivationResponse.whiskError(sequenceActivationFailure))
                        }
                    }
                }
        }
    }

    /**
     * Invokes one component from a sequence action. Unless an unexpected whisk failure happens, the future returned is always successful.
     * The return is a tuple of
     *       1. either an activation (right) or an activation response (left) in case the activation could not be retrieved
     *       2. the dynamic count of atomic actions observed so far since the start of the topmost sequence on behalf which this action is executing
     *
     * The method distinguishes between invoking a sequence or an atomic action.
     * @param user the user executing the sequence
     * @param action the action to be invoked
     * @param payload the payload for the action
     * @param cause the activation id of the first sequence containing this action
     * @param atomicActionCount the number of activations
     * @return future with the result of the invocation and the dynamic atomic action count so far
     */
    private def invokeSeqOneComponent(user: Identity, action: WhiskAction, payload: Option[JsObject], cause: Option[ActivationId], atomicActionCount: Int)(
        implicit transid: TransactionId): Future[(Either[ActivationResponse, WhiskActivation], Int)] = {
        // invoke the action by calling the right method depending on whether it's an atomic action or a sequence
        // the tuple contains activationId, wskActivation, atomicActionCount (up till this point in execution)
        val futureWhiskActivationTuple = action.exec match {
            case SequenceExec(components) =>
                // invoke a sequence
                logging.info(this, s"sequence invoking an enclosed sequence $action")
                // call invokeSequence to invoke the inner sequence
                // true for blocking; false for topmost
                invokeSequence(user, action, payload, blocking = true, topmost = false, components, cause, atomicActionCount)
            case _ =>
                // this is an invoke for an atomic action
                logging.info(this, s"sequence invoking an enclosed atomic action $action")
                val timeout = action.limits.timeout.duration + blockingInvokeGrace
                invokeSingleAction(user, action, payload, timeout, blocking = true, cause) map {
                    case (activationId, wskActivation) => (activationId, wskActivation, atomicActionCount + 1)
                }
        }

        futureWhiskActivationTuple map {
            case (activationId, wskActivation, atomicActionCountSoFar) =>
                // the activation is None only if the activation could not be retrieved either from active ack or from db
                wskActivation match {
                    case Some(activation) => (Right(activation), atomicActionCountSoFar)
                    case None => {
                        val activationResponse = ActivationResponse.whiskError(s"$sequenceRetrieveActivationTimeout Activation id '$activationId'.")
                        (Left(activationResponse), atomicActionCountSoFar) // dynamic count doesn't matter, sequence will be interrupted
                    }
                }
        }
    }

    /** Replaces default namespaces in a vector of components from a sequence with appropriate namespace. */
    private def resolveDefaultNamespace(components: Vector[FullyQualifiedEntityName], user: Identity): Vector[FullyQualifiedEntityName] = {
        // if components are part of the default namespace, they contain `_`; replace it!
        val resolvedComponents = components map { c => FullyQualifiedEntityName(c.path.resolveNamespace(user.namespace), c.name) }
        resolvedComponents
    }

    /** Max atomic action count allowed for sequences */
    private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt
}
