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

import scala.util.Try
import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel

import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Paths

import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.common.SimpleExec
import whisk.common.PrintStreamEmitter

import whisk.core.WhiskConfig
import whisk.core.container.DockerOutput
import whisk.core.container.ContainerAddr
import whisk.core.container.ContainerHash
import whisk.core.container.ContainerIdentifier
import whisk.core.container.ContainerName
import whisk.core.container.ContainerState

/**
 * Serves as interface to the docker cli tool. Ensures proper concurrency
 *  precautions are taken.
 *
 *  You only need one instance (and you shouldn't get more).
 */
class DockerProxy(config: WhiskConfig, val dockerHost: String)(implicit actorSystem: ActorSystem) extends Logging {
    private implicit val emitter: PrintStreamEmitter = this
    private implicit val ec = actorSystem.dispatcher

    // Determines how to run docker. Failure to find a Docker binary implies
    // a failure to initialize this instance of DockerProxy.
    private val dockerCmd: Seq[String] = {
        val alternatives = List("/usr/bin/docker", "/usr/local/bin/docker")

        val dockerBin = Try {
            alternatives.find(a => Files.isExecutable(Paths.get(a))).get
        } getOrElse {
            throw new FileNotFoundException(s"Couldn't locate docker binary (tried: ${alternatives.mkString(", ")}).")
        }

        if (dockerHost == "localhost") {
            Seq(dockerBin)
        } else {
            Seq(dockerBin, "--host", s"tcp://$dockerHost")
        }
    }

    val serializeDockerOp = config.invokerSerializeDockerOp.toBoolean
    val serializeDockerPull = config.invokerSerializeDockerOp.toBoolean
    info(this, s"dockerhost = $dockerHost    serializeDockerOp = $serializeDockerOp   serializeDockerPull = $serializeDockerPull")

    // docker run
    def run(name: Option[ContainerName], image: String, args: Seq[String], settings: ContainerSettings)(implicit transid: TransactionId): ContainerHash = {
        val nameArgs = name.map(n => Seq("--name", n.name)).getOrElse(Seq.empty[String])
        val imgArgs = Seq("-d", image)
        val settingsArgs = settings.toArgs

        runCmd((Seq("run") ++ settingsArgs ++ nameArgs ++ imgArgs ++ args): _*).toOption.map { result =>
            ContainerHash.fromString(result)
        } getOrElse {
            throw new Exception(s"Container hash expected after `docker run`.")
        }
    }

    // docker inspect, with some logic to retrieve the IP address specifically
    def inspectIPAddress(id: ContainerIdentifier)(implicit transid: TransactionId): ContainerAddr = {
        runCmd("inspect", "--format", "'{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'", id.id).toOption.map { output =>
            ContainerAddr(output.substring(1, output.length - 1), 8080) // FIXME that 8080 is not from Docker.
        } getOrElse {
            throw new Exception(s"Container IP expected after `docker inspect`.")
        }
    }

    // docker pause
    def pause(id: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runCmd(printLogOnError = false, "pause", id.id)
    }

    // docker unpause
    def unpause(id: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runCmd(printLogOnError = false, "unpause", id.id)
    }

    // docker logs
    def logs(id: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runCmd("logs", id.id)
    }

    // docker rm -f
    // Forcefully removes a container, can be used on a running container but not a paused one.
    //
    def rm(id: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runCmd("rm", "-f", id.id)
    }

    def ps(all: Boolean)(implicit transid: TransactionId): Seq[ContainerState] = {
        val cmd = Seq("ps", "--no-trunc") ++ (if (all) Seq("-a") else Seq.empty[String])

        def parseOneLine(line: String) = Try {
            val tokens = line.split("""\s+""")
            ContainerState(ContainerHash.fromString(tokens(0)), tokens(1), ContainerName.fromString(tokens.last))
        } toOption

        for (
            output <- runCmd(cmd: _*).toOption.toSeq;
            line <- output.split("\n").drop(1);
            info <- parseOneLine(line)
        ) yield info
    }

    // docker pull
    def pull(img: String)(implicit transid: TransactionId): DockerOutput = {
        runCmd("pull", img)
    }

    private def runCmd(args: String*)(implicit transid: TransactionId): DockerOutput = {
        runCmd(printLogOnError = true, args: _*)
    }

    private def runCmd(printLogOnError: Boolean, args: String*)(implicit transid: TransactionId): DockerOutput = {
        val start = transid.started(this, LoggingMarkers.INVOKER_DOCKER_CMD(args(0)))

        try {
            val fullCmd = dockerCmd ++ args

            val (stdout, stderr, exitCode) = SimpleExec.syncRunCmd(fullCmd)

            if (exitCode == 0) {
                transid.finished(this, start)
                DockerOutput(stdout.trim)
            } else {
                if (printLogOnError) {
                    transid.failed(this, start, s"stdout:\n$stdout\nstderr:\n$stderr", ErrorLevel)
                } else {
                    transid.failed(this, start)
                }
                DockerOutput.unavailable
            }
        } catch {
            case t: Throwable =>
                transid.failed(this, start, "error: " + t.getMessage, ErrorLevel)
                DockerOutput.unavailable
        }
    }
}
