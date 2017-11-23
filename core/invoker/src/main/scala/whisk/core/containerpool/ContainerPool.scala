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

package whisk.core.containerpool

import scala.collection.immutable

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorRefFactory
import akka.actor.Props
import whisk.common.AkkaLogging

import whisk.core.entity.ByteSize
import whisk.core.entity.CodeExec
import whisk.core.entity.EntityName
import whisk.core.entity.ExecutableWhiskAction
import whisk.core.entity.size._
import whisk.core.connector.MessageFeed

sealed trait WorkerState
case object Busy extends WorkerState
case object Free extends WorkerState

case class WorkerData(data: ContainerData, state: WorkerState)

/**
 * A pool managing containers to run actions on.
 *
 * This pool fulfills the other half of the ContainerProxy contract. Only
 * one job (either Start or Run) is sent to a child-actor at any given
 * time. The pool then waits for a response of that container, indicating
 * the container is done with the job. Only then will the pool send another
 * request to that container.
 *
 * Upon actor creation, the pool will start to prewarm containers according
 * to the provided prewarmConfig, iff set. Those containers will **not** be
 * part of the poolsize calculation, which is capped by the poolSize parameter.
 * Prewarm containers are only used, if they have matching arguments
 * (kind, memory) and there is space in the pool.
 *
 * @param childFactory method to create new container proxy actor
 * @param maxActiveContainers maximum amount of containers doing work
 * @param maxPoolSize maximum size of containers allowed in the pool
 * @param feed actor to request more work from
 * @param prewarmConfig optional settings for container prewarming
 */
class ContainerPool(childFactory: ActorRefFactory => ActorRef,
                    maxActiveContainers: Int,
                    maxPoolSize: Int,
                    feed: ActorRef,
                    prewarmConfig: Option[PrewarmingConfig] = None)
    extends Actor {
  implicit val logging = new AkkaLogging(context.system.log)

  var freePool = immutable.Map.empty[ActorRef, ContainerData]
  var busyPool = immutable.Map.empty[ActorRef, ContainerData]
  var prewarmedPool = immutable.Map.empty[ActorRef, ContainerData]

  prewarmConfig.foreach { config =>
    logging.info(this, s"pre-warming ${config.count} ${config.exec.kind} containers")
    (1 to config.count).foreach { _ =>
      prewarmContainer(config.exec, config.memoryLimit)
    }
  }

  def receive: Receive = {
    // A job to run on a container
    case r: Run =>
      val container = if (busyPool.size < maxActiveContainers) {
        // Schedule a job to a warm container
        ContainerPool
          .schedule(r.action, r.msg.user.namespace, freePool)
          .orElse {
            if (busyPool.size + freePool.size < maxPoolSize) {
              takePrewarmContainer(r.action).orElse {
                Some(createContainer())
              }
            } else None
          }
          .orElse {
            // Remove a container and create a new one for the given job
            ContainerPool.remove(r.action, r.msg.user.namespace, freePool).map { toDelete =>
              removeContainer(toDelete)
              takePrewarmContainer(r.action).getOrElse {
                createContainer()
              }
            }
          }
      } else None

      container match {
        case Some((actor, data)) =>
          busyPool = busyPool + (actor -> data)
          freePool = freePool - actor
          actor ! r // forwards the run request to the container
        case None =>
          logging.error(this, "Rescheduling Run message, too many message in the pool")(r.msg.transid)
          self ! r
      }

    // Container is free to take more work
    case NeedWork(data: WarmedData) =>
      freePool = freePool + (sender() -> data)
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
        feed ! MessageFeed.Processed
      }

    // Container is prewarmed and ready to take work
    case NeedWork(data: PreWarmedData) =>
      prewarmedPool = prewarmedPool + (sender() -> data)

    // Container got removed
    case ContainerRemoved =>
      freePool = freePool - sender()
      busyPool.get(sender()).foreach { _ =>
        busyPool = busyPool - sender()
        feed ! MessageFeed.Processed
      }
  }

  /** Creates a new container and updates state accordingly. */
  def createContainer(): (ActorRef, ContainerData) = {
    val ref = childFactory(context)
    val data = NoData()
    freePool = freePool + (ref -> data)
    ref -> data
  }

  /** Creates a new prewarmed container */
  def prewarmContainer(exec: CodeExec[_], memoryLimit: ByteSize) =
    childFactory(context) ! Start(exec, memoryLimit)

  /**
   * Takes a prewarm container out of the prewarmed pool
   * iff a container with a matching kind is found.
   *
   * @param kind the kind you want to invoke
   * @return the container iff found
   */
  def takePrewarmContainer(action: ExecutableWhiskAction): Option[(ActorRef, ContainerData)] =
    prewarmConfig.flatMap { config =>
      val kind = action.exec.kind
      val memory = action.limits.memory.megabytes.MB
      prewarmedPool
        .find {
          case (_, PreWarmedData(_, `kind`, `memory`)) => true
          case _                                       => false
        }
        .map {
          case (ref, data) =>
            // Move the container to the usual pool
            freePool = freePool + (ref -> data)
            prewarmedPool = prewarmedPool - ref
            // Create a new prewarm container
            prewarmContainer(config.exec, config.memoryLimit)

            (ref, data)
        }
    }

  /** Removes a container and updates state accordingly. */
  def removeContainer(toDelete: ActorRef) = {
    toDelete ! Remove
    freePool = freePool - toDelete
    busyPool = busyPool - toDelete
  }
}

object ContainerPool {

  /**
   * Finds the best container for a given job to run on.
   *
   * Selects an arbitrary warm container from the passed pool of idle containers
   * that matches the action and the invocation namespace. The implementation uses
   * matching such that structural equality of action and the invocation namespace
   * is required.
   * Returns None iff no matching container is in the idle pool.
   * Does not consider pre-warmed containers.
   *
   * @param action the action to run
   * @param invocationNamespace the namespace, that wants to run the action
   * @param idles a map of idle containers, awaiting work
   * @return a container if one found
   */
  def schedule[A](action: ExecutableWhiskAction,
                  invocationNamespace: EntityName,
                  idles: Map[A, ContainerData]): Option[(A, ContainerData)] = {
    idles.find {
      case (_, WarmedData(_, `invocationNamespace`, `action`, _)) => true
      case _                                                      => false
    }
  }

  /**
   * Finds the best container to remove to make space for the job passed to run.
   *
   * Determines the least recently used Free container in the pool.
   *
   * @param action the action that wants to get a container
   * @param invocationNamespace the namespace, that wants to run the action
   * @param pool a map of all free containers in the pool
   * @return a container to be removed iff found
   */
  def remove[A](action: ExecutableWhiskAction,
                invocationNamespace: EntityName,
                pool: Map[A, ContainerData]): Option[A] = {
    // Try to find a Free container that is initialized with any OTHER action
    val freeContainers = pool.collect {
      case (ref, w: WarmedData) if (w.action != action || w.invocationNamespace != invocationNamespace) => ref -> w
    }

    if (freeContainers.nonEmpty) {
      val (ref, _) = freeContainers.minBy(_._2.lastUsed)
      Some(ref)
    } else None
  }

  def props(factory: ActorRefFactory => ActorRef,
            maxActive: Int,
            size: Int,
            feed: ActorRef,
            prewarmConfig: Option[PrewarmingConfig] = None) =
    Props(new ContainerPool(factory, maxActive, size, feed, prewarmConfig))
}

/** Contains settings needed to perform container prewarming */
case class PrewarmingConfig(count: Int, exec: CodeExec[_], memoryLimit: ByteSize)
