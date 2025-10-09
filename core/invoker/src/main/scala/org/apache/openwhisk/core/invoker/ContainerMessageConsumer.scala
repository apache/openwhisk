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

package org.apache.openwhisk.core.invoker

import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.openwhisk.common.{GracefulShutdown, Logging, TransactionId}
import org.apache.openwhisk.core.WarmUp.isWarmUpAction
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.connector.ContainerCreationError.{DBFetchError, InvalidActionLimitError}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.containerpool.v2.{CreationContainer, DeletionContainer}
import org.apache.openwhisk.core.database.{ArtifactStore, DocumentRevisionMismatchException, NoDocumentException}
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.http.Messages

import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class ContainerMessageConsumer(
  invokerInstanceId: InvokerInstanceId,
  containerPool: ActorRef,
  entityStore: ArtifactStore[WhiskEntity],
  config: WhiskConfig,
  msgProvider: MessagingProvider,
  longPollDuration: FiniteDuration,
  maxPeek: Int,
  sendAckToScheduler: (SchedulerInstanceId, ContainerCreationAckMessage) => Future[ResultMetadata])(
  implicit actorSystem: ActorSystem,
  executionContext: ExecutionContext,
  logging: Logging) {

  private val topic = s"${Invoker.topicPrefix}invoker${invokerInstanceId.toInt}"
  private val consumer =
    msgProvider.getConsumer(config, topic, topic, maxPeek, maxPollInterval = TimeLimit.MAX_DURATION + 1.minute)

  private val authStore = WhiskAuthStore.datastore()

  private def handler(bytes: Array[Byte]): Future[Unit] = Future {
    val raw = new String(bytes, StandardCharsets.UTF_8)
    ContainerMessage.parse(raw) match {
      case Success(creation: ContainerCreationMessage) if isWarmUpAction(creation.action) =>
        logging.info(
          this,
          s"container creation message for ${creation.invocationNamespace}/${creation.action} is received (creationId: ${creation.creationId})")
        feed ! MessageFeed.Processed

      case Success(creation: ContainerCreationMessage) =>
        implicit val transid: TransactionId = creation.transid
        logging
          .info(this, s"container creation message for ${creation.invocationNamespace}/${creation.action} is received")

        val createContainer = for {
          identity <- Identity.get(authStore, EntityName(creation.invocationNamespace))
          action <- WhiskAction
            .get(entityStore, creation.action.toDocId, creation.revision, fromCache = true)
        } yield {
          // check action limits before creating container
          action.limits.checkLimits(identity)
          containerPool ! CreationContainer(creation, action)
          feed ! MessageFeed.Processed
        }
        createContainer.recover {
          case t =>
            val creationError = t match {
              case _: ActionLimitsException => InvalidActionLimitError
              case _                        => DBFetchError
            }
            val message = t match {
              case _: ActionLimitsException => t.getMessage // return generated failed message
              case _: NoDocumentException =>
                Messages.actionRemovedWhileInvoking
              case _: DocumentRevisionMismatchException =>
                Messages.actionMismatchWhileInvoking
              case e: Throwable =>
                logging.error(
                  this,
                  s"An unknown DB error occurred while fetching action ${creation.invocationNamespace}/${creation.action} for creation ${creation.creationId}, error: $e.")
                Messages.actionFetchErrorWhileInvoking
            }
            logging.error(
              this,
              s"failed to create a container ${creation.invocationNamespace}/${creation.action}, error: $message (creationId: ${creation.creationId})")

            val ack = ContainerCreationAckMessage(
              creation.transid,
              creation.creationId,
              creation.invocationNamespace,
              creation.action,
              creation.revision,
              creation.whiskActionMetaData,
              invokerInstanceId,
              creation.schedulerHost,
              creation.rpcPort,
              creation.retryCount,
              Some(creationError),
              Some(message))
            sendAckToScheduler(creation.rootSchedulerIndex, ack)
            feed ! MessageFeed.Processed
        }
      case Success(deletion: ContainerDeletionMessage) =>
        implicit val transid: TransactionId = deletion.transid
        logging.info(this, s"deletion message for ${deletion.invocationNamespace}/${deletion.action} is received")
        containerPool ! DeletionContainer(deletion)
        feed ! MessageFeed.Processed
      case Failure(t) =>
        logging.error(this, s"Failed to parse $bytes, error: ${t.getMessage}")
        feed ! MessageFeed.Processed

      case _ =>
        logging.error(this, s"Unexpected message received $raw")
        feed ! MessageFeed.Processed
    }
  }

  private val feed = actorSystem.actorOf(Props {
    new MessageFeed("containerCreation", logging, consumer, maxPeek, longPollDuration, handler)
  })

  def close(): Unit = {
    feed ! GracefulShutdown
  }
}
