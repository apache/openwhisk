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

package whisk.core.container

import scala.annotation.tailrec

import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig.invokerContainerNetwork
import whisk.core.WhiskConfig.selfDockerEndpoint
import whisk.core.entity.ActionLimits

/**
 * Reifies a docker container.
 */
class Container(
    originalId: TransactionId,
    useRunc: Boolean,
    val dockerhost: String,
    mounted: Boolean,
    val key: ActionContainerId,
    containerName: Option[ContainerName],
    val image: String,
    network: String,
    cpuShare: Int,
    policy: Option[String],
    dnsServers: Seq[String],
    val limits: ActionLimits = ActionLimits(),
    env: Map[String, String] = Map(),
    args: Array[String] = Array())(
        implicit val logging: Logging)
    extends ContainerUtils {

    implicit var transid = originalId

    val id = Container.idCounter.next()
    val nameAsString = containerName.map(_.name).getOrElse("anon")

    val (containerId, containerHostAndPort) = bringup(mounted, containerName, image, network, cpuShare, env, args, limits, policy, dnsServers)

    def details: String = {
        val name = containerName.map(_.name) getOrElse "??"
        val id = containerId.id
        val ip = containerHostAndPort getOrElse "??"
        s"container [$name] [$id] [$ip]"
    }

    def pause(): Boolean =
        if (useRunc) {
            RuncUtils.isSuccessful(RuncUtils.pause(containerId))
        } else {
            DockerOutput.isSuccessful(pauseContainer(containerId))
        }

    def unpause(): Boolean =
        if (useRunc) {
            RuncUtils.isSuccessful(RuncUtils.resume(containerId))
        } else {
            DockerOutput.isSuccessful(unpauseContainer(containerId))
        }

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
    final def remove(needUnpause: Boolean, tryCount: Int = Container.removeContainerRetryCount)(implicit transid: TransactionId): Unit = {
        if (tryCount <= 0) {
            logging.error(this, s"Failed to remove container ${containerId.id}")
        } else {
            if (tryCount == Container.removeContainerRetryCount) {
                logging.info(this, s"Removing container ${containerId.id}")
            } else {
                logging.warn(this, s"Retrying to remove container ${containerId.id}")
            }
            if (needUnpause) unpause() // a paused container cannot be removed
            rmContainer(containerId).toOption match {
                case None => remove(needUnpause, tryCount - 1)
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
