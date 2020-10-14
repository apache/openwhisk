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

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.tracing.WhiskTracerProvider
import org.apache.openwhisk.common.{Logging, LoggingMarkers, TransactionId, UserEvents}
import org.apache.openwhisk.core.connector.{ActivationMessage, EventMessage, MessagingProvider}
import org.apache.openwhisk.core.controller.WhiskServices
import org.apache.openwhisk.core.database.{ActivationStore, NoDocumentException, UserContext}
import org.apache.openwhisk.core.entitlement.{Resource, _}
import org.apache.openwhisk.core.entity.ActivationResponse.ERROR_FIELD
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeInt
import org.apache.openwhisk.core.entity.types.EntityStore
import org.apache.openwhisk.http.Messages._
import org.apache.openwhisk.spi.SpiLoader
import org.apache.openwhisk.utils.ExecutionContextFactory.FutureExtensions
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.Interval

import scala.collection.mutable.Buffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}
import pureconfig._
import pureconfig.generic.auto._

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
  protected val activeAckTopicIndex: ControllerInstanceId

  /** Database service to CRUD actions. */
  protected val entityStore: EntityStore

  /** Database service to get activations. */
  protected val activationStore: ActivationStore

  /** Message producer. This is needed to write user-metrics. */
  private val messagingProvider = SpiLoader.get[MessagingProvider]
  private val producer = messagingProvider.getProducer(services.whiskConfig)

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
   * A conductor action is an executable action with a truthy "conductor" annotation.
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

    if (action.annotations.isTruthy(WhiskActivation.conductorAnnotation)) {
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
    val activationId = activationIdFactory.make()

    val startActivation = transid.started(
      this,
      waitForResponse
        .map(_ => LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING)
        .getOrElse(LoggingMarkers.CONTROLLER_ACTIVATION),
      logLevel = InfoLevel)
    val startLoadbalancer =
      transid.started(this, LoggingMarkers.CONTROLLER_LOADBALANCER, s"action activation id: ${activationId}")

    val message = ActivationMessage(
      transid,
      FullyQualifiedEntityName(action.namespace, action.name, Some(action.version), action.binding),
      action.rev,
      user,
      activationId, // activation id created here
      activeAckTopicIndex,
      waitForResponse.isDefined,
      args,
      action.parameters.initParameters,
      action.parameters.lockedParameters(payload.map(_.fields.keySet).getOrElse(Set.empty)),
      cause = cause,
      WhiskTracerProvider.tracer.getTraceContext(transid))

    val postedFuture = loadBalancer.publish(action, message)

    postedFuture andThen {
      case Success(_) => transid.finished(this, startLoadbalancer)
      case Failure(e) => transid.failed(this, startLoadbalancer, e.getMessage)
    } flatMap { activeAckResponse =>
      // is caller waiting for the result of the activation?
      waitForResponse
        .map { timeout =>
          // yes, then wait for the activation response from the message bus
          // (known as the active response or active ack)
          waitForActivationResponse(user, message.activationId, timeout, activeAckResponse)
        }
        .getOrElse {
          // no, return the activation id
          Future.successful(Left(message.activationId))
        }
    } andThen {
      case Success(_) => transid.finished(this, startActivation)
      case Failure(e) => transid.failed(this, startActivation, e.getMessage)
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
   */
  private case class Session(activationId: ActivationId,
                             start: Instant,
                             action: ExecutableWhiskActionMetaData,
                             cause: Option[ActivationId],
                             var duration: Long,
                             var maxMemory: ByteSize,
                             var state: Option[JsObject],
                             accounting: CompositionAccounting,
                             logs: Buffer[ActivationId])

  /**
   * A method that knows how to invoke a composition.
   *
   * The method instantiates the session object for the composition and invokes the conductor action.
   * It waits for the activation response, synthesizes the activation record and writes it to the datastore.
   * It distinguishes nested, blocking and non-blocking invokes, returning either the activation or the activation id.
   *
   * @param user the identity invoking the action
   * @param action the conductor action to invoke for the composition
   * @param payload the dynamic arguments for the activation
   * @param waitForResponse if not empty, wait upto specified duration for a response (this is used for blocking activations)
   * @param cause the activation id that is responsible for this invoke/activation
   * @param accounting the accounting object for the caller if any
   * @param transid a transaction id for logging
   * @return a promise that completes with one of the following successful cases:
   *            Right(WhiskActivation) if waiting for a response and response is ready within allowed duration,
   *            Left(ActivationId) if not waiting for a response, or allowed duration has elapsed without a result ready
   */
  private def invokeComposition(user: Identity,
                                action: ExecutableWhiskActionMetaData,
                                payload: Option[JsObject],
                                waitForResponse: Option[FiniteDuration],
                                cause: Option[ActivationId],
                                accounting: Option[CompositionAccounting] = None)(
    implicit transid: TransactionId): Future[Either[ActivationId, WhiskActivation]] = {

    val session = Session(
      activationId = activationIdFactory.make(),
      start = Instant.now(Clock.systemUTC()),
      action,
      cause,
      duration = 0,
      maxMemory = action.limits.memory.megabytes MB,
      state = None,
      accounting = accounting.getOrElse(CompositionAccounting()), // share accounting with caller
      logs = Buffer.empty)

    logging.info(this, s"invoking composition $action topmost ${cause.isEmpty} activationid '${session.activationId}'")

    val response: Future[Either[ActivationId, WhiskActivation]] =
      invokeConductor(user, payload, session, transid).map(response =>
        Right(completeActivation(user, session, response, waitForResponse.isDefined)))

    // is caller waiting for the result of the activation?
    cause
      .map(_ => response) // ignore waitForResponse when not topmost
      .orElse(
        // blocking invoke, wait until timeout
        waitForResponse.map(response.withAlternativeAfterTimeout(_, Future.successful(Left(session.activationId)))))
      .getOrElse(
        // no, return the session id
        Future.successful(Left(session.activationId)))
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
   * @param parentTid a parent transaction id
   */
  private def invokeConductor(user: Identity,
                              payload: Option[JsObject],
                              session: Session,
                              parentTid: TransactionId): Future[ActivationResponse] = {

    implicit val transid: TransactionId = TransactionId.childOf(parentTid)

    if (session.accounting.conductors > 2 * actionSequenceLimit) {
      // composition is too long
      Future.successful(ActivationResponse.applicationError(compositionIsTooLong))
    } else {
      // inject state into payload if any
      val params = session.state
        .map(state => Some(JsObject(payload.getOrElse(JsObject.empty).fields ++ state.fields)))
        .getOrElse(payload)

      // invoke conductor action
      session.accounting.conductors += 1
      val activationResponse =
        invokeSimpleAction(
          user,
          action = session.action,
          payload = params,
          waitForResponse = Some(session.action.limits.timeout.duration + 1.minute), // wait for result
          cause = Some(session.activationId)) // cause is session id

      waitForActivation(user, session, activationResponse).flatMap {
        case Left(response) => // unsuccessful invocation, return error response
          Future.successful(response)
        case Right(activation) => // successful invocation
          val result = activation.resultAsJson

          // extract params from result, auto boxing result if not a dictionary
          val params = result.fields.get(WhiskActivation.paramsField).map {
            case obj: JsObject => obj
            case value         => JsObject(WhiskActivation.valueField -> value)
          }

          // update session state, auto boxing state if not a dictionary
          session.state = result.fields.get(WhiskActivation.stateField).map {
            case obj: JsObject => obj
            case value         => JsObject(WhiskActivation.stateField -> value)
          }

          // extract next action from result and invoke
          result.fields.get(WhiskActivation.actionField) match {
            case None =>
              // no next action, end composition execution, return to caller
              Future.successful(ActivationResponse(activation.response.statusCode, Some(params.getOrElse(result))))
            case Some(next) =>
              FullyQualifiedEntityName.resolveName(next, user.namespace.name) match {
                case Some(fqn) if session.accounting.components < actionSequenceLimit =>
                  tryInvokeNext(user, fqn, params, session, transid)

                case Some(_) => // composition is too long
                  invokeConductor(
                    user,
                    payload = Some(JsObject(ERROR_FIELD -> JsString(compositionIsTooLong))),
                    session = session,
                    transid)

                case None => // parsing failure
                  invokeConductor(
                    user,
                    payload = Some(JsObject(ERROR_FIELD -> JsString(compositionComponentInvalid(next)))),
                    session = session,
                    transid)

              }
          }
      }
    }

  }

  /**
   * Checks if the user is entitled to invoke the next action and invokes it.
   *
   * @param user the subject
   * @param fqn the name of the action
   * @param params parameters for the action
   * @param session the session for the current activation
   * @return promise for the eventual activation
   */
  private def tryInvokeNext(user: Identity,
                            fqn: FullyQualifiedEntityName,
                            params: Option[JsObject],
                            session: Session,
                            parentTid: TransactionId): Future[ActivationResponse] = {

    implicit val transid: TransactionId = TransactionId.childOf(parentTid)

    val resource = Resource(fqn.path, Collection(Collection.ACTIONS), Some(fqn.name.asString))
    entitlementProvider
      .check(user, Privilege.ACTIVATE, Set(resource), noThrottle = true)
      .flatMap { _ =>
        // successful entitlement check
        WhiskActionMetaData
          .resolveActionAndMergeParameters(entityStore, fqn)
          .flatMap {
            case next =>
              // successful resolution
              invokeComponent(user, action = next, payload = params, session)
          }
          .recoverWith {
            case _ =>
              // resolution failure
              invokeConductor(
                user,
                payload = Some(JsObject(ERROR_FIELD -> JsString(compositionComponentNotFound(fqn.asString)))),
                session = session,
                transid)
          }
      }
      .recoverWith {
        case _ =>
          // failed entitlement check
          invokeConductor(
            user,
            payload = Some(JsObject(ERROR_FIELD -> JsString(compositionComponentNotAccessible(fqn.asString)))),
            session = session,
            transid)
      }
  }

  /**
   * A method that knows how to handle a component invocation.
   *
   * This method distinguishes primitive actions, sequences, and compositions.
   * The conductor action is reinvoked after the successful invocation of the component.
   *
   * @param user the identity invoking the action
   * @param action the component action to invoke
   * @param payload the dynamic arguments for the activation
   * @param session the session object for this composition
   * @param transid a transaction id for logging
   */
  private def invokeComponent(user: Identity, action: WhiskActionMetaData, payload: Option[JsObject], session: Session)(
    implicit transid: TransactionId): Future[ActivationResponse] = {

    val exec = action.toExecutableWhiskAction
    val activationResponse: Future[Either[ActivationId, WhiskActivation]] = exec match {
      case Some(action) if action.annotations.isTruthy(WhiskActivation.conductorAnnotation) => // composition
        // invokeComposition will increase the invocation counts
        invokeComposition(
          user,
          action,
          payload,
          waitForResponse = None, // not topmost, hence blocking, no need for timeout
          cause = Some(session.activationId),
          accounting = Some(session.accounting))
      case Some(action) => // primitive action
        session.accounting.components += 1
        invokeSimpleAction(
          user,
          action,
          payload,
          waitForResponse = Some(action.limits.timeout.duration + 1.minute),
          cause = Some(session.activationId))
      case None => // sequence
        session.accounting.components += 1
        val SequenceExecMetaData(components) = action.exec
        invokeSequence(
          user,
          action,
          components,
          payload,
          waitForOutermostResponse = None,
          cause = Some(session.activationId),
          topmost = false,
          atomicActionsCount = 0).map(r => r._1)
    }

    waitForActivation(user, session, activationResponse).flatMap {
      case Left(response) => // unsuccessful invocation, return error response
        Future.successful(response)
      case Right(activation) => // reinvoke conductor on component result
        invokeConductor(user, payload = Some(activation.resultAsJson), session = session, transid)
    }
  }

  /**
   * Waits for a response from a conductor of component action invocation.
   * Handles internal errors (activation failure or timeout).
   * Logs the activation id and updates the duration and max memory for the session.
   * Returns the activation record if successful, the error response if not.
   *
   * @param user the identity invoking the action
   * @param session the session object for this composition
   * @param activationResponse the future activation to wait on
   * @param transid a transaction id for logging
   */
  private def waitForActivation(user: Identity,
                                session: Session,
                                activationResponse: Future[Either[ActivationId, WhiskActivation]])(
    implicit transid: TransactionId): Future[Either[ActivationResponse, WhiskActivation]] = {

    activationResponse
      .map {
        case Left(activationId) => // invocation timeout
          session.logs += activationId
          Left(ActivationResponse.whiskError(compositionActivationTimeout(activationId)))
        case Right(activation) => // successful invocation
          session.logs += activation.activationId
          // activation.duration should be defined but this is not reflected by the type so be defensive
          // end - start is a sensible default but not the correct value for sequences and compositions
          session.duration += activation.duration.getOrElse(activation.end.toEpochMilli - activation.start.toEpochMilli)
          activation.annotations.get("limits").foreach { limitsAnnotation =>
            limitsAnnotation.asJsObject.getFields("memory") match {
              case Seq(JsNumber(memory)) =>
                session.maxMemory = Math.max(session.maxMemory.toMB.toInt, memory.toInt) MB
            }
          }
          Right(activation)
      }
      .recover { // invocation failure
        case _ => Left(ActivationResponse.whiskError(compositionActivationFailure))
      }
  }

  /**
   * Creates an activation for a composition and writes it back to the datastore.
   * Returns the activation.
   */
  private def completeActivation(user: Identity,
                                 session: Session,
                                 response: ActivationResponse,
                                 blockingComposition: Boolean)(implicit transid: TransactionId): WhiskActivation = {

    val context = UserContext(user)

    // compute max memory
    val sequenceLimits = Parameters(
      WhiskActivation.limitsAnnotation,
      ActionLimits(session.action.limits.timeout, MemoryLimit(session.maxMemory), session.action.limits.logs).toJson)

    // set causedBy if not topmost
    val causedBy = session.cause.map { _ =>
      Parameters(WhiskActivation.causedByAnnotation, JsString(Exec.SEQUENCE))
    }

    // set waitTime for conductor action
    val waitTime = {
      Parameters(
        WhiskActivation.waitTimeAnnotation,
        Interval(transid.meta.start, session.start).duration.toMillis.toJson)
    }

    // set binding if invoked action is in a package binding
    val binding =
      session.action.binding.map(f => Parameters(WhiskActivation.bindingAnnotation, JsString(f.asString)))

    val end = Instant.now(Clock.systemUTC())

    // create the whisk activation
    val activation = WhiskActivation(
      namespace = user.namespace.name.toPath,
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
      annotations = Parameters(WhiskActivation.topmostAnnotation, JsBoolean(session.cause.isEmpty)) ++
        Parameters(WhiskActivation.pathAnnotation, JsString(session.action.fullyQualifiedName(false).asString)) ++
        Parameters(WhiskActivation.kindAnnotation, JsString(Exec.SEQUENCE)) ++
        Parameters(WhiskActivation.conductorAnnotation, JsTrue) ++
        causedBy ++ waitTime ++ binding ++
        sequenceLimits,
      duration = Some(session.duration))

    if (UserEvents.enabled) {
      EventMessage.from(activation, s"controller${activeAckTopicIndex.asString}", user.namespace.uuid) match {
        case Success(msg) => UserEvents.send(producer, msg)
        case Failure(t)   => logging.warn(this, s"activation event was not sent: $t")
      }
    }

    activationStore.storeAfterCheck(activation, blockingComposition, None, context)(transid, notifier = None, logging)

    activation
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
    val context = UserContext(user)
    val result = Promise[Either[ActivationId, WhiskActivation]]
    val docid = new DocId(WhiskEntity.qualifiedName(user.namespace.name.toPath, activationId))
    logging.debug(this, s"action activation will block for result upto $totalWaitTime")

    // 1. Wait for the active-ack to happen. Either immediately resolve the promise or poll the database quickly
    //    in case of an incomplete active-ack (record too large for example).
    activeAckResponse.foreach {
      case Right(activation) => result.trySuccess(Right(activation))
      case _ if (controllerActivationConfig.pollingFromDb) =>
        pollActivation(docid, context, result, i => 1.seconds + (2.seconds * i), maxRetries = 4)
      case Left(activationId) =>
        result.trySuccess(Left(activationId)) // complete the future immediately if it's configured to not poll db for blocking activations
    }

    if (controllerActivationConfig.pollingFromDb) {
      // 2. Poll the database slowly in case the active-ack never arrives
      pollActivation(docid, context, result, _ => 15.seconds)
    }

    // 3. Timeout forces a fallback to activationId
    val timeout = actorSystem.scheduler.scheduleOnce(totalWaitTime)(result.trySuccess(Left(activationId)))

    result.future.andThen {
      case _ => timeout.cancel()
    }
  }

  /**
   * Polls the database for an activation.
   *
   * Does not use Future composition because an early exit is wanted, once any possible external source resolved the
   * Promise.
   *
   * @param docid the docid to poll for
   * @param result promise to resolve on result. Is also used to abort polling once completed.
   */
  private def pollActivation(docid: DocId,
                             context: UserContext,
                             result: Promise[Either[ActivationId, WhiskActivation]],
                             wait: Int => FiniteDuration,
                             retries: Int = 0,
                             maxRetries: Int = Int.MaxValue)(implicit transid: TransactionId): Unit = {
    if (!result.isCompleted && retries < maxRetries) {
      val schedule = actorSystem.scheduler.scheduleOnce(wait(retries)) {
        activationStore.get(ActivationId(docid.asString), context).onComplete {
          case Success(activation) =>
            transid.mark(
              this,
              LoggingMarkers.CONTROLLER_ACTIVATION_BLOCKING_DATABASE_RETRIEVAL,
              s"retrieved activation for blocking invocation via DB polling",
              logLevel = InfoLevel)
            result.trySuccess(Right(activation.withoutLogs))
          case Failure(_: NoDocumentException) => pollActivation(docid, context, result, wait, retries + 1, maxRetries)
          case Failure(t: Throwable)           => result.tryFailure(t)
        }
      }

      // Halt the schedule if the result is provided during one execution
      result.future.onComplete(_ => schedule.cancel())
    }
  }

  /** Max atomic action count allowed for sequences */
  private lazy val actionSequenceLimit = whiskConfig.actionSequenceLimit.toInt

  protected val controllerActivationConfig =
    loadConfigOrThrow[ControllerActivationConfig](ConfigKeys.controllerActivation)

}

case class ControllerActivationConfig(pollingFromDb: Boolean, maxWaitForBlockingActivation: FiniteDuration)
