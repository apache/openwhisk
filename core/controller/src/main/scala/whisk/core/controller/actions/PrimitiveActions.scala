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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure

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
import whisk.core.entity._
import whisk.core.entity.types.ActivationStore
import whisk.core.entity.types.EntityStore
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

  /**
   * Posts request to the loadbalancer. If the loadbalancer accepts the requests with an activation id,
   * then wait for the result of the activation if necessary.
   *
   * NOTE:
   * For activations of actions, cause is populated only for actions that were invoked as a result of a sequence activation.
   * For actions that are enclosed in a sequence and are activated as a result of the sequence activation, the cause
   * contains the activation id of the immediately enclosing sequence.
   * e.g.,: s -> a, x, c    and   x -> c  (x and s are sequences, a, b, c atomic actions)
   * cause for a, x, c is the activation id of s
   * cause for c is the activation id of x
   * cause for s is not defined
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
  protected[actions] def invokeSingleAction(
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
      activationNamespace = user.namespace.toPath,
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
