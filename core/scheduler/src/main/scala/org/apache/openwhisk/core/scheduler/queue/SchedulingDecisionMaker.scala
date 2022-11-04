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

package org.apache.openwhisk.core.scheduler.queue

import akka.actor.{Actor, ActorSystem, Props}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.FullyQualifiedEntityName
import org.apache.openwhisk.core.scheduler.SchedulingConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SchedulingDecisionMaker(
  invocationNamespace: String,
  action: FullyQualifiedEntityName,
  schedulingConfig: SchedulingConfig)(implicit val actorSystem: ActorSystem, ec: ExecutionContext, logging: Logging)
    extends Actor {

  private val staleThreshold: Double = schedulingConfig.staleThreshold.toMillis.toDouble

  override def receive: Receive = {
    case msg: QueueSnapshot =>
      decide(msg)
        .andThen {
          case Success(DecisionResults(Skip, _)) =>
          // do nothing
          case Success(result: DecisionResults) =>
            msg.recipient ! result
          case Failure(e) =>
            logging.error(this, s"failed to make a scheduling decision due to $e");
        }
  }

  private[queue] def decide(snapshot: QueueSnapshot) = {
    val QueueSnapshot(
      initialized,
      incoming,
      currentMsg,
      existing,
      inProgress,
      staleActivationNum,
      existingContainerCountInNs,
      inProgressContainerCountInNs,
      averageDuration,
      limit,
      stateName,
      _) = snapshot
    val totalContainers = existing + inProgress
    val availableMsg = currentMsg + incoming.get()

    if (limit <= 0) {
      // this is an error case, the limit should be bigger than 0
      stateName match {
        case Flushing => Future.successful(DecisionResults(Skip, 0))
        case _        => Future.successful(DecisionResults(Pausing, 0))
      }
    } else {
      val capacity = if (schedulingConfig.allowOverProvisionBeforeThrottle && totalContainers == 0) {
        // if space available within the over provision ratio amount above namespace limit, create one container for new
        // action so namespace traffic can attempt to re-balance without blocking entire action
        if ((ceiling(limit * schedulingConfig.namespaceOverProvisionBeforeThrottleRatio) - existingContainerCountInNs - inProgressContainerCountInNs) > 0) {
          1
        } else {
          0
        }
      } else {
        limit - existingContainerCountInNs - inProgressContainerCountInNs
      }
      if (capacity <= 0) {
        stateName match {

          /**
           * If the container is created later (for any reason), all activations fail(too many requests).
           *
           * However, if the container exists(totalContainers != 0), the activation is not treated as a failure and the activation is delivered to the container.
           */
          case Running
              if !schedulingConfig.allowOverProvisionBeforeThrottle || (schedulingConfig.allowOverProvisionBeforeThrottle && ceiling(
                limit * schedulingConfig.namespaceOverProvisionBeforeThrottleRatio) - existingContainerCountInNs - inProgressContainerCountInNs <= 0) =>
            logging.info(
              this,
              s"there is no capacity activations will be dropped or throttled, (availableMsg: $availableMsg totalContainers: $totalContainers, limit: $limit, namespaceContainers: ${existingContainerCountInNs}, namespaceInProgressContainer: ${inProgressContainerCountInNs}) [$invocationNamespace:$action]")
            Future.successful(DecisionResults(EnableNamespaceThrottling(dropMsg = totalContainers == 0), 0))
          case NamespaceThrottled
              if schedulingConfig.allowOverProvisionBeforeThrottle && ceiling(
                limit * schedulingConfig.namespaceOverProvisionBeforeThrottleRatio) - existingContainerCountInNs - inProgressContainerCountInNs > 0 =>
            Future.successful(DecisionResults(DisableNamespaceThrottling, 0))
          // do nothing
          case _ =>
            // no need to print any messages if the state is already NamespaceThrottled
            Future.successful(DecisionResults(Skip, 0))
        }
      } else {
        (stateName, averageDuration) match {
          // there is no container
          case (Running, None) if totalContainers == 0 && !initialized =>
            logging.info(
              this,
              s"add one initial container if totalContainers($totalContainers) == 0 [$invocationNamespace:$action]")
            Future.successful(DecisionResults(AddInitialContainer, 1))

          // Todo: when disabling throttling we may create some containers.
          case (NamespaceThrottled, _) =>
            Future.successful(DecisionResults(DisableNamespaceThrottling, 0))

          // this is an exceptional case, create a container immediately
          case (Running, _) if totalContainers == 0 && availableMsg > 0 =>
            logging.info(
              this,
              s"add one container if totalContainers($totalContainers) == 0 && availableMsg($availableMsg) > 0 [$invocationNamespace:$action]")
            Future.successful(DecisionResults(AddContainer, 1))

          case (Flushing, _) if totalContainers == 0 =>
            logging.info(
              this,
              s"add one container case Paused if totalContainers($totalContainers) == 0 [$invocationNamespace:$action]")
            // it is highly likely the queue could not create an initial container if the limit is 0
            Future.successful(DecisionResults(AddInitialContainer, 1))

          // there is no activation result yet, but some activations became stale
          // it may cause some over-provisioning if it takes much time to create a container and execution time is short.
          // but it is a kind of trade-off and we place latency on top of over-provisioning
          case (Running, None) if staleActivationNum > 0 =>
            // we can safely get the value as we already checked the existence
            val num = staleActivationNum - inProgress
            // if it tries to create more containers than existing messages, we just create shortage
            val actualNum = if (num > availableMsg) availableMsg else num
            addServersIfPossible(
              existing,
              inProgress,
              0,
              availableMsg,
              capacity,
              actualNum,
              staleActivationNum,
              0.0,
              Running)
          // need more containers and a message is already processed
          case (Running, Some(duration)) =>
            // we can safely get the value as we already checked the existence
            val containerThroughput = staleThreshold / duration
            val expectedTps = containerThroughput * (existing + inProgress)
            val availableNonStaleActivations = availableMsg - staleActivationNum

            var staleContainerProvision = 0
            if (staleActivationNum > 0) {
              val num = ceiling(staleActivationNum.toDouble / containerThroughput)
              // if it tries to create more containers than existing messages, we just create shortage
              staleContainerProvision = (if (num > staleActivationNum) staleActivationNum else num) - inProgress
            }

            if (availableNonStaleActivations >= expectedTps && existing + inProgress < availableNonStaleActivations) {
              val num = ceiling((availableNonStaleActivations / containerThroughput) - existing - inProgress)
              // if it tries to create more containers than existing messages, we just create shortage
              val actualNum =
                if (num + totalContainers > availableNonStaleActivations) availableNonStaleActivations - totalContainers
                else num
              addServersIfPossible(
                existing,
                inProgress,
                containerThroughput,
                availableMsg,
                capacity,
                actualNum + staleContainerProvision,
                staleActivationNum,
                duration,
                Running)
            } else if (staleContainerProvision > 0) {
              addServersIfPossible(
                existing,
                inProgress,
                containerThroughput,
                availableMsg,
                capacity,
                staleContainerProvision,
                staleActivationNum,
                duration,
                Running)
            } else {
              Future.successful(DecisionResults(Skip, 0))
            }

          // generally we assume there are enough containers for actions when shutting down the scheduler
          // but if there were already too many activation in the queue with not enough containers,
          // we should add more containers to quickly consume those messages.
          // this case is for that as a last resort.
          case (Removing, Some(duration)) if staleActivationNum > 0 =>
            // we can safely get the value as we already checked the existence
            val containerThroughput = staleThreshold / duration
            val num = ceiling(staleActivationNum.toDouble / containerThroughput)
            // if it tries to create more containers than existing messages, we just create shortage
            val actualNum = (if (num > staleActivationNum) staleActivationNum else num) - inProgress
            addServersIfPossible(
              existing,
              inProgress,
              containerThroughput,
              availableMsg,
              capacity,
              actualNum,
              staleActivationNum,
              duration,
              Running)

          // same with the above case but no duration exist.
          case (Removing, None) if staleActivationNum > 0 =>
            // we can safely get the value as we already checked the existence
            val num = staleActivationNum - inProgress
            // if it tries to create more containers than existing messages, we just create shortage
            val actualNum = if (num > availableMsg) availableMsg else num
            addServersIfPossible(
              existing,
              inProgress,
              0,
              availableMsg,
              capacity,
              actualNum,
              staleActivationNum,
              0.0,
              Running)

          // do nothing
          case _ =>
            Future.successful(DecisionResults(Skip, 0))
        }
      }
    }
  }

  private def addServersIfPossible(existing: Int,
                                   inProgress: Int,
                                   containerThroughput: Double,
                                   availableMsg: Int,
                                   capacity: Int,
                                   actualNum: Int,
                                   staleActivationNum: Int,
                                   duration: Double = 0.0,
                                   state: MemoryQueueState) = {
    if (actualNum > capacity) {
      // containers can be partially created. throttling should be enabled
      logging.info(
        this,
        s"[$state] enable namespace throttling and add $capacity container, staleActivationNum: $staleActivationNum, duration: ${duration}, containerThroughput: $containerThroughput, availableMsg: $availableMsg, existing: $existing, inProgress: $inProgress, capacity: $capacity [$invocationNamespace:$action]")
      Future.successful(DecisionResults(EnableNamespaceThrottling(dropMsg = false), capacity))
    } else if (actualNum <= 0) {
      // it means nothing
      Future.successful(DecisionResults(Skip, 0))
    } else {
      // create num containers
      // we need to create one more container than expected because existing container would already took the message
      logging.info(
        this,
        s"[$state]add $actualNum container, staleActivationNum: $staleActivationNum, duration: ${duration}, containerThroughput: $containerThroughput, availableMsg: $availableMsg, existing: $existing, inProgress: $inProgress, capacity: $capacity [$invocationNamespace:$action]")
      Future.successful(DecisionResults(AddContainer, actualNum))
    }
  }

  private def ceiling(d: Double) = math.ceil(d).toInt
}

object SchedulingDecisionMaker {
  def props(invocationNamespace: String, action: FullyQualifiedEntityName, schedulingConfig: SchedulingConfig)(
    implicit actorSystem: ActorSystem,
    ec: ExecutionContext,
    logging: Logging): Props = {
    Props(new SchedulingDecisionMaker(invocationNamespace, action, schedulingConfig))
  }
}
