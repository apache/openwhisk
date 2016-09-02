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

package whisk.core.container.docker

import whisk.core.entity.ActionLimits

// Settings that can be tweaked for a `docker run` command.
case class ContainerSettings(
    network: String,
    cpuShare: Int,
    env: Map[String, String],
    limits: ActionLimits, // FIXME these limits should be Docker specific and constructed from ActionLimits.
    policy: Option[String]) {

    def toArgs: Seq[String] = {
        // Static part.
        val capabilityArg = Seq("--cap-drop", "NET_RAW", "--cap-drop", "NET_ADMIN")
        val consulServiceIgnore = Seq("-e", "SERVICE_IGNORE=true")
        val fileHandleLimit = Seq("--ulimit", "nofile=64:64")
        val processLimit = Seq("--ulimit", "nproc=512:512")

        // Settings-specific part.
        val containerNetwork = Seq("--net", network)
        val cpuArg = Seq("-c", cpuShare.toString)
        val envArgs = env.toSeq.flatMap({ case (k, v) => Seq("-e", s"$k=$v") })
        val memoryArg = Seq("-m", s"${limits.memory()}m")

        val securityOpts = policy map { p =>
            Seq("--security-opt", s"apparmor:${p}")
        } getOrElse (Seq.empty[String])

        envArgs ++ consulServiceIgnore ++ cpuArg ++ memoryArg ++ capabilityArg ++ fileHandleLimit ++ processLimit ++ securityOpts ++ containerNetwork
    }
}
