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

import whisk.common.Logging
import whisk.common.SimpleExec
import whisk.common.TransactionId
import whisk.core.entity.ActionLimits
import java.io.File
import scala.util.Try
import scala.language.postfixOps
import whisk.common.LoggingMarkers
import akka.event.Logging.ErrorLevel
import whisk.common.PrintStreamEmitter

/**
 * Information from docker ps.
 */
case class ContainerState(id: String, image: String, name: String)

trait ContainerUtils extends Logging {

    /** Defines the docker host, optional **/
    val dockerhost: String

    def makeEnvVars(env: Map[String, String]): Array[String] = {
        env.map {
            kv => s"-e ${kv._1}=${kv._2}"
        }.mkString(" ").split(" ").filter { x => x.nonEmpty }
    }

    /**
     * Creates a container instance and runs it.
     *
     * @param image the docker image to run
     * @return container id and container host
     */
    def bringup(name: Option[String], image: String, network: String, env: Map[String, String], args: Array[String], limits: ActionLimits, policy: Option[String])(implicit transid: TransactionId): (ContainerId, ContainerIP) = {
        val id = makeContainer(name, image, network, env, args, limits, policy)
        val host = if (id.isDefined) getContainerHost(name) else None
        (id, host)
    }

    /**
     * Pulls container images.
     */
    def pullImage(image: String)(implicit transid: TransactionId): DockerOutput = ContainerUtils.pullImage(dockerhost, image)

    /*
     * TODO: The file handle and process limits should be moved to some global limits config.
     */
    def makeContainer(name: Option[String], image: String, network: String, env: Map[String, String], args: Array[String], limits: ActionLimits, policy: Option[String])(implicit transid: TransactionId): ContainerId = {
        val nameOption = if (name isDefined) Array("--name", name.getOrElse("")) else Array.empty[String]
        val memoryArg = Array("-m", s"${limits.memory()}m")
        val capabilityArg = Array("--cap-drop", "NET_RAW", "--cap-drop", "NET_ADMIN")
        val consulServiceIgnore = Array("-e", "SERVICE_IGNORE=true")
        val fileHandleLimit = Array("--ulimit", "nofile=64:64")
        val processLimit = Array("--ulimit", "nproc=512:512")
        val securityOpts = policy map { p => Array("--security-opt", s"apparmor:${p}") } getOrElse (Array.empty[String])
        val containerNetwork = Array("--net", network)
        val cmd = Array("run") ++ makeEnvVars(env) ++ consulServiceIgnore ++ nameOption ++ memoryArg ++
            capabilityArg ++ fileHandleLimit ++ processLimit ++ securityOpts ++ containerNetwork ++ Array("-d", image) ++ args
        runDockerCmd(cmd: _*)
    }

    def killContainer(name: String)(implicit transid: TransactionId): DockerOutput = killContainer(Some(name))

    def killContainer(container: ContainerName)(implicit transid: TransactionId): DockerOutput = {
        container map { name => runDockerCmd("kill", name) } getOrElse None
    }

    def getContainerLogs(container: ContainerName)(implicit transid: TransactionId): DockerOutput = {
        container map { name => runDockerCmd("logs", name) } getOrElse None
    }

