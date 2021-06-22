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

package org.apache.openwhisk.core.containerpool.v2

import akka.actor.Status.{Failure => FailureMessage}
import akka.actor.{ActorRef, ActorSystem, FSM, Props, Stash}
import akka.grpc.internal.ClientClosedException
import akka.pattern.pipe
import io.grpc.StatusRuntimeException
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool.ContainerId
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.scheduler.SchedulerEndpoints
import org.apache.openwhisk.core.scheduler.grpc.ActivationResponse
import org.apache.openwhisk.core.scheduler.queue.{ActionMismatch, MemoryQueueError, NoActivationMessage, NoMemoryQueue}
import org.apache.openwhisk.grpc.{ActivationServiceClient, FetchRequest, RescheduleRequest, RescheduleResponse}
import spray.json.JsonParser.ParsingException

import scala.concurrent.Future
import scala.util.{Success, Try}

// Event send by the actor
case class ClientCreationCompleted(client: Option[ActorRef] = None)
case object ClientClosed

// Event received by the actor
case object StartClient
case class RequestActivation(lastDuration: Option[Long] = None, newScheduler: Option[SchedulerEndpoints] = None)
case class RescheduleActivation(invocationNamespace: String,
                                fqn: FullyQualifiedEntityName,
                                rev: DocRevision,
                                msg: ActivationMessage)
case object RetryRequestActivation
case object ContainerWarmed
case object CloseClientProxy
case object StopClientProxy

// state
sealed trait ActivationClientProxyState
case object ClientProxyUninitialized extends ActivationClientProxyState
case object ClientProxyReady extends ActivationClientProxyState
case object ClientProxyRemoving extends ActivationClientProxyState

// data
sealed trait ActivationClientProxyData
case class Client(activationClient: ActivationServiceClient, rpcHost: String, rpcPort: Int)
    extends ActivationClientProxyData
case class Retry(count: Int) extends ActivationClientProxyData

