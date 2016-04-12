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

import whisk.core.WhiskConfig
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
    image: String,
    network: String,
    pull: Boolean = false,
    val limits: ActionLimits = ActionLimits(),
    env: Map[String, String] = Map(),
    args: Array[String] = Array())
    extends ContainerUtils {

    setVerbosity(pool.getVerbosity())

    implicit var transid = originalId

    val id = Container.idCounter.next()
    val name = containerName.getOrElse("anon")
    val dockerhost = pool.dockerhost

    if (pull) pullImage(image)

    val (containerId, containerIP) = bringup(containerName, image, network, env, args, limits)

    def details: String = {
        val name = containerName getOrElse "??"
        val id = containerId getOrElse "??"
        val ip = containerIP getOrElse "??"
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
     * Removes a container (it may be running).
     */
    def remove()(implicit transid: TransactionId): Unit = {
        info(this, s"Removing container $containerId")
        unpause()  // a paused container cannot be removed
        rmContainer(containerId)
    }
}

object Container {
    def requiredProperties = Map(selfDockerEndpoint -> null, invokerContainerNetwork -> "bridge")
    private val idCounter = new Counter()
}
