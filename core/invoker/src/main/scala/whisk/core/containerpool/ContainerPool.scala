/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.containerpool

import akka.actor.Actor
import scala.collection.mutable
import akka.actor.ActorRef
import akka.actor.Props
import whisk.core.dispatcher.ActivationFeed.FreeWilly
import whisk.common.AkkaLogging
import akka.actor.ActorRefFactory
import whisk.core.entity.WhiskAction
import whisk.core.entity.EntityName
import whisk.core.entity.ExecManifest
import whisk.core.entity.CodeExecAsString

sealed trait WorkerState
case object Busy extends WorkerState
case object Free extends WorkerState

case class WorkerData(data: ContainerData, state: WorkerState)

/**
 * A pool managing containers to run actions on.
 *
 * @param childFactory method to create new containers
 * @param poolSize maximum size of containers allowed in the pool
 * @param feed actor to request more work from
 */
class ContainerPool(
    childFactory: ActorRefFactory => ActorRef,
    poolSize: Int,
    feed: ActorRef) extends Actor {
    val logging = new AkkaLogging(context.system.log)

    val pool = new mutable.HashMap[ActorRef, WorkerData]
    val prewarmedPool = new mutable.HashMap[ActorRef, WorkerData]

    val prewarmKind = "nodejs:6"
    val prewarmCount = 2
    val prewarmExec = ExecManifest.runtimesManifest.resolveDefaultRuntime(prewarmKind).map { manifest =>
        new CodeExecAsString(manifest, "", None)
    }

    logging.info(this, s"pre-warming $prewarmCount $prewarmKind containers")
    (1 to prewarmCount).foreach { _ =>
        prewarmContainer()
    }

    def receive: Receive = {
        // A job to run on a container
        case r: Run =>
            // Schedule a job to a warm container
            ContainerPool.schedule(r.action, r.msg.user.namespace, pool.toMap).orElse {
                // Create a cold container iff there's space in the pool
                if (pool.size < poolSize) {
                    takePrewarmContainer(r.action.exec.kind).orElse {
                        Some(createContainer())
                    }
                } else None
            }.orElse {
                // Remove a container and create a new one for the given job
                ContainerPool.remove(r.msg.user.namespace, pool.toMap).map { toDelete =>
                    removeContainer(toDelete)
                    createContainer()
                }
            } match {
                case Some(actor) =>
                    pool.get(actor) match {
                        case Some(w) =>
                            pool.update(actor, WorkerData(w.data, Busy))
                            actor ! r
                        case None =>
                            logging.error(this, "actor data not found")
                            self ! r
                    }
                case None =>
                    // "reenqueue" the request to find a container at a later point in time
                    self ! r
            }

        // Container tells us it is free to take more work
        case NeedWork(data: WarmedData) =>
            pool.update(sender(), WorkerData(data, Free))
            feed ! FreeWilly

        case NeedWork(data: PreWarmedData) =>
            prewarmedPool.update(sender(), WorkerData(data, Free))

        // Container got removed
        case ContainerRemoved =>
            pool.remove(sender())
    }

    /**
     * Creates a new container and updates state accordingly.
     */
    def createContainer() = {
        val ref = childFactory(context)
        pool.update(ref, WorkerData(NoData(), Free))
        ref
    }

    def prewarmContainer() = prewarmExec match {
        case Some(exec) => childFactory(context) ! Start(exec)
        case None       => logging.error(this, "could not pre-warm containers because the manifest is missing")
    }

    def takePrewarmContainer(kind: String) =
        if (kind == prewarmKind) {
            prewarmedPool.headOption.map {
                case (ref, data) =>
                    // Move the container to the usual pool
                    pool.update(ref, data)
                    prewarmedPool.remove(ref)
                    // Create a new prewarm container
                    prewarmContainer()

                    ref
            }
        } else None

    /**
     * Removes a container and updates state accordingly.
     */
    def removeContainer(toDelete: ActorRef) = {
        toDelete ! Remove
        pool.remove(toDelete)
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
     * @param namespace the namespace, that wants to run the action
     * @param idles a map of idle containers, awaiting work
     * @return a container if one found
     */
    def schedule[A](action: WhiskAction, namespace: EntityName, idles: Map[A, WorkerData]): Option[A] = {
        idles.find {
            case (_, WorkerData(WarmedData(_, `namespace`, `action`, _), Free)) => true
            case _ => false
        }.map(_._1)
    }

    /**
     * Finds the best container to remove to make space for the job passed to run.
     *
     * Determines which namespace consumes most resources in the current pool and
     * takes away one of their containers iff the namespace placing the new job is
     * not already the most consuming one.
     *
     * @param namespace the namespace that wants to get a container
     * @param pool a map of all containers in the pool
     * @return a container to be removed iff found
     */
    def remove[A](namespace: EntityName, pool: Map[A, WorkerData]): Option[A] = {
        val grouped = pool.collect {
            case (ref, WorkerData(w: WarmedData, Free)) => ref -> w
        }.groupBy {
            case (ref, data) => data.namespace
        }

        if (!grouped.isEmpty) {
            val (maxConsumer, containersToDelete) = grouped.maxBy(_._2.size)
            val (ref, _) = containersToDelete.minBy(_._2.lastUsed)
            Some(ref)
        } else None
    }

    def props(factory: ActorRefFactory => ActorRef,
              size: Int,
              feed: ActorRef) = Props(new ContainerPool(factory, size, feed))
}
