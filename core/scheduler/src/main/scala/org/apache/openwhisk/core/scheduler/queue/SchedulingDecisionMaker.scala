package org.apache.openwhisk.core.scheduler.queue

import akka.actor.{Actor, ActorSystem, Props}
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.FullyQualifiedEntityName

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class SchedulingDecisionMaker(
  invocationNamespace: String,
  action: FullyQualifiedEntityName,
  StaleThreshold: Double = 100.0)(implicit val actorSystem: ActorSystem, ec: ExecutionContext, logging: Logging)
    extends Actor {

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
      val capacity = limit - existingContainerCountInNs - inProgressContainerCountInNs
      if (capacity <= 0) {
        stateName match {

          /**
           * If the container is created later (for any reason), all activations fail(too many requests).
           *
           * However, if the container exists(totalContainers != 0), the activation is not treated as a failure and the activation is delivered to the container.
           */
          case Running =>
            logging.info(
              this,
              s"there is no capacity activations will be dropped or throttled, (availableMsg: $availableMsg totalContainers: $totalContainers, limit: $limit, namespaceContainers: ${existingContainerCountInNs}, namespaceInProgressContainer: ${inProgressContainerCountInNs}) [$invocationNamespace:$action]")
            Future.successful(DecisionResults(EnableNamespaceThrottling(dropMsg = totalContainers == 0), 0))

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

          case (Running, Some(duration)) if staleActivationNum > 0 =>
            // we can safely get the value as we already checked the existence
            val containerThroughput = StaleThreshold / duration
            val num = ceiling(availableMsg.toDouble / containerThroughput)
            // if it tries to create more containers than existing messages, we just create shortage
            val actualNum = (if (num > availableMsg) availableMsg else num) - inProgress
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

          // need more containers and a message is already processed
          case (Running, Some(duration)) =>
            // we can safely get the value as we already checked the existence
            val containerThroughput = StaleThreshold / duration
            val expectedTps = containerThroughput * (existing + inProgress)

            if (availableMsg >= expectedTps && existing + inProgress < availableMsg) {
              val num = ceiling((availableMsg / containerThroughput) - existing - inProgress)
              // if it tries to create more containers than existing messages, we just create shortage
              val actualNum = if (num + totalContainers > availableMsg) availableMsg - totalContainers else num
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
            } else {
              Future.successful(DecisionResults(Skip, 0))
            }

          // generally we assume there are enough containers for actions when shutting down the scheduler
          // but if there were already too many activation in the queue with not enough containers,
          // we should add more containers to quickly consume those messages.
          // this case is for that as a last resort.
          case (Removing, Some(duration)) if staleActivationNum > 0 =>
            // we can safely get the value as we already checked the existence
            val containerThroughput = StaleThreshold / duration
            val num = ceiling(availableMsg.toDouble / containerThroughput)
            // if it tries to create more containers than existing messages, we just create shortage
            val actualNum = (if (num > availableMsg) availableMsg else num) - inProgress
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
  def props(invocationNamespace: String, action: FullyQualifiedEntityName, StaleThreshold: Double = 100.0)(
    implicit actorSystem: ActorSystem,
    ec: ExecutionContext,
    logging: Logging): Props = {
    Props(new SchedulingDecisionMaker(invocationNamespace, action, StaleThreshold))
  }
}
