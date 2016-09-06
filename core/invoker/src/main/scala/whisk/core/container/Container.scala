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

package whisk.core.container

import scala.annotation.tailrec

import akka.event.Logging.LogLevel

import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.WhiskConfig.invokerContainerNetwork
import whisk.core.entity.ActionLimits
import whisk.common.TransactionId
import whisk.common.Counter

/**
 * Reifies a docker container.
 */
class Container(
    originalId: TransactionId,
    val dockerhost: String,
    val key: ActionContainerId,
    containerName: Option[ContainerName],
    val image: String,
    network: String,
    cpuShare: Int,
    policy: Option[String],
    val limits: ActionLimits = ActionLimits(),
    env: Map[String, String] = Map(),
    args: Array[String] = Array(),
    logLevel: LogLevel)
    extends ContainerUtils {

    setVerbosity(logLevel)

    implicit var transid = originalId

    val id = Container.idCounter.next()
    val name = containerName.getOrElse("anon")

    val (containerId, containerHostAndPort) = bringup(containerName, image, network, cpuShare, env, args, limits, policy)

    def details: String = {
        val name = containerName getOrElse "??"
        val id = containerId.id
        val ip = containerHostAndPort getOrElse "??"
        s"container [$name] [$id] [$ip]"
    }

    def pause(): Unit = pauseContainer(containerId)

    def unpause(): Unit = unpauseContainer(containerId)

    /**
     * A prefix of the container id known to be displayed by docker ps.
     */
    lazy val containerIdPrefix: String = {
        // docker ps contains only a prefix of the id
        containerId.id.take(8)
    }

    /**
     * Gets logs for container.
     */
    def getLogs()(implicit transid: TransactionId): String = {
        getContainerLogs(containerId).toOption getOrElse ""
    }

    /**
     * Unpauses and removes a container (it may be running).
     */
    @tailrec
    final def remove(tryCount: Int = Container.removeContainerRetryCount)(implicit transid: TransactionId): Unit = {
        if (tryCount <= 0) {
            error(this, s"Failed to remove container $containerId")
        } else {
            if (tryCount == Container.removeContainerRetryCount) {
                info(this, s"Removing container $containerId")
            } else {
                warn(this, s"Retrying to remove container $containerId")
            }
            unpause() // a paused container cannot be removed
            rmContainer(containerId).toOption match {
                case None => remove(tryCount - 1)
                case _    => ()
            }
        }
    }
}

object Container {
    def requiredProperties = Map(selfDockerEndpoint -> null, invokerContainerNetwork -> "bridge")
    private val idCounter = new Counter()
    private val removeContainerRetryCount = 2
}
