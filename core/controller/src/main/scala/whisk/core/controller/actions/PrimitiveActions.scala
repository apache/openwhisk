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

package whisk.core.controller.actions

import java.time.Clock
import java.time.Instant

import scala.collection.mutable.Buffer
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Props
import spray.json._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.Scheduler
import whisk.common.TransactionId
import whisk.core.connector.ActivationMessage
import whisk.core.controller.WhiskServices
import whisk.core.database.NoDocumentException
import whisk.core.entitlement._
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entity.size.SizeInt
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.EntityStore
import whisk.http.Messages._
import whisk.utils.ExecutionContextFactory.FutureExtensions

protected[actions] trait PrimitiveActions {
  /** The core collections require backend services to be injected in this trait. */
  services: WhiskServices =>

  /** An actor system for timed based futures. */
  protected implicit val actorSystem: ActorSystem

  /** An execution context for futures. */
  protected implicit val executionContext: ExecutionContext

  protected implicit val logging: Logging

  /**
   *  The index of the active ack topic, this controller is listening for.
   *  Typically this is also the instance number of the controller
   */
  protected val activeAckTopicIndex: InstanceId

  /** Database service to CRUD actions. */
  protected val entityStore: EntityStore

  /** Database service to get activations. */
  protected val activationStore: ActivationStore

  /** A method that knows how to invoke a sequence of actions. */
  protected[actions] def invokeSequence(
    user: Identity,
    action: WhiskActionMetaData,
    components: Vector[FullyQualifiedEntityName],
    payload: Option[JsObject],
    waitForOutermostResponse: Option[FiniteDuration],
    cause: Option[ActivationId],
    topmost: Boolean,
    atomicActionsCount: Int)(implicit transid: TransactionId): Future[(Either[ActivationId, WhiskActivation], Int)]

  /**
   * A method that knows how to invoke a single primitive action or a composition.
   *
   * A composition is a kind of sequence of actions that is dynamically computed.
   * The execution of a composition is triggered by the invocation of a conductor action.
   * A conductor action is an executable action with a defined "conductor" annotation (the value does not matter).
   * Sequences cannot be compositions: the "conductor" annotation on a sequence has no effect.
   *
   * A conductor action may either return a final result or a triplet { action, params, state }.
   * In the latter case, the specified component action is invoked on the specified params object.
   * Upon completion of this action the conductor action is reinvoked with a payload that combines
   * the output of the action with the state returned by the previous conductor invocation.
   * The composition result is the result of the final conductor invocation in the chain of invocations.
   *
   * The trace of a composition obeys the grammar: conductorInvocation(componentInvocation conductorInvocation)*
   *
   * The activation records for a composition and its components mimic the activation records of sequences.
   * They include the same "topmost", "kind", and "causedBy" annotations with the same semantics.
   * The activation record for a composition also includes a specific annotation "conductor" with value true.
   */
  protected[actions] def invokeSingleAction(
    user: Identity,
    action: ExecutableWhiskActionMetaData,
    payload: Option[JsObject],
    waitForResponse: Option[FiniteDuration],
    cause: Option[ActivationId])(implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {

    if (action.annotations.get(WhiskActivation.conductorAnnotation).isDefined) {
      invokeComposition(user, action, payload, waitForResponse, cause)
    } else {
      invokeSimpleAction(user, action, payload, waitForResponse, cause)
    }
  }

  /**
   * A method that knows how to invoke a single primitive action.
   *
   * Posts request to the loadbalancer. If the loadbalancer accepts the requests with an activation id,
   * then wait for the result of the activation if necessary.
   *
   * NOTE:
   * Cause is populated only for actions that were invoked as a result of a sequence activation or a composition.
   * For actions that are enclosed in a sequence and are activated as a result of the sequence activation, the cause
   * contains the activation id of the immediately enclosing sequence.
   * e.g.,: s -> a, x, c    and   x -> c  (x and s are sequences, a, b, c atomic actions)
   * cause for a, x, c is the activation id of s
   * cause for c is the activation id of x
   * cause for s is not defined
   * For actions that are enclosed in a composition and are activated as a result of the composition activation,
   * the cause contains the activation id of the immediately enclosing composition.
   *
   * @param user the identity invoking the action
   * @param action the action to invoke
   * @param payload the dynamic arguments for the activation
   * @param waitForResponse if not empty, wait upto specified duration for a response (this is used for blocking activations)
   * @param cause the activation id that is responsible for this invoke/activation
   * @param transid a transaction id for logging
   * @return a promise that completes with one of the following successful cases:
   *            Right(WhiskActivation) if waiting for a response and response is ready within allowed duration,
   *            Left(ActivationId) if not waiting for a response, or allowed duration has elapsed without a result ready
   *         or these custom failures:
   *            RequestEntityTooLarge if the message is too large to to post to the message bus
   */
  private def invokeSimpleAction(
    user: Identity,
    action: ExecutableWhiskActionMetaData,
    payload: Option[JsObject],
    waitForResponse: Option[FiniteDuration],
    cause: Option[ActivationId])(implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {

    // merge package parameters with action (action parameters supersede), then merge in payload
    val args = action.parameters merge payload
    val message = ActivationMessage(
      transid,
      FullyQualifiedEntityName(action.namespace, action.name, Some(action.version)),
      action.rev,
      user,
      activationIdFactory.make(), // activation id created here
      activeAckTopicIndex,
      waitForResponse.isDefined,
      args,
      cause = cause)

    val startActivation = transid.started(
      this,
      waitForResponse
        .map(_ => LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING)
        .getOrElse(LoggingMarkers.CONTROLLER_ACTIVATION))
    val startLoadbalancer =
      transid.started(this, LoggingMarkers.CONTROLLER_LOADBALANCER, s"action activation id: ${message.activationId}")
    val postedFuture = loadBalancer.publish(action, message)

    postedFuture.flatMap { activeAckResponse =>
      // successfully posted activation request to the message bus
      transid.finished(this, startLoadbalancer)

      // is caller waiting for the result of the activation?
      waitForResponse
        .map { timeout =>
          // yes, then wait for the activation response from the message bus
          // (known as the active response or active ack)
          waitForActivationResponse(user, message.activationId, timeout, activeAckResponse)
            .andThen { case _ => transid.finished(this, startActivation) }
        }
        .getOrElse {
          // no, return the activation id
          transid.finished(this, startActivation)
          Future.successful(Left(message.activationId))
        }
    }
  }

  /**
   * Mutable cumulative accounting of what happened during the execution of a composition.
   *
   * Compositions are aborted if the number of action invocations exceeds a limit.
   * The permitted max is n component invocations plus 2n+1 conductor invocations (where n is the actionSequenceLimit).
   * The max is chosen to permit a sequence with up to n primitive actions.
   *
   * NOTE:
   * A sequence invocation counts as one invocation irrespective of the number of action invocations in the sequence.
   * If one component of a composition is also a composition, the caller and callee share the same accounting object.
   * The counts are shared between callers and callees so the limit applies globally.
   *
   * @param components the current count of component actions already invoked
   * @param conductors the current count of conductor actions already invoked
   */
  private case class CompositionAccounting(var components: Int = 0, var conductors: Int = 0)

  /**
   * A mutable session object to keep track of the execution of one composition.
   *
   * NOTE:
   * The session object is not shared between callers and callees.
   * A callee has a reference to the session object for the caller.
   * This permits the callee to return to the caller when done.
   *
   * @param activationId the activationId for the composition (ie the activation record for the composition)
   * @param start the start time for the composition
   * @param action the conductor action responsible for the execution of the composition
   * @param cause the cause of the composition (activationId of the enclosing sequence or composition if any)
   * @param duration the "user" time so far executing the composition (sum of durations for
   *        all actions invoked so far which is different from the total time spent executing the composition)
   * @param maxMemory the maximum memory annotation observed so far for the conductor action and components
   * @param state the json state object to inject in the parameter object of the next conductor invocation
   * @param accounting the global accounting object used to abort compositions requiring too many action invocations
   * @param logs a mutable buffer that is appended with new activation ids as the composition unfolds
   *             (in contrast with sequences, the logs of a hierarchy of compositions is not flattened)
   * @param caller the session object for the parent composition (caller) if any
   * @param result a placeholder for returning the result of the composition invocation (blocking invocation only)
   */
  private case class Session(activationId: ActivationId,
                             start: Instant,
                             action: ExecutableWhiskActionMetaData,
                             cause: Option[ActivationId],
                             var duration: Long,
                             var maxMemory: Int,
                             var state: Option[JsObject],
                             accounting: CompositionAccounting,
                             logs: Buffer[ActivationId],
                             caller: Option[Session],
                             result: Option[Promise[Either[ActivationId, WhiskActivation]]])

  /**
   * A method that knows how to invoke a composition.
   *
   * The method instantiates the session object for the composition, invokes the conductor action for the composition,
   * and waits for the composition result (resulting activation) if the invocation is blocking (up to timeout) or if
   * the invocation is nested inside an enclosing sequence.
   *
   * @param user the identity invoking the action
   * @param action the conductor action to invoke for the composition
   * @param payload the dynamic arguments for the activation
   * @param waitForResponse if not empty, wait upto specified duration for a response (this is used for blocking activations)
   * @param cause the activation id that is responsible for this invoke/activation
   * @param caller the session object for the caller if any
   * @param transid a transaction id for logging
   * @return a promise that completes with one of the following successful cases:
   *            Right(WhiskActivation) if waiting for a response and response is ready within allowed duration,
   *            Left(ActivationId) if not waiting for a response, or allowed duration has elapsed without a result ready
   *         or an error
   */
  private def invokeComposition(
    user: Identity,
    action: ExecutableWhiskActionMetaData,
    payload: Option[JsObject],
    waitForResponse: Option[FiniteDuration],
    cause: Option[ActivationId],
    caller: Option[Session] = None)(implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {

    // should wait for result if topmost blocking invoke or invoked from a sequence
    val shouldWait = waitForResponse.isDefined || cause.isDefined && !caller.isDefined

    // result promise if waiting
    val result = if (shouldWait) Some(Promise[Either[ActivationId, WhiskActivation]]()) else None

    val session = Session(
      activationId = activationIdFactory.make(),
      start = Instant.now(Clock.systemUTC()),
      action,
      cause,
      duration = 0,
      maxMemory = action.limits.memory.megabytes,
      state = None,
      accounting = caller.map { _.accounting }.getOrElse(CompositionAccounting()), // share accounting with caller
      logs = Buffer.empty,
      caller,
      result)

    invokeConductor(user, payload, session)

    // is caller waiting for the result of the activation?
    result map { result =>
      if (cause.isDefined && !caller.isDefined) {
        // ignore timeout when a sequence invokes a composition
        result.future
      } else {
        // handle timeout
        result.future.withAlternativeAfterTimeout(waitForResponse.head, Future.successful(Left(session.activationId)))
      }
    } getOrElse {
      // no, return the session id
      Future.successful(Left(session.activationId))
    }
  }

  /**
   * A method that knows how to handle a conductor invocation.
   *
   * This method prepares the payload and invokes the conductor action.
   * It parses the result and extracts the name of the next component action if any.
   * It either invokes the desired component action or completes the composition invocation.
   * It also checks the invocation counts against the limits.
   *
   * @param user the identity invoking the action
   * @param payload the dynamic arguments for the activation
   * @param session the session object for this composition
   * @param transid a transaction id for logging
   */
  private def invokeConductor(user: Identity, payload: Option[JsObject], session: Session)(
    implicit transid: TransactionId) = {

    if (session.accounting.conductors > 2 * actionSequenceLimit) {
      // composition is too long
      val response = ActivationResponse.applicationError(compositionIsTooLong)
      completeAppActivation(user, session, response)
    } else {
      // inject state into payload if any
      val params = session.state
        .map { state =>
          Some(JsObject(payload.getOrElse(JsObject()).fields ++ state.fields))
        }
        .getOrElse(payload)

      // invoke conductor action
      session.accounting.conductors += 1
      val response =
        invokeSimpleAction(
          user,
          session.action,
          params,
          Some(session.action.limits.timeout.duration + 1.minute), // wait for result
          Some(session.activationId)) // cause is session id

      response.onComplete {
        case Failure(t) =>
          // invocation failure
          val response = ActivationResponse.whiskError(compositionActivationFailure)
          completeAppActivation(user, session, response)
        case Success(Left(activationId)) =>
          // invocation timeout
          session.logs += activationId
          val response = ActivationResponse.whiskError(compositionActivationTimeout(activationId))
          completeAppActivation(user, session, response)
        case Success(Right(activation)) =>
          // successful invocation
          session.logs += activation.activationId
          session.duration += activation.duration.getOrElse(activation.end.toEpochMilli - activation.start.toEpochMilli)

          val result = activation.resultAsJson

          // extract params from result
          val params = result.getFields(WhiskActivation.paramsField).headOption.map { p =>
            Try(p.asJsObject).getOrElse(JsObject(WhiskActivation.valueField -> p)) // ensure params is a dictionary
          }

          // update session state
          session.state = result.getFields(WhiskActivation.stateField).headOption.flatMap { p =>
            Try(Some(p.asJsObject)).getOrElse(None)
          }

          // extract next action from result and invoke
          result.getFields(WhiskActivation.actionField).headOption match {
            case None =>
              // no next action, end composition execution, return to caller
              val response = ActivationResponse(activation.response.statusCode, Some(params.getOrElse(result)))
              completeAppActivation(user, session, response)
            case Some(next) =>
              Try(next.convertTo[EntityPath]) match {
                case Failure(t) =>
                  // parsing failure
                  val response = ActivationResponse.applicationError(compositionComponentInvalid(next))
                  completeAppActivation(user, session, response)
                case Success(next) =>
                  // resolve and invoke next action
                  val fqn = (if (next.defaultPackage) EntityPath.DEFAULT.addPath(next) else next)
                    .resolveNamespace(user.namespace)
                    .toFullyQualifiedEntityName
                  val resource = Resource(fqn.path, Collection(Collection.ACTIONS), Some(fqn.name.asString))
                  entitlementProvider.check(user, Privilege.ACTIVATE, Set(resource), noThrottle = true).onComplete {
                    case Failure(t) =>
                      // failed entitlement check
                      val response =
                        ActivationResponse.applicationError(compositionComponentNotAccessible(next.toString))
                      completeAppActivation(user, session, response)
                    case Success(_) =>
                      // successful entitlement check
                      WhiskActionMetaData.resolveActionAndMergeParameters(entityStore, fqn).onComplete {
                        case Failure(t) =>
                          // resolution failure
                          val response =
                            ActivationResponse.applicationError(compositionComponentNotFound(next.toString))
                          completeAppActivation(user, session, response)
                        case Success(next) =>
                          if (session.accounting.components >= actionSequenceLimit) {
                            // composition is too long
                            val response = ActivationResponse.applicationError(compositionIsTooLong)
                            completeAppActivation(user, session, response)
                          } else {
                            // successful resolution
                            invokeComponent(user, next, params, session)
                          }
                      }
                  }
              }
          }
      }
    }
  }

  /**
   * A method that knows how to handle a component invocation.
   *
   * This method distinguishes primitive actions, sequences, and compositions.
   * If the component action is a composition, it invokes invokeComposition and returns.
   * invokeComposition takes care of resuming the execution of this composition, when the nested composition finishes.
   * If the component action is not a composition, it is invoked followed by the reinvocation of the conductor action.
   * This method also keeps track of the duration and memory footprint for the composition.
   *
   * @param user the identity invoking the action
   * @param action the component action to invoke
   * @param payload the dynamic arguments for the activation
   * @param session the session object for this composition
   * @param transid a transaction id for logging
   */
  private def invokeComponent(user: Identity, action: WhiskActionMetaData, payload: Option[JsObject], session: Session)(
    implicit transid: TransactionId) {

    val exec = action.toExecutableWhiskAction
    if (action.annotations.get("conductor").isDefined && exec.isDefined) {
      // composition
      invokeComposition(user, exec.head, payload, None, Some(session.activationId), Some(session)) // non-blocking
      // invokeComposition will take care of accounting and continuations
    } else {
      session.accounting.components += 1
      exec
        .map { exec =>
          // not a sequence, not a composition
          invokeSimpleAction(
            user,
            exec,
            payload,
            Some(action.limits.timeout.duration + 1.minute),
            Some(session.activationId))
        }
        .getOrElse {
          // sequence
          val SequenceExecMetaData(components) = action.exec
          invokeSequence(user, action, components, payload, None, Some(session.activationId), false, 0).map(r => r._1)
        }
        .onComplete {
          case Failure(t) =>
            val response = ActivationResponse.whiskError(compositionActivationFailure)
            completeAppActivation(user, session, response)
          case Success(Left(activationId)) =>
            session.logs += activationId
            val response = ActivationResponse.whiskError(compositionActivationTimeout(activationId))
            completeAppActivation(user, session, response)
          case Success(Right(activation)) =>
            session.logs += activation.activationId
            session.duration += activation.duration.getOrElse(
              activation.end.toEpochMilli - activation.start.toEpochMilli)
            activation.annotations.get("limits") map { limitsAnnotation =>
              limitsAnnotation.asJsObject.getFields("memory") match {
                case Seq(JsNumber(memory)) =>
                  session.maxMemory = Math.max(session.maxMemory, memory.toInt)
              }
            }

            // invoke continuation
            invokeConductor(user, Some(activation.resultAsJson), session)
        }
    }
  }

  /**
   * Creates an activation for a composition and writes it back to the datastore.
   * Completes the associated promise if any.
   * Resumes caller if any.
   */
  private def completeAppActivation(user: Identity, session: Session, response: ActivationResponse)(
    implicit transid: TransactionId): Unit = {

    // compute max memory
    val sequenceLimits = Parameters(
      WhiskActivation.limitsAnnotation,
      ActionLimits(session.action.limits.timeout, MemoryLimit(session.maxMemory MB), session.action.limits.logs).toJson)

    // set causedBy if not topmost
    val causedBy = if (session.cause.isDefined) {
      Some(Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE)))
    } else {
      None
    }

    val end = Instant.now(Clock.systemUTC())

    // create the whisk activation
    val activation = WhiskActivation(
      namespace = user.namespace.toPath,
      name = session.action.name,
      user.subject,
      activationId = session.activationId,
      start = session.start,
      end = end,
      cause = session.cause,
      response = response,
      logs = ActivationLogs(session.logs.map(_.asString).toVector),
      version = session.action.version,
      publish = false,
      annotations = Parameters(WhiskActivation.topmostAnnotation, JsBoolean(!session.cause.isDefined)) ++
        Parameters(WhiskActivation.pathAnnotation, JsString(session.action.fullyQualifiedName(false).asString)) ++
        Parameters(WhiskActivation.kindAnnotation, JsString(Exec.SEQUENCE)) ++
        Parameters(WhiskActivation.conductorAnnotation, JsBoolean(true)) ++
        causedBy ++
        sequenceLimits,
      duration = Some(session.duration))

    // complete the promise if any
    session.result.map { _.success(Right(activation)) }

    logging.info(this, s"recording activation '${activation.activationId}'")
    WhiskActivation.put(activationStore, activation)(transid, notifier = None) onComplete {
      case Success(id) => logging.info(this, s"recorded activation")
      case Failure(t) =>
        logging.error(
          this,
          s"failed to record activation ${activation.activationId} with error ${t.getLocalizedMessage}")
    }

    // resume caller if any
    session.caller.map { caller =>
      caller.logs += session.activationId
      caller.duration += session.duration
      caller.maxMemory = Math.max(caller.maxMemory, session.maxMemory)
      invokeConductor(user, Some(activation.resultAsJson), caller)
    }
  }

  /**
   * Waits for a response from the message bus (e.g., Kafka) containing the result of the activation. This is the fast path
   * used for blocking calls where only the result of the activation is needed. This path is called active acknowledgement
   * or active ack.
   *
   * While waiting for the active ack, periodically poll the datastore in case there is a failure in the fast path delivery
   * which could happen if the connection from an invoker to the message bus is disrupted, or if the publishing of the response
   * fails because the message is too large.
   */
  private def waitForActivationResponse(user: Identity,
                                        activationId: ActivationId,
                                        totalWaitTime: FiniteDuration,
                                        activeAckResponse: Future[Either[ActivationId, WhiskActivation]])(
    implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {
    // this is the promise which active ack or db polling will try to complete via:
    // 1. active ack response, or
    // 2. failing active ack (due to active ack timeout), fall over to db polling
    // 3. timeout on db polling => converts activation to non-blocking (returns activation id only)
    // 4. internal error message
    val docid = DocId(WhiskEntity.qualifiedName(user.namespace.toPath, activationId))
    val (promise, finisher) = ActivationFinisher.props({ () =>
      WhiskActivation.get(activationStore, docid)
    })

    logging.info(this, s"action activation will block for result upto $totalWaitTime")

    activeAckResponse map {
      case result @ Right(_) =>
        // activation complete, result is available
        finisher ! ActivationFinisher.Finish(result)

      case _ =>
        // active ack received but it does not carry the response,
        // no result available except by polling the db
        logging.warn(this, "pre-emptively polling db because active ack is missing result")
        finisher ! Scheduler.WorkOnceNow
    }

    // return the promise which is either fulfilled by active ack, polling from the database,
    // or the timeout alternative when the allowed duration expires (i.e., the action took
    // longer than the permitted, per totalWaitTime).
    promise.withAlternativeAfterTimeout(
      totalWaitTime, {
        Future.successful(Left(activationId)).andThen {
          // result no longer interesting; terminate the finisher/shut down db polling if necessary
          case _ => actorSystem.stop(finisher)
        }
      })
  }

  /** Max atomic action count allowed for sequences */
  private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt
}

/** Companion to the ActivationFinisher. */
protected[actions] object ActivationFinisher {
  case class Finish(activation: Right[ActivationId, WhiskActivation])

  private type ActivationLookup = () => Future[WhiskActivation]

  /** Periodically polls the db to cover missing active acks. */
  private val datastorePollPeriodForActivation = 15.seconds

  /**
   * In case of a partial active ack where it is know an activation completed
   * but the result could not be sent over the bus, use this periodicity to poll
   * for a result.
   */
  private val datastorePreemptivePolling = Seq(1.second, 3.seconds, 5.seconds, 7.seconds)

  def props(activationLookup: ActivationLookup)(
    implicit transid: TransactionId,
    actorSystem: ActorSystem,
    executionContext: ExecutionContext,
    logging: Logging): (Future[Either[ActivationId, WhiskActivation]], ActorRef) = {

    val (p, _, f) = props(activationLookup, datastorePollPeriodForActivation, datastorePreemptivePolling)
    (p.future, f) // hides the polling actor
  }

  /**
   * Creates the finishing actor.
   * This is factored for testing.
   */
  protected[actions] def props(activationLookup: ActivationLookup,
                               slowPoll: FiniteDuration,
                               fastPolls: Seq[FiniteDuration])(
    implicit transid: TransactionId,
    actorSystem: ActorSystem,
    executionContext: ExecutionContext,
    logging: Logging): (Promise[Either[ActivationId, WhiskActivation]], ActorRef, ActorRef) = {

    // this is strictly completed by the finishing actor
    val promise = Promise[Either[ActivationId, WhiskActivation]]
    val dbpoller = poller(slowPoll, promise, activationLookup)
    val finisher = Props(new ActivationFinisher(dbpoller, fastPolls, promise))

    (promise, dbpoller, actorSystem.actorOf(finisher))
  }

  /**
   * An actor to complete a blocking activation request. It encapsulates a promise
   * to be completed when the result is ready. This may happen in one of two ways.
   * An active ack message is relayed to this actor to complete the promise when
   * the active ack is received. Or in case of a partial/missing active ack, an
   * explicitly scheduled datastore poll of the activation record, if found, will
   * complete the transaction. When the promise is fulfilled, the actor self destructs.
   */
  private class ActivationFinisher(poller: ActorRef, // the activation poller
                                   fastPollPeriods: Seq[FiniteDuration],
                                   promise: Promise[Either[ActivationId, WhiskActivation]])(
    implicit transid: TransactionId,
    actorSystem: ActorSystem,
    executionContext: ExecutionContext,
    logging: Logging)
      extends Actor {

    // when the future completes, self-destruct
    promise.future.andThen { case _ => shutdown() }

    var preemptiveMsgs = Vector.empty[Cancellable]

    def receive = {
      case ActivationFinisher.Finish(activation) =>
        promise.trySuccess(activation)

      case msg @ Scheduler.WorkOnceNow =>
        // try up to three times when pre-emptying the schedule
        fastPollPeriods.foreach { s =>
          preemptiveMsgs = preemptiveMsgs :+ context.system.scheduler.scheduleOnce(s, poller, msg)
        }
    }

    def shutdown(): Unit = {
      preemptiveMsgs.foreach(_.cancel())
      preemptiveMsgs = Vector.empty
      context.stop(poller)
      context.stop(self)
    }

    override def postStop() = {
      logging.info(this, "finisher shutdown")
      preemptiveMsgs.foreach(_.cancel())
      preemptiveMsgs = Vector.empty
      context.stop(poller)
    }
  }

  /**
   * This creates the inner datastore poller for the completed activation.
   * It is a factory method to facilitate testing.
   */
  private def poller(slowPollPeriod: FiniteDuration,
                     promise: Promise[Either[ActivationId, WhiskActivation]],
                     activationLookup: ActivationLookup)(implicit transid: TransactionId,
                                                         actorSystem: ActorSystem,
                                                         executionContext: ExecutionContext,
                                                         logging: Logging): ActorRef = {
    Scheduler.scheduleWaitAtMost(slowPollPeriod, initialDelay = slowPollPeriod, name = "dbpoll")(() => {
      if (!promise.isCompleted) {
        activationLookup() map {
          // complete the future, which in turn will poison pill this scheduler
          activation =>
            promise.trySuccess(Right(activation.withoutLogs)) // logs excluded on blocking calls
        } andThen {
          case Failure(e: NoDocumentException) => // do nothing, scheduler will reschedule another poll
          case Failure(t: Throwable) => // something went wrong, abort
            logging.error(this, s"failed while waiting on result: ${t.getMessage}")
            promise.tryFailure(t) // complete the future, which in turn will poison pill this scheduler
        }
      } else Future.successful({}) // the scheduler will be halted because the promise is now resolved
    })
  }
}