class ActivationClientProxy(
  invocationNamespace: String,
  action: FullyQualifiedEntityName,
  rev: DocRevision,
  schedulerHost: String,
  rpcPort: Int,
  containerId: ContainerId,
  activationClientFactory: (String, FullyQualifiedEntityName, String, Int, Boolean) => Future[ActivationServiceClient])(
  implicit actorSystem: ActorSystem,
  logging: Logging)
    extends FSM[ActivationClientProxyState, ActivationClientProxyData]
    with Stash {

  implicit val ec = actorSystem.dispatcher

  private var warmed = false

  startWith(ClientProxyUninitialized, Retry(3))

  when(ClientProxyUninitialized) {
    case Event(StartClient, r: Retry) =>
      // build activation client using original scheduler endpoint firstly
      createActivationClient(invocationNamespace, action, schedulerHost, rpcPort, tryOtherScheduler = false)
        .pipeTo(self)

      stay using r

    case Event(client: ActivationClient, _) =>
      context.parent ! ClientCreationCompleted()

      goto(ClientProxyReady) using Client(client.client, client.rpcHost, client.rpcPort)

    case Event(f: FailureMessage, _) =>
      logging.error(this, s"failed to create grpc client for ${action} caused by: $f")
      self ! ClientClosed

      goto(ClientProxyRemoving)

    case _ => delay
  }

  when(ClientProxyReady) {
    case Event(request: RequestActivation, client: Client) =>
      request.newScheduler match {
        // if scheduler is changed, client needs to be recreated
        case Some(scheduler) if scheduler.host != client.rpcHost || scheduler.rpcPort != client.rpcPort =>
          val newHost = request.newScheduler.get.host
          val newPort = request.newScheduler.get.rpcPort
          client.activationClient
            .close()
            .flatMap(_ =>
              createActivationClient(invocationNamespace, action, newHost, newPort, tryOtherScheduler = false))
            .pipeTo(self)

        case _ =>
          requestActivationMessage(invocationNamespace, action, rev, client.activationClient, request.lastDuration)
            .pipeTo(self)
      }
      stay()

    case Event(e: RescheduleActivation, client: Client) =>
      logging.info(this, s"got a reschedule message ${e.msg.activationId} for action: ${e.msg.action}")
      client.activationClient
        .rescheduleActivation(
          RescheduleRequest(e.invocationNamespace, e.fqn.serialize, e.rev.serialize, e.msg.serialize))
        .recover {
          case t =>
            logging.error(this, s"Failed to reschedule activation (error: $t)")
            Future.successful(RescheduleResponse())
        }
        .foreach(res => {
          context.parent ! res
        })
      stay()

    case Event(msg: ActivationMessage, _: Client) =>
      logging.debug(this, s"got a message ${msg.activationId} for action: ${msg.action}")
      context.parent ! msg

      stay()

    /**
     * Case of scheduler error
     */
    case Event(error: MemoryQueueError, c: Client) =>
      error match {
        case _: NoMemoryQueue =>
          logging.error(
            this,
            s"The queue of action ${action} under invocationNamespace ${invocationNamespace} does not exist. Check for queues in other schedulers.")
          c.activationClient
            .close()
            .flatMap(_ =>
              createActivationClient(invocationNamespace, action, c.rpcHost, c.rpcPort, tryOtherScheduler = true))
            .pipeTo(self)

          stay()

        case _: ActionMismatch =>
          logging.error(this, s"action version does not match: $action")
          c.activationClient.close().andThen {
            case _ => self ! ClientClosed
          }

          goto(ClientProxyRemoving)

        case _: NoActivationMessage => // retry
          logging.debug(this, s"no activation message exist: $action")
          context.parent ! RetryRequestActivation

          stay()
      }

    /**
     * Case of system error like grpc, parsing message
     */
    case Event(f: FailureMessage, c: Client) =>
      f.cause match {
        case t: ParsingException =>
          logging.error(this, s"failed to parse activation message: $t")
          context.parent ! RetryRequestActivation

          stay()

        // When scheduler pod recreated, the StatusRuntimeException with `Unable to resolve host` would happen.
        // In such situation, it is better to stop the activationClientProxy, otherwise, in short time,
        // it would print huge log due to create another grpcClient to fetch activation again.
        case t: StatusRuntimeException if t.getMessage.contains(ActivationClientProxy.hostResolveError) =>
          logging.error(this, s"akka grpc server connection failed: $t")
          self ! ClientClosed

          goto(ClientProxyRemoving)

        case t: StatusRuntimeException =>
          logging.error(this, s"akka grpc server connection failed: $t")
          c.activationClient
            .close()
            .flatMap(_ =>
              createActivationClient(invocationNamespace, action, c.rpcHost, c.rpcPort, tryOtherScheduler = true))
            .pipeTo(self)

          stay()

        case _: ClientClosedException =>
          logging.error(this, s"grpc client is already closed for $action")
          self ! ClientClosed

          goto(ClientProxyRemoving)

        case t: Throwable =>
          logging.error(this, s"get activation from remote server error: $t")
          safelyCloseClient(c)
          goto(ClientProxyRemoving)
      }

    case Event(client: ActivationClient, _) =>
      // long poll
      requestActivationMessage(invocationNamespace, action, rev, client.client)
        .pipeTo(self)

      stay using Client(client.client, client.rpcHost, client.rpcPort)
  }

  when(ClientProxyRemoving) {
    case Event(request: RequestActivation, client: Client) =>
      request.newScheduler match {
        // if scheduler is changed, client needs to be recreated
        case Some(scheduler) if scheduler.host != client.rpcHost || scheduler.rpcPort != client.rpcPort =>
          val newHost = request.newScheduler.get.host
          val newPort = request.newScheduler.get.rpcPort
          client.activationClient
            .close()
            .flatMap(_ =>
              createActivationClient(invocationNamespace, action, newHost, newPort, tryOtherScheduler = false))
            .pipeTo(self)

        case _ =>
          requestActivationMessage(invocationNamespace, action, rev, client.activationClient, request.lastDuration)
            .pipeTo(self)
      }
      stay()

    case Event(msg: ActivationMessage, _: Client) =>
      context.parent ! msg

      stay()

    case Event(_: MemoryQueueError, _: Client) =>
      self ! ClientClosed

      stay()

    case Event(f: FailureMessage, c: Client) =>
      logging.error(this, s"some error happened for action: ${action} in state: $stateName, caused by: $f")
      safelyCloseClient(c)
      stay()

    case Event(client: ActivationClient, _) =>
      // long poll
      requestActivationMessage(invocationNamespace, action, rev, client.client)
        .pipeTo(self)

      stay using Client(client.client, client.rpcHost, client.rpcPort)
  }

  // Unstash all messages stashed while in intermediate state
  onTransition {
    case _ -> ClientProxyReady    => unstashAll()
    case _ -> ClientProxyRemoving => unstashAll()
  }

  whenUnhandled {
    case Event(ContainerWarmed, _) =>
      warmed = true
      stay

    case Event(CloseClientProxy, c: Client) =>
      safelyCloseClient(c)
      goto(ClientProxyRemoving)

    case Event(ClientClosed, _) =>
      context.parent ! ClientClosed

      stop()

    case Event(StopClientProxy, c: Client) =>
      safelyCloseClient(c)
      stay()
  }

  initialize()

  /** Delays all incoming messages until unstashAll() is called */
  def delay = {
    stash()
    stay
  }

  /**
   * Safely shut down the client.
   */
  private def safelyCloseClient(client: Client): Unit = {
    Try {
      client.activationClient
        .fetchActivation(
          FetchRequest(
            TransactionId(TransactionId.generateTid()).serialize,
            invocationNamespace,
            action.serialize,
            rev.serialize,
            containerId.asString,
            warmed,
            None,
            false))
        .andThen {
          case _ =>
            client.activationClient.close().andThen {
              case _ => self ! ClientClosed
            }
        }
    }.recover {
      // If the fetchActivation is executed when the client is closed, the andThen statement is not executed.
      case _: ClientClosedException =>
        self ! ClientClosed
    }
  }

  /**
   * Request activation message to scheduler by long poll
   *
   * @return ActivationMessage or MemoryQueueError
   */
  private def requestActivationMessage(invocationNamespace: String,
                                       fqn: FullyQualifiedEntityName,
                                       rev: DocRevision,
                                       client: ActivationServiceClient,
                                       lastDuration: Option[Long] = None) = {
    Try {
      client
        .fetchActivation(
          FetchRequest(
            TransactionId(TransactionId.generateTid()).serialize,
            invocationNamespace,
            fqn.serialize,
            rev.serialize,
            containerId.asString,
            warmed,
            lastDuration,
            true))
        .flatMap { r =>
          Future(ActivationResponse.parse(r.activationMessage))
            .flatMap(Future.fromTry)
            .flatMap {
              case ActivationResponse(Right(msg)) =>
                Future.successful(msg)
              case ActivationResponse(Left(msg)) =>
                Future.successful(msg)
            }
        }
    }.recover {
        case _: ClientClosedException =>
          logging.debug(this, s"grpc client is closed for $fqn in the Try closure")
          Future.successful(ClientClosed)
      }
      .getOrElse(Future.failed(new Exception(s"error to get $fqn activation from grpc server")))
  }

  private def createActivationClient(invocationNamespace: String,
                                     fqn: FullyQualifiedEntityName,
                                     schedulerHost: String,
                                     rpcPort: Int,
                                     tryOtherScheduler: Boolean,
                                     retry: Int = 5): Future[ActivationClient] = {
    activationClientFactory(invocationNamespace, fqn, schedulerHost, rpcPort, tryOtherScheduler)
      .map { client =>
        ActivationClient(client, schedulerHost, rpcPort)
      }
      .andThen {
        case Success(_) => logging.debug(this, "The gRPC client created successfully")
      }
      .recoverWith {
        case _: Throwable =>
          if (retry < 5)
            createActivationClient(invocationNamespace, action, schedulerHost, rpcPort, tryOtherScheduler, retry - 1)
          else {
            Future.failed(new Exception("The number of client creation retries has been exceeded."))
          }
      }
  }
}

object ActivationClientProxy {

  val hostResolveError = "Unable to resolve host"

  def props(invocationNamespace: String,
            action: FullyQualifiedEntityName,
            rev: DocRevision,
            schedulerHost: String,
            rpcPort: Int,
            containerId: ContainerId,
            activationClientFactory: (
              String,
              FullyQualifiedEntityName,
              String,
              Int,
              Boolean) => Future[ActivationServiceClient])(implicit actorSystem: ActorSystem, logging: Logging) = {
    Props(
      new ActivationClientProxy(
        invocationNamespace,
        action,
        rev,
        schedulerHost,
        rpcPort,
        containerId,
        activationClientFactory))
  }
}

case class ActivationClient(client: ActivationServiceClient, rpcHost: String, rpcPort: Int)
