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
    pool: ContainerPool,
    val key: String,
    containerName: Option[String],
    val image: String,
    network: String,
    policy: Option[String],
    val limits: ActionLimits = ActionLimits(),
    env: Map[String, String] = Map(),
    args: Array[String] = Array())
    extends ContainerUtils {

    setVerbosity(pool.getVerbosity())

    implicit var transid = originalId

    val id = Container.idCounter.next()
    val name = containerName.getOrElse("anon")
    val dockerhost = pool.dockerhost

    val (containerId, containerHostAndPort) = bringup(containerName, image, network, env, args, limits, policy)

    def details: String = {
        val name = containerName getOrElse "??"
        val id = containerId getOrElse "??"
        val ip = containerHostAndPort getOrElse "??"
        s"container [$name] [$id] [$ip]"
    }

    def pause() {
        containerId map pauseContainer
    }

    def unpause() {
        containerId map unpauseContainer
    }

    /**
     * Returns a prefix of the container id known to be displayed by docker ps.
     */
    def containerIdPrefix() = {
        // docker ps contains only a prefix of the id
        containerId map { id => id.substring(0, math.min(8, id.length)) } getOrElse "anon"
    }

    /**
     * Gets logs for container.
     */
    def getLogs()(implicit transid: TransactionId): String = {
        getContainerLogs(containerId) getOrElse ""
    }

    /**
     * Unpauses and removes a container (it may be running).
     */
    def remove(tryCount: Int = Container.removeContainerRetryCount)(implicit transid: TransactionId): Unit = {
        if (tryCount <= 0) {
            error(this, s"Failed to remove container $containerId")
        } else {
            if (tryCount == Container.removeContainerRetryCount) {
                info(this, s"Removing container $containerId")
            } else {
                warn(this, s"Retrying to remove container $containerId")
            }
            unpause() // a paused container cannot be removed
            rmContainer(containerId) match {
                case None => remove(tryCount - 1)
                case _    => ()
            }
        }
    }

    /**
     * Gets the current size of the mounted file associated with this whisk container.
     */
    def getLogSize(mounted: Boolean) = pool.getLogSize(this, mounted)

    /**
     * Gets docker logs.
     */
    def getDockerLogContent(start: Long, end: Long, mounted: Boolean)(implicit transid: TransactionId): Array[Byte] = {
        this.synchronized {
            containerId map { id => pool.getDockerLogContent(id, start, end, mounted) } getOrElse Array()
        }
    }

}

object Container {
    def requiredProperties = Map(selfDockerEndpoint -> null, invokerContainerNetwork -> "bridge")
    private val idCounter = new Counter()
    private val removeContainerRetryCount = 2
}
