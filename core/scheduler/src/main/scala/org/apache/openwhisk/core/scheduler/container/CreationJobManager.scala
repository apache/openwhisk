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

package org.apache.openwhisk.core.scheduler.container

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Cancellable, Props}
import org.apache.openwhisk.common.{GracefulShutdown, Logging}
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.etcd.EtcdKV.ContainerKeys.inProgressContainer
import org.apache.openwhisk.core.service.{RegisterData, UnregisterData}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.scheduler.message.{
  CreationJobState,
  FailedCreationJob,
  FinishCreationJob,
  RegisterCreationJob,
  ReschedulingCreationJob,
  SuccessfulCreationJob
}
import org.apache.openwhisk.core.scheduler.queue.{MemoryQueueKey, QueuePool}
import pureconfig.loadConfigOrThrow

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case object GetPoolStatus

case class JobEntry(action: FullyQualifiedEntityName, timer: Cancellable)

class CreationJobManager(feedFactory: (ActorRefFactory, String, Int, Array[Byte] => Future[Unit]) => ActorRef,
                         schedulerInstanceId: SchedulerInstanceId,
                         dataManagementService: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging)
    extends Actor {
  private implicit val ec: ExecutionContext = actorSystem.dispatcher
  private val baseTimeout = loadConfigOrThrow[FiniteDuration](ConfigKeys.schedulerInProgressJobRetentionSecond)
  private val retryLimit = 5
  private val retryDelayTime = 100.milliseconds

  /**
   * Store a JobEntry in local to get an alarm for key timeout
   * It does not matter whether the information stored in Local is redundant or null.
   * When a new JobEntry is created, it is overwritten if it is duplicated.
   * If there is no corresponding JobEntry at the time of deletion, nothing is done.
   */
  protected val creationJobPool = TrieMap[CreationId, JobEntry]()

  override def receive: Receive = {
    case RegisterCreationJob(
        ContainerCreationMessage(_, invocationNamespace, action, revision, actionMetaData, _, _, _, _, creationId)) =>
      val isBlackboxInvocation = actionMetaData.toExecutableWhiskAction.exists(a => a.exec.pull)
      registerJob(invocationNamespace, action, revision, creationId, isBlackboxInvocation)

    case FinishCreationJob(
        ContainerCreationAckMessage(
          tid,
          creationId,
          invocationNamespace,
          action,
          revision,
          actionMetaData,
          _,
          schedulerHost,
          rpcPort,
          retryCount,
          error,
          reason)) =>
      if (error.isEmpty) {
        logging.info(this, s"[$creationId] create container successfully")
        deleteJob(
          invocationNamespace,
          action,
          revision,
          creationId,
          SuccessfulCreationJob(creationId, invocationNamespace, action, revision))

      } else {
        val cause = reason.getOrElse("unknown reason")
        // if exceed the retry limit or meet errors which we don't need to reschedule, make it a failure
        if (retryCount >= retryLimit || !error.exists(ContainerCreationError.whiskErrors.contains)) {
          logging.error(
            this,
            s"[$creationId] Failed to create container $retryCount/$retryLimit times for $cause. Finished creation")
          // Delete from pool after all retries are failed
          deleteJob(
            invocationNamespace,
            action,
            revision,
            creationId,
            FailedCreationJob(creationId, invocationNamespace, action, revision, error.get, cause))
        } else {
          // Reschedule
          logging.error(
            this,
            s"[$creationId] Failed to create container $retryCount/$retryLimit times for $cause. Started rescheduling")
          // Add some time interval during retry create container, because etcd put operation needs some time if data inconsistant happens
          actorSystem.scheduler.scheduleOnce(retryDelayTime) {
            context.parent ! ReschedulingCreationJob(
              tid,
              creationId,
              invocationNamespace,
              action,
              revision,
              actionMetaData,
              schedulerHost,
              rpcPort,
              retryCount)
          }
        }
      }

    case GracefulShutdown =>
      ackFeed ! GracefulShutdown
  }

  private def registerJob(invocationNamespace: String,
                          action: FullyQualifiedEntityName,
                          revision: DocRevision,
                          creationId: CreationId,
                          isBlackboxInvocation: Boolean) = {
    creationJobPool getOrElseUpdate (creationId, {
      val key = inProgressContainer(invocationNamespace, action, revision, schedulerInstanceId, creationId)
      dataManagementService ! RegisterData(key, "", failoverEnabled = false)
      JobEntry(action, createTimer(invocationNamespace, action, revision, creationId, isBlackboxInvocation))
    })
  }

  private def deleteJob(invocationNamespace: String,
                        action: FullyQualifiedEntityName,
                        revision: DocRevision,
                        creationId: CreationId,
                        state: CreationJobState) = {
    val key = inProgressContainer(invocationNamespace, action, revision, schedulerInstanceId, creationId)

    // If there is a JobEntry, delete it.
    creationJobPool
      .remove(creationId)
      .foreach(entry => {
        sendState(state)
        entry.timer.cancel()
      })

    dataManagementService ! UnregisterData(key)
    Future.successful({})
  }

  private def sendState(state: CreationJobState): Unit = {
    context.parent ! state // send state to ContainerManager
    QueuePool.get(MemoryQueueKey(state.invocationNamespace, state.action.toDocId.asDocInfo(state.revision))) match {
      case Some(memoryQueueValue) if memoryQueueValue.isLeader =>
        memoryQueueValue.queue ! state
      case _ =>
        logging.error(this, s"get a $state for a nonexistent memory queue or a follower")
    }
  }

  protected def createTimer(invocationNamespace: String,
                            action: FullyQualifiedEntityName,
                            revision: DocRevision,
                            creationId: CreationId,
                            isBlackbox: Boolean): Cancellable = {
    val timeout = if (isBlackbox) FiniteDuration(baseTimeout.toSeconds * 3, TimeUnit.SECONDS) else baseTimeout
    actorSystem.scheduler.scheduleOnce(timeout) {
      logging.warn(
        this,
        s"Failed to create a container for $action(blackbox: $isBlackbox), error: $creationId timed out after $timeout")
      creationJobPool
        .remove(creationId)
        .foreach(
          _ =>
            sendState(
              FailedCreationJob(
                creationId,
                invocationNamespace,
                action,
                revision,
                ContainerCreationError.TimeoutError,
                s"timeout waiting for the ack of $creationId after $timeout")))
      dataManagementService ! UnregisterData(
        inProgressContainer(invocationNamespace, action, revision, schedulerInstanceId, creationId))
    }
  }

  private val topicPrefix = loadConfigOrThrow[String](ConfigKeys.kafkaTopicsPrefix)
  private val topic = s"${topicPrefix}creationAck${schedulerInstanceId.asString}"
  private val maxActiveAcksPerPoll = 128
  private val ackFeed = feedFactory(actorSystem, topic, maxActiveAcksPerPoll, processAcknowledgement)

  def processAcknowledgement(bytes: Array[Byte]): Future[Unit] = {
    Future(ContainerCreationAckMessage.parse(new String(bytes, StandardCharsets.UTF_8)))
      .flatMap(Future.fromTry)
      .flatMap { msg =>
        // forward msg to job manager
        self ! FinishCreationJob(msg)
        ackFeed ! MessageFeed.Processed
        Future.successful(())
      }
      .recoverWith {
        case t =>
          // Iff everything above failed, we have a terminal error at hand. Either the message failed
          // to deserialize, or something threw an error where it is not expected to throw.
          ackFeed ! MessageFeed.Processed
          logging.error(this, s"terminal failure while processing container creation ack message: $t")
          Future.successful(())
      }
  }
}

object CreationJobManager {
  def props(feedFactory: (ActorRefFactory, String, Int, Array[Byte] => Future[Unit]) => ActorRef,
            schedulerInstanceId: SchedulerInstanceId,
            dataManagementService: ActorRef)(implicit actorSystem: ActorSystem, logging: Logging) =
    Props(new CreationJobManager(feedFactory, schedulerInstanceId, dataManagementService))
}
