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

package org.apache.openwhisk.core.controller.actions

import java.time.{Clock, Instant}
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.{Logging, TransactionId, UserEvents}
import org.apache.openwhisk.core.connector.{EventMessage, MessagingProvider}
import org.apache.openwhisk.core.containerpool.Interval
import org.apache.openwhisk.core.controller.WhiskServices
import org.apache.openwhisk.core.database.{ActivationStore, NoDocumentException, UserContext}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.types._
import org.apache.openwhisk.http.Messages._
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.ExecutionContextFactory.FutureExtensions
import spray.json._

import scala.collection._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

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

  /** Instace of the controller. This is needed to write user-metrics. */
  protected val activeAckTopicIndex: ControllerInstanceId

  /** Message producer. This is needed to write user-metrics. */
  private val messagingProvider = SpiLoader.get[MessagingProvider]
  private val producer = messagingProvider.getProducer(services.whiskConfig)

  /** A method that knows how to invoke a single primitive action. */
  protected[actions] def invokeAction(
    user: Identity,
    action: WhiskActionMetaData,
    payload: Option[JsObject],
    waitForResponse: Option[FiniteDuration],
    cause: Option[ActivationId])(implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]]

  /**
   * Executes a sequence by invoking in a blocking fashion each of its components.
   *
   * @param user the user invoking the action
   * @param action the sequence action to be invoked
   * @param components the actions in the sequence
   * @param payload the dynamic arguments for the activation
   * @param waitForOutermostResponse some duration iff this is a blocking invoke
   * @param cause the id of the activation that caused this sequence (defined only for inner sequences and None for topmost sequences)
   * @param topmost true iff this is the topmost sequence invoked directly through the api (not indirectly through a sequence)
   * @param atomicActionsCount the dynamic atomic action count observed so far since the start of invocation of the topmost sequence(0 if topmost)
   * @param transid a transaction id for logging
   * @return a future of type (ActivationId, Some(WhiskActivation), atomicActionsCount) if blocking; else (ActivationId, None, 0)
   */
  protected[actions] def invokeSequence(
    user: Identity,
    action: WhiskActionMetaData,
    components: Vector[FullyQualifiedEntityName],
    payload: Option[JsObject],
    waitForOutermostResponse: Option[FiniteDuration],
    cause: Option[ActivationId],
    topmost: Boolean,
    atomicActionsCount: Int)(implicit transid: TransactionId): Future[(Either[ActivationId, WhiskActivation], Int)] = {
    require(action.exec.kind == Exec.SEQUENCE, "this method requires an action sequence")

    // create new activation id that corresponds to the sequence
    val seqActivationId = activationIdFactory.make()
    logging.info(this, s"invoking sequence $action topmost $topmost activationid '$seqActivationId'")

    val start = Instant.now(Clock.systemUTC())
    val futureSeqResult: Future[(Either[ActivationId, WhiskActivation], Int)] = {
      // even though the result of completeSequenceActivation is Right[WhiskActivation],
      // use a more general type for futureSeqResult in case a blocking invoke takes
      // longer than expected and we must return Left[ActivationId] instead
      completeSequenceActivation(
        seqActivationId,
        // the cause for the component activations is the current sequence
        invokeSequenceComponents(
          user,
          action,
          seqActivationId,
          payload,
          components,
          cause = Some(seqActivationId),
          atomicActionsCount),
        user,
        action,
        topmost,
        waitForOutermostResponse.isDefined,
        start,
        cause)
    }

    if (topmost) { // need to deal with blocking and closing connection
      waitForOutermostResponse
        .map { timeout =>
          logging.debug(this, s"invoke sequence blocking topmost!")
          futureSeqResult.withAlternativeAfterTimeout(
            timeout,
            Future.successful(Left(seqActivationId), atomicActionsCount))
        }
        .getOrElse {
          // non-blocking sequence execution, return activation id
          Future.successful(Left(seqActivationId), 0)
        }
    } else {
      // not topmost, no need to worry about terminating incoming request
      // and this is a blocking activation therefore by definition
      // Note: the future for the sequence result recovers from all throwable failures
      futureSeqResult
    }
  }

  /**
   * Creates an activation for the sequence and writes it back to the datastore.
   */
  private def completeSequenceActivation(seqActivationId: ActivationId,
                                         futureSeqResult: Future[SequenceAccounting],
                                         user: Identity,
                                         action: WhiskActionMetaData,
                                         topmost: Boolean,
                                         blockingSequence: Boolean,
                                         start: Instant,
                                         cause: Option[ActivationId])(
    implicit transid: TransactionId): Future[(Right[ActivationId, WhiskActivation], Int)] = {
    val context = UserContext(user)

    // not topmost, no need to worry about terminating incoming request
    // Note: the future for the sequence result recovers from all throwable failures
    futureSeqResult
      .map { accounting =>
        // sequence terminated, the result of the sequence is the result of the last completed activation
        val end = Instant.now(Clock.systemUTC())
        val seqActivation =
          makeSequenceActivation(user, action, seqActivationId, accounting, topmost, cause, start, end)
        (Right(seqActivation), accounting.atomicActionCnt)
      }
      .andThen {
        case Success((Right(seqActivation), _)) =>
          if (UserEvents.enabled) {
            EventMessage.from(seqActivation, s"controller${activeAckTopicIndex.asString}", user.namespace.uuid) match {
              case Success(msg) => UserEvents.send(producer, msg)
              case Failure(t)   => logging.warn(this, s"activation event was not sent: $t")
            }
          }

          activationStore.storeAfterCheck(seqActivation, blockingSequence, None, context)(
            transid,
            notifier = None,
            logging)

        // This should never happen; in this case, there is no activation record created or stored:
        // should there be?
        case Failure(t) => logging.error(this, s"sequence activation failed: ${t.getMessage}")
      }
  }

  /**
   * Creates an activation for a sequence.
   */
  private def makeSequenceActivation(user: Identity,
                                     action: WhiskActionMetaData,
                                     activationId: ActivationId,
                                     accounting: SequenceAccounting,
                                     topmost: Boolean,
                                     cause: Option[ActivationId],
                                     start: Instant,
                                     end: Instant)(implicit transid: TransactionId): WhiskActivation = {

    // compute max memory
    val sequenceLimits = accounting.maxMemory map { maxMemoryAcrossActionsInSequence =>
      Parameters(
        WhiskActivation.limitsAnnotation,
        ActionLimits(action.limits.timeout, MemoryLimit(maxMemoryAcrossActionsInSequence MB), action.limits.logs).toJson)
    }

    // set causedBy if not topmost sequence
    val causedBy = if (!topmost) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else None

    // set waitTime for sequence action
    val waitTime = {
      Parameters(WhiskActivation.waitTimeAnnotation, Interval(transid.meta.start, start).duration.toMillis.toJson)
    }

    // set binding if an invoked action is in a package binding
    val binding = action.binding map { path =>
      Parameters(WhiskActivation.bindingAnnotation, JsString(path.asString))
    }

    // create the whisk activation
    WhiskActivation(
      namespace = user.namespace.name.toPath,
      name = action.name,
      user.subject,
      activationId = activationId,
      start = start,
      end = end,
      cause = if (topmost) None else cause, // propagate the cause for inner sequences, but undefined for topmost
      response = accounting.previousResponse.getAndSet(null), // getAndSet(null) drops reference to the activation result
      logs = accounting.finalLogs,
      version = action.version,
      publish = false,
      annotations = Parameters(WhiskActivation.topmostAnnotation, JsBoolean(topmost)) ++
        Parameters(WhiskActivation.pathAnnotation, JsString(action.fullyQualifiedName(false).asString)) ++
        Parameters(WhiskActivation.kindAnnotation, JsString(Exec.SEQUENCE)) ++
        causedBy ++ waitTime ++ binding ++
        sequenceLimits,
      duration = Some(accounting.duration))
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
   * @param inputPayload the payload passed to the first component in the sequence
   * @param components the components in the sequence
   * @param cause the activation id of the sequence that lead to invoking this sequence or None if this sequence is topmost
   * @param atomicActionCnt the dynamic atomic action count observed so far since the start of the execution of the topmost sequence
   * @return a future which resolves with the accounting for a sequence, including the last result, duration, and activation ids
   */
  private def invokeSequenceComponents(
    user: Identity,
    seqAction: WhiskActionMetaData,
    seqActivationId: ActivationId,
    inputPayload: Option[JsObject],
    components: Vector[FullyQualifiedEntityName],
    cause: Option[ActivationId],
    atomicActionCnt: Int)(implicit transid: TransactionId): Future[SequenceAccounting] = {

    // For each action in the sequence, fetch any of its associated parameters (including package or binding).
    // We do this for all of the actions in the sequence even though it may be short circuited. This is to
    // hide the latency of the fetches from the datastore and the parameter merging that has to occur. It
    // may be desirable in the future to selectively speculate over a smaller number of components rather than
    // the entire sequence.
    //
    // This action/parameter resolution is done in futures; the execution starts as soon as the first component
    // is resolved.
    val resolvedFutureActions = resolveDefaultNamespace(components, user) map { c =>
      WhiskActionMetaData.resolveActionAndMergeParameters(entityStore, c)
    }

    // this holds the initial value of the accounting structure, including the input boxed as an ActivationResponse
    val initialAccounting = Future.successful {
      SequenceAccounting(atomicActionCnt, ActivationResponse.payloadPlaceholder(inputPayload))
    }

    // execute the actions in sequential blocking fashion
    resolvedFutureActions
      .foldLeft(initialAccounting) { (accountingFuture, futureAction) =>
        accountingFuture.flatMap { accounting =>
          if (accounting.atomicActionCnt < actionSequenceLimit) {
            invokeNextAction(user, futureAction, accounting, cause, transid)
              .flatMap { accounting =>
                if (!accounting.shortcircuit) {
                  Future.successful(accounting)
                } else {
                  // this is to short circuit the fold
                  Future.failed(FailedSequenceActivation(accounting)) // terminates the fold
                }
              }
              .recoverWith {
                case _: NoDocumentException =>
                  val updatedAccount =
                    accounting.fail(ActivationResponse.applicationError(sequenceComponentNotFound), None)
                  Future.failed(FailedSequenceActivation(updatedAccount)) // terminates the fold
              }
          } else {
            val updatedAccount = accounting.fail(ActivationResponse.applicationError(sequenceIsTooLong), None)
            Future.failed(FailedSequenceActivation(updatedAccount)) // terminates the fold
          }
        }
      }
      .recoverWith {
        // turn the failed accounting back to success; this is the only possible failure
        // since all throwables are recovered with a failed accounting instance and this is
        // in turned boxed to FailedSequenceActivation
        case FailedSequenceActivation(accounting) => Future.successful(accounting)
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
   * @param futureAction the future which fetches the action to be invoked from the db
   * @param accounting the state of the sequence activation, contains the dynamic activation count, logs and payload for the next action
   * @param cause the activation id of the first sequence containing this activations
   * @return a future which resolves with updated accounting for a sequence, including the last result, duration, and activation ids
   */
  private def invokeNextAction(user: Identity,
                               futureAction: Future[WhiskActionMetaData],
                               accounting: SequenceAccounting,
                               cause: Option[ActivationId],
                               parentTid: TransactionId): Future[SequenceAccounting] = {
    futureAction.flatMap { action =>
      implicit val transid: TransactionId = TransactionId.childOf(parentTid)

      // the previous response becomes input for the next action in the sequence;
      // the accounting no longer needs to hold a reference to it once the action is
      // invoked, so previousResponse.getAndSet(null) drops the reference at this point
      // which prevents dragging the previous response for the lifetime of the next activation
      val inputPayload = accounting.previousResponse.getAndSet(null).result.map(_.asJsObject)

      // invoke the action by calling the right method depending on whether it's an atomic action or a sequence
      val futureWhiskActivationTuple = action.toExecutableWhiskAction match {
        case None =>
          val SequenceExecMetaData(components) = action.exec
          logging.debug(this, s"sequence invoking an enclosed sequence $action")
          // call invokeSequence to invoke the inner sequence; this is a blocking activation by definition
          invokeSequence(
            user,
            action,
            components,
            inputPayload,
            None,
            cause,
            topmost = false,
            accounting.atomicActionCnt)
        case Some(executable) =>
          // this is an invoke for an atomic action
          logging.debug(this, s"sequence invoking an enclosed atomic action $action")
          val timeout = action.limits.timeout.duration + 1.minute
          invokeAction(user, action, inputPayload, waitForResponse = Some(timeout), cause) map {
            case res => (res, accounting.atomicActionCnt + 1)
          }
      }

      futureWhiskActivationTuple
        .map {
          case (Right(activation), atomicActionCountSoFar) =>
            accounting.maybe(activation, atomicActionCountSoFar, actionSequenceLimit)

          case (Left(activationId), atomicActionCountSoFar) =>
            // the result could not be retrieved in time either from active ack or from db
            logging.error(this, s"component activation timedout for $activationId")
            val activationResponse = ActivationResponse.whiskError(sequenceRetrieveActivationTimeout(activationId))
            accounting.fail(activationResponse, Some(activationId))

        }
        .recover {
          // check any failure here and generate an activation response to encapsulate
          // the failure mode; consider this failure a whisk error
          case t: Throwable =>
            logging.error(this, s"component activation failed: $t")
            accounting.fail(ActivationResponse.whiskError(sequenceActivationFailure), None)
        }
    }
  }

  /** Replaces default namespaces in a vector of components from a sequence with appropriate namespace. */
  private def resolveDefaultNamespace(components: Vector[FullyQualifiedEntityName],
                                      user: Identity): Vector[FullyQualifiedEntityName] = {
    // resolve any namespaces that may appears as "_" (the default namespace)
    components.map(c => FullyQualifiedEntityName(c.path.resolveNamespace(user.namespace), c.name))
  }

  /** Max atomic action count allowed for sequences */
  private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt

}

/**
 * Cumulative accounting of what happened during the execution of a sequence.
 *
 * @param atomicActionCnt the current count of non-sequence (c.f. atomic) actions already invoked
 * @param previousResponse a reference to the previous activation result which will be nulled out
 *        when no longer needed (see previousResponse.getAndSet(null) below)
 * @param logs a mutable buffer that is appended with new activation ids as the sequence unfolds
 * @param duration the "user" time so far executing the sequence (sum of durations for
 *        all actions invoked so far which is different from the total time spent executing the sequence)
 * @param maxMemory the maximum memory annotation observed so far for the
 *        components (needed to annotate the sequence with GB-s)
 * @param shortcircuit when true, stops the execution of the next component in the sequence
 */
protected[actions] case class SequenceAccounting(atomicActionCnt: Int,
                                                 previousResponse: AtomicReference[ActivationResponse],
                                                 logs: mutable.Buffer[ActivationId],
                                                 duration: Long = 0,
                                                 maxMemory: Option[Int] = None,
                                                 shortcircuit: Boolean = false) {

  /** @return the ActivationLogs data structure for this sequence invocation */
  def finalLogs = ActivationLogs(logs.map(id => id.asString).toVector)

  /** The previous activation was successful. */
  private def success(activation: WhiskActivation, newCnt: Int, shortcircuit: Boolean = false) = {
    previousResponse.set(null)
    SequenceAccounting(
      prev = this,
      newCnt = newCnt,
      shortcircuit = shortcircuit,
      incrDuration = activation.duration,
      newResponse = activation.response,
      newActivationId = activation.activationId,
      newMemoryLimit = activation.annotations.get("limits") map { limitsAnnotation => // we have a limits annotation
        limitsAnnotation.asJsObject.getFields("memory") match {
          case Seq(JsNumber(memory)) =>
            Some(memory.toInt) // we have a numerical "memory" field in the "limits" annotation
        }
      } getOrElse { None })
  }

  /** The previous activation failed (this is used when there is no activation record or an internal error. */
  def fail(failureResponse: ActivationResponse, activationId: Option[ActivationId]) = {
    require(!failureResponse.isSuccess)
    logs.appendAll(activationId)
    copy(previousResponse = new AtomicReference(failureResponse), shortcircuit = true)
  }

  /** Determines whether the previous activation succeeded or failed. */
  def maybe(activation: WhiskActivation, newCnt: Int, maxSequenceCnt: Int) = {
    // check conditions on payload that may lead to interrupting the execution of the sequence
    //     short-circuit the execution of the sequence iff the payload contains an error field
    //     and is the result of an action return, not the initial payload
    val outputPayload = activation.response.result.map(_.asJsObject)
    val payloadContent = outputPayload getOrElse JsObject.empty
    val errorField = payloadContent.fields.get(ActivationResponse.ERROR_FIELD)
    val withinSeqLimit = newCnt <= maxSequenceCnt

    if (withinSeqLimit && errorField.isEmpty) {
      // all good with this action invocation
      success(activation, newCnt)
    } else {
      val nextActivation = if (!withinSeqLimit) {
        // no error in the activation but the dynamic count of actions exceeds the threshold
        // this is here as defensive code; the activation should not occur if its takes the
        // count above its limit
        val newResponse = ActivationResponse.applicationError(sequenceIsTooLong)
        activation.copy(response = newResponse)
      } else {
        assert(errorField.isDefined)
        activation
      }

      // there is an error field in the activation response. here, we treat this like success,
      // in the sense of tallying up the accounting fields, but terminate the sequence early
      success(nextActivation, newCnt, shortcircuit = true)
    }
  }
}

/**
 *  Three constructors for SequenceAccounting:
 *     - one for successful invocation of an action in the sequence,
 *     - one for failed invocation, and
 *     - one to initialize things
 */
protected[actions] object SequenceAccounting {

  def maxMemory(prevMemoryLimit: Option[Int], newMemoryLimit: Option[Int]): Option[Int] = {
    (prevMemoryLimit ++ newMemoryLimit).reduceOption(Math.max)
  }

  // constructor for successful invocations, or error'ing ones (where shortcircuit = true)
  def apply(prev: SequenceAccounting,
            newCnt: Int,
            incrDuration: Option[Long],
            newResponse: ActivationResponse,
            newActivationId: ActivationId,
            newMemoryLimit: Option[Int],
            shortcircuit: Boolean): SequenceAccounting = {

    // compute the new max memory
    val newMaxMemory = maxMemory(prev.maxMemory, newMemoryLimit)

    // append log entry
    prev.logs += newActivationId

    SequenceAccounting(
      atomicActionCnt = newCnt,
      previousResponse = new AtomicReference(newResponse),
      logs = prev.logs,
      duration = incrDuration map { prev.duration + _ } getOrElse { prev.duration },
      maxMemory = newMaxMemory,
      shortcircuit = shortcircuit)
  }

  // constructor for initial payload
  def apply(atomicActionCnt: Int, initialPayload: ActivationResponse): SequenceAccounting = {
    SequenceAccounting(atomicActionCnt, new AtomicReference(initialPayload), mutable.Buffer.empty)
  }
}

protected[actions] case class FailedSequenceActivation(accounting: SequenceAccounting) extends Throwable
