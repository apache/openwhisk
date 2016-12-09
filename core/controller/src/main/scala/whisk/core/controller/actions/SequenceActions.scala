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
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.ActorSystem
import spray.json._
import whisk.common.Logging
import whisk.common.PrintStreamEmitter
import whisk.common.TransactionId
import whisk.core.controller.BlockingInvokeTimeout
import whisk.core.controller.WhiskActionsApi._
import whisk.core.controller.WhiskServices
import whisk.core.entity._
import whisk.core.entity.size.SizeInt
import whisk.core.entity.types._
import whisk.http.Messages._
import whisk.utils.ExecutionContextFactory.FutureExtensions

protected[actions] trait SequenceActions extends Logging {
    /** The core collections require backend services to be injected in this trait. */
    services: WhiskServices =>

    /** An actor system for timed based futures. */
    protected implicit val actorSystem: ActorSystem

    /** An execution context for futures. */
    protected implicit val executionContext: ExecutionContext

    /** Database service to CRUD actions. */
    protected val entityStore: EntityStore

    /** Database service to get activations. */
    protected val activationStore: ActivationStore

    private implicit val emitter = this: PrintStreamEmitter

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
        info(this, s"invoking sequence $action topmost $topmost activationid '$seqActivationId'")
        val start = Instant.now(Clock.systemUTC())
        val seqActivationPromise = Promise[Option[WhiskActivation]]
        // the cause for the component activations is the current sequence
        val futureWskActivations = invokeSequenceComponents(user, action, seqActivationId, payload, components, cause = Some(seqActivationId), atomicActionsCount)
        val futureSeqResult = Future.sequence(futureWskActivations)
        val response: Future[(ActivationId, Option[WhiskActivation], Int)] =
            if (topmost) { // need to deal with blocking and closing connection
                if (blocking) {
                    val timeout = maxWaitForBlockingActivation + blockingInvokeGrace
                    val futureSeqResultTimeout = futureSeqResult withTimeout (timeout, new BlockingInvokeTimeout(seqActivationId))
                    // if the future fails with a timeout, the failure is dealt with at the caller level
                    futureSeqResultTimeout map { wskActivationTuples =>
                        val wskActivationEithers = wskActivationTuples.map(_._1)
                        // the execution of the sequence was successful, return the result
                        val end = Instant.now(Clock.systemUTC())
                        val seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end))
                        val atomicActionCnt = wskActivationTuples.last._2
                        (seqActivationId, seqActivation, atomicActionCnt)
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
                futureSeqResult map { wskActivationTuples =>
                    val wskActivationEithers = wskActivationTuples.map(_._1)
                    // all activations are successful, the result of the sequence is the result of the last activation
                    val end = Instant.now(Clock.systemUTC())
                    val seqActivation = Some(makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end))
                    val atomicActionCnt = wskActivationTuples.last._2
                    (seqActivationId, seqActivation, atomicActionCnt)
                } andThen {
                    case Success((_, seqActivation, _)) => seqActivationPromise.success(seqActivation)
                    case Failure(t)                     => seqActivationPromise.success(None)
                }
            }

        // store result of sequence execution
        // if seqActivation is defined, use it; otherwise create it (e.g., for non-blocking activations)
        // the execution can reach here without a seqActivation due to non-blocking activations OR blocking activations that reach the blocking invoke timeout
        // futureSeqResult should always be successful, if failed, there is an error
        futureSeqResult flatMap { tuples => seqActivationPromise.future map { (tuples, _) } } onComplete {
            case Success((wskActivationTuples, seqActivation)) =>
                // all activations were successful
                val activation = seqActivation getOrElse {
                    val wskActivationEithers = wskActivationTuples.map(_._1)
                    val end = Instant.now(Clock.systemUTC())
                    // the response of the sequence is the response of the very last activation
                    makeSequenceActivation(user, action, seqActivationId, wskActivationEithers, topmost, cause, start, end)
                }
                storeSequenceActivation(activation)
            case Failure(t: Throwable) =>
                // consider this whisk error
                // TODO shall we attempt storing the activation if it exists or even inspect the futures?
                // this should be a pretty serious whisk errror if it gets here
                error(this, s"Sequence activation failed: ${t.getMessage}")
        }

        response
    }

    /**
     * Stores sequence activation to database.
     */
    private def storeSequenceActivation(activation: WhiskActivation)(implicit transid: TransactionId): Unit = {
        info(this, s"recording activation '${activation.activationId}'")
        WhiskActivation.put(activationStore, activation) onComplete {
            case Success(id) => info(this, s"recorded activation")
            case Failure(t)  => error(this, s"failed to record activation")
        }
    }

    /**
     * Creates an activation for a sequence.
     */
    private def makeSequenceActivation(
        user: Identity,
        action: WhiskAction,
        activationId: ActivationId,
        wskActivationEithers: Vector[Either[ActivationResponse, WhiskActivation]],
        topmost: Boolean,
        cause: Option[ActivationId],
        start: Instant,
        end: Instant): WhiskActivation = {

        // extract all successful activations from the vector of activation eithers
        // the vector is either all rights, all lefts, or some rights followed by some lefts (no interleaving)
        val (right, left) = wskActivationEithers.span(_.isRight)
        val wskActivations = right.map(_.right.get)

        // the activation response is either the first left if it exists or the response of the last successful activation
        val activationResponse = if (left.length == 0) {
            wskActivations.last.response
        } else {
            left.head.left.get
        }

        // compose logs
        val logs = ActivationLogs(wskActivations map {
            activation => activation.activationId.toString
        })

        // compute duration
        val duration = (wskActivations map { activation =>
            activation.duration getOrElse {
                error(this, s"duration for $activation is not defined")
                activation.end.toEpochMilli - activation.start.toEpochMilli
            }
        }).sum

        // compute max memory
        val maxMemory = Try {
            val memoryLimits = wskActivations map { activation =>
                val limits = ActionLimits.serdes.read(activation.annotations("limits").get)
                limits.memory.megabytes
            }
            memoryLimits.max.MB
        }

        val sequenceLimits = maxMemory map {
            mb => ActionLimits(action.limits.timeout, MemoryLimit(mb), action.limits.logs)
        }

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
            response = activationResponse,
            logs = logs,
            version = action.version,
            publish = false,
            annotations = Parameters("topmost", JsBoolean(topmost)) ++
                Parameters("path", action.fullyQualifiedName(false).toString) ++
                Parameters("kind", "sequence") ++
                causedBy ++
                sequenceLimits.map(l => Parameters("limits", l.toJson)).getOrElse(Parameters()),
            duration = Some(duration))
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
        payload: Option[JsObject],
        components: Vector[FullyQualifiedEntityName],
        cause: Option[ActivationId],
        atomicActionCnt: Int)(
            implicit transid: TransactionId): Vector[Future[(Either[ActivationResponse, WhiskActivation], Int)]] = {
        info(this, s"invoke sequence $seqAction ($seqActivationId) with components $components")

        // first retrieve the information/entities on all actions
        // do not wait to successfully retrieve all the actions before starting the execution
        // start execution of the first action while potentially still retrieving entities
        // Note: the execution starts even if one of the futures retrieving an entity may fail
        // first components need to be resolved given any package bindings and the params need to be merged
        // NOTE: OLD-STYLE sequences may have default namespace in the names of the components, resolve default namespace first
        val resolvedFutureActions = resolveDefaultNamespace(components, user) map { c => WhiskAction.resolveActionAndMergeParameters(entityStore, c) }

        // "scan" the wskActions to execute them in blocking fashion
        // use scanLeft instead of foldLeft as we need the intermediate results
        // TODO: double-check the package param policy
        // env are the parameters for the package that the sequence is in; throw them away, not used in the sequence execution
        // create a "fake" WhiskActivation to hold the payload of the sequence to init the scanLeft
        val fakeStart = Instant.now()
        val fakeEnd = Instant.now()
        val fakeResponse = ActivationResponse.payloadPlaceholder(payload)

        // NOTE: the init value is a fake activation to bootstrap the invocations of actions; in case of error, the previous activation response is used; for this reason,
        // the fake init activation has as activation response application error - useful in the case the payload itself contains an error field, unused otherwise
        val initFakeWhiskActivation: Future[(Either[ActivationResponse, WhiskActivation], Int)] = Future successful {
            (Right(WhiskActivation(seqAction.namespace, seqAction.name, user.subject, seqActivationId, fakeStart, fakeEnd, response = fakeResponse, duration = None)), atomicActionCnt)
        }

        // seqComponentWskActivationFutures contains a fake activation on the first position in the vector; the rest of the vector is the result of each component execution/activation
        val seqComponentWskActivationFutures = resolvedFutureActions.scanLeft(initFakeWhiskActivation) {
            (futureActivationAtomicCntPair, futureAction) =>
                futureAction flatMap {
                    action =>
                        futureActivationAtomicCntPair flatMap {
                            case (activationEither, atomicActionCount) =>
                                activationEither match {
                                    case Right(activation) =>
                                        val payload = activation.response.result.map(_.asJsObject)
                                        // first check conditions on payload that may lead to interrupting the execution of the sequence
                                        val payloadContent = payload getOrElse JsObject.empty
                                        val errorFields = payloadContent.getFields(ActivationResponse.ERROR_FIELD)
                                        if (errorFields.isEmpty) {
                                            // second check the atomic action count for sequence action limit)
                                            if (atomicActionCount >= actionSequenceLimit) {
                                                val activationResponse = ActivationResponse.applicationError(s"$sequenceIsTooLong")
                                                Future.successful(Left(activationResponse), atomicActionCount) // dynamic action count doesn't matter anymore
                                            } else {
                                                invokeSeqOneComponent(user, action, payload, cause, atomicActionCount)
                                            }
                                        } else {
                                            // there is an error field, terminate sequence early
                                            // propagate the activation response
                                            Future.successful(Left(activation.response), atomicActionCount) // dynamic action count doesn't matter anymore
                                        }
                                    case Left(activationResponse) =>
                                        // the sequence is interrupted, no more processing
                                        Future.successful(Left(activationResponse), 0) // dynamic action count does not matter from now on
                                }
                        }
                } recover {
                    // check any failure here and generate an activation response such that this method always returns a vector of successful futures
                    case t: Throwable =>
                        // consider this failure a whisk error
                        val activationResponse = ActivationResponse.whiskError(sequenceActivationFailure)
                        (Left(activationResponse), 0)
                }
        }

        seqComponentWskActivationFutures.drop(1) // drop the first future which contains the init value from scanLeft
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
            case SequenceExec(_, components) =>
                // invoke a sequence
                info(this, s"sequence invoking an enclosed sequence $action")
                // call invokeSequence to invoke the inner sequence
                // true for blocking; false for topmost
                invokeSequence(user, action, payload, blocking = true, topmost = false, components, cause, atomicActionCount) map {
                    case (activationId, wskActivation, seqAtomicActionCnt) =>
                        (activationId, wskActivation, seqAtomicActionCnt + atomicActionCount)
                }
            case _ =>
                // this is an invoke for an atomic action
                info(this, s"sequence invoking an enclosed atomic action $action")
                val timeout = action.limits.timeout() + blockingInvokeGrace
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