    def pauseContainer(name: String)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd(true, Array("pause", name))
    }

    def unpauseContainer(name: String)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd(true, Array("unpause", name))
    }

    def rmContainer(container: String)(implicit transid: TransactionId): DockerOutput = rmContainer(Some(container))

    /**
     * Forcefully removes a container, can be used on a running container but not a paused one.
     */
    def rmContainer(container: ContainerName)(implicit transid: TransactionId): DockerOutput = {
        container map { name => runDockerCmd("rm", "-f", name) } getOrElse None
    }

    /*
     * List containers (-a if all).
     */
    def listContainers(all: Boolean)(implicit transid: TransactionId): Array[ContainerState] = {
        val tmp = Array("ps", "--no-trunc")
        val cmd = if (all) tmp :+ "-a" else tmp
        runDockerCmd(cmd: _*) map { output =>
            val lines = output.split("\n").drop(1) // skip the header
            lines.map(parsePsOutput)
        } getOrElse Array()
    }

    def getDockerLogSize(containerId: String, mounted: Boolean)(implicit transid: TransactionId): Long = {
        try {
            getDockerLogFile(containerId, mounted).length
        } catch {
            case e: Exception =>
                error(this, s"getDockerLogSize failed on $containerId")
                0
        }
    }

    /**
     * Reads the contents of the file at the given position.
     * It is assumed that the contents does exist and that region is not changing concurrently.
     */
    def getDockerLogContent(containerId: String, start: Long, end: Long, mounted: Boolean)(implicit transid: TransactionId): Array[Byte] = {
        var fis: java.io.FileInputStream = null
        try {
            val file = getDockerLogFile(containerId, mounted)
            fis = new java.io.FileInputStream(file)
            val channel = fis.getChannel().position(start)
            var remain = (end - start).toInt
            val buffer = java.nio.ByteBuffer.allocate(remain)
            while (remain > 0) {
                val read = channel.read(buffer)
                if (read > 0)
                    remain = read - read.toInt
                Thread.sleep(50) // TODO What is this for?
            }
            buffer.array
        } catch {
            case e: Exception =>
                error(this, s"getDockerLogContent failed on $containerId")
                Array()
        } finally {
            if (fis != null) fis.close()
        }

    }

    def getContainerHost(container: ContainerName)(implicit transid: TransactionId): ContainerIP = {
        container map { name =>
            runDockerCmd("inspect", "--format", "'{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'", name) map {
                output => appendPort(output.substring(1, output.length - 1))
            }
        } getOrElse None
    }

    def runDockerCmd(args: String*)(implicit transid: TransactionId): DockerOutput = runDockerCmd(false, args)

    /**
     * Synchronously runs the given docker command returning stdout if successful.
     */
    def runDockerCmd(skipLogError: Boolean, args: Seq[String])(implicit transid: TransactionId): DockerOutput =
        ContainerUtils.runDockerCmd(dockerhost, skipLogError, args)(transid)

    // If running outside a container, then logs files are in docker's own
    // /var/lib/docker/containers.  If running inside a container, is mounted at /containers.
    // Root access is needed when running outside the container.
    private def dockerContainerDir(mounted: Boolean) = {
        if (mounted) "/containers" else "/var/lib/docker/containers"
    }

    /**
     * Gets the filename of the docker logs of other containers that is mapped back into the invoker.
     */
    private def getDockerLogFile(containerId: String, mounted: Boolean) = {
        new java.io.File(s"""${dockerContainerDir(mounted)}/$containerId/$containerId-json.log""").getCanonicalFile()
    }

    private def parsePsOutput(line: String) = {
        val tokens = line.split("\\s+")
        ContainerState(tokens(0), tokens(1), tokens(tokens.length - 1))
    }

    protected def appendPort(host: String) = s"$host:8080"

    protected type ContainerName = Option[String]
    protected type ContainerId = Option[String]
    protected type ContainerIP = Option[String]
    protected type DockerOutput = ContainerUtils.DockerOutput
}

object ContainerUtils extends Logging {

    protected type DockerOutput = Option[String]
    private implicit val emitter: PrintStreamEmitter = this

    /**
     * Synchronously runs the given docker command returning stdout if successful.
     */
    def runDockerCmd(dockerhost: String, skipLogError: Boolean, args: Seq[String])(implicit transid: TransactionId): DockerOutput = {
        val start = transid.started(this, LoggingMarkers.INVOKER_DOCKER_CMD(args(0)))
        getDockerCmd(dockerhost) map { _ ++ args } map {
            SimpleExec.syncRunCmd(_)(transid)
        } map {
            case (stdout, stderr, exitCode) =>
                if (exitCode == 0) {
                    transid.finished(this, start)
                    Some(stdout.trim)
                } else {
                    if (!skipLogError) {
                        transid.failed(this, start, s"stdout:\n$stdout\nstderr:\n$stderr", ErrorLevel)
                    } else {
                        transid.failed(this, start)
                    }
                    None
                }
        } getOrElse {
            transid.failed(this, start, "docker executable not found", ErrorLevel)
            None
        }
    }

    private def file(path: String) = Try { new File(path) } filter { _.exists } toOption

    def getDockerCmd(dockerhost: String) = {
        val dockerLoc = file("/usr/bin/docker") orElse file("/usr/local/bin/docker")
        if (dockerhost == "localhost") {
            dockerLoc map { f => Array[String](f.toString) }
        } else {
            dockerLoc map { f => Array[String](f.toString, "--host", s"tcp://$dockerhost") }
        }
    }

    /**
     * Pulls container images.
     */
    def pullImage(dockerhost: String, image: String)(implicit transid: TransactionId): DockerOutput = {
        val cmd = Array("pull", image)
        runDockerCmd(dockerhost, false, cmd)
    }

}
