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

import java.io.{ File, FileNotFoundException }

import scala.language.postfixOps
import scala.util.Try

import akka.event.Logging.ErrorLevel
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.{ Logging, SimpleExec, TransactionId, LoggingMarkers }
import whisk.common.Logging
import whisk.core.entity.ActionLimits

/**
 * Information from docker ps.
 */
case class ContainerState(id: ContainerHash, image: String, name: ContainerName)

trait ContainerUtils {

    implicit val logging: Logging

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
    def bringup(mounted: Boolean,
                name: Option[ContainerName],
                image: String,
                network: String,
                cpuShare: Int,
                env: Map[String, String],
                args: Array[String],
                limits: ActionLimits,
                policy: Option[String],
                dnsServer: Seq[String])(
                    implicit transid: TransactionId): (ContainerHash, Option[ContainerAddr]) = {
        val id = makeContainer(name, image, network, cpuShare, env, args, limits, policy, dnsServer)
        val host = getContainerIpAddr(id, mounted, network)
        (id, host map { ContainerAddr(_, 8080) })
    }

    /**
     * Pulls container images.
     */
    def pullImage(image: String)(implicit transid: TransactionId): DockerOutput = ContainerUtils.pullImage(dockerhost, image)

    /*
     * TODO: The file handle and process limits should be moved to some global limits config.
     */
    @throws[ContainerError]
    def makeContainer(name: Option[ContainerName],
                      image: String,
                      network: String,
                      cpuShare: Int,
                      env: Map[String, String],
                      args: Seq[String],
                      limits: ActionLimits,
                      policy: Option[String],
                      dnsServers: Seq[String])(
                          implicit transid: TransactionId): ContainerHash = {
        val nameOption = name.map(n => Array("--name", n.name)).getOrElse(Array.empty[String])
        val cpuArg = Array("-c", cpuShare.toString)
        val memoryArg = Array("-m", s"${limits.memory.megabytes}m", "--memory-swap", s"${limits.memory.megabytes}m")
        val capabilityArg = Array("--cap-drop", "NET_RAW", "--cap-drop", "NET_ADMIN")
        val consulServiceIgnore = Array("-e", "SERVICE_IGNORE=true")
        val fileHandleLimit = Array("--ulimit", "nofile=1024:1024")
        val processLimit = Array("--pids-limit", "1024")
        val securityOpts = policy map { p => Array("--security-opt", s"apparmor:${p}") } getOrElse (Array.empty[String])
        val dnsOpts = dnsServers.map(Seq("--dns", _)).flatten
        val containerNetwork = Array("--net", network)

        val cmd = Seq("run") ++ makeEnvVars(env) ++ consulServiceIgnore ++ nameOption ++ cpuArg ++ memoryArg ++
            capabilityArg ++ fileHandleLimit ++ processLimit ++ securityOpts ++ dnsOpts ++ containerNetwork ++ Seq("-d", image) ++ args

        runDockerCmd(cmd: _*).toOption.map { result =>
            ContainerHash.fromString(result)
        } getOrElse {
            throw new ContainerError("Failed to start container.")
        }
    }

    def killContainer(container: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd("kill", container.id)
    }

    def getContainerLogs(container: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd("logs", container.id)
    }

    def pauseContainer(container: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd("pause", container.id)
    }

    def unpauseContainer(container: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd("unpause", container.id)
    }

    /**
     * Forcefully removes a container, can be used on a running container but not a paused one.
     */
    def rmContainer(container: ContainerIdentifier)(implicit transid: TransactionId): DockerOutput = {
        runDockerCmd("rm", "-f", container.id)
    }

    /*
     * List containers (-a if all).
     */
    def listContainers(all: Boolean)(implicit transid: TransactionId): Seq[ContainerState] = {
        val tmp = Array("ps", "--no-trunc")
        val cmd = if (all) tmp :+ "-a" else tmp
        runDockerCmd(cmd: _*).toOption map { output =>
            val lines = output.split("\n").drop(1).toSeq // skip the header
            lines.map(parsePsOutput)
        } getOrElse Seq()
    }

    def getDockerLogSize(containerId: ContainerHash, mounted: Boolean)(implicit transid: TransactionId): Long = {
        try {
            getDockerLogFile(containerId, mounted).length
        } catch {
            case e: Exception =>
                logging.error(this, s"getDockerLogSize failed on ${containerId.id}")
                0
        }
    }

    /**
     * Reads the contents of the file at the given position.
     * It is assumed that the contents does exist and that region is not changing concurrently.
     */
    def getDockerLogContent(containerHash: ContainerHash, start: Long, end: Long, mounted: Boolean)(implicit transid: TransactionId): Array[Byte] = {
        var fis: java.io.FileInputStream = null
        try {
            val file = getDockerLogFile(containerHash, mounted)
            fis = new java.io.FileInputStream(file)
            val channel = fis.getChannel().position(start)
            var remain = (end - start).toInt
            val buffer = java.nio.ByteBuffer.allocate(remain)
            while (remain > 0) {
                val read = channel.read(buffer)
                if (read > 0)
                    remain = read - read.toInt
            }
            buffer.array
        } catch {
            case e: Exception =>
                logging.error(this, s"getDockerLogContent failed on ${containerHash.hash}: ${e.getClass}: ${e.getMessage}")
                Array()
        } finally {
            if (fis != null) fis.close()
        }

    }

    /**
     * Obtain IP addr by looking at config file but fall back to inspect when in testing mode (where there is no root access)
     * or as a general fallback if the config file path fails.
     */
    def getContainerIpAddr(container: ContainerHash, mounted: Boolean, network: String)(implicit transid: TransactionId): Option[String] =
        if (!mounted) {
            getContainerIpAddrViaInspect(container)
        } else {
            getContainerIpAddrViaConfig(container, network).toOption orElse {
                logging.warn(this, "Failed to obtain IP address of container via config file.  Falling back to inspect.")
                getContainerIpAddrViaInspect(container)
            }
        }

    /**
     * Obtain container IP address with docker inspect.
     */
    def getContainerIpAddrViaInspect(container: ContainerHash)(implicit transid: TransactionId): Option[String] = {
        val inspectFormat = "'{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'"
        runDockerCmd("inspect", "--format", inspectFormat, container.id).toOption.map { output =>
            output.substring(1, output.length - 1)
        }
    }

    /**
     * Obtain container IP address by reading docker config file.
     */
    def getContainerIpAddrViaConfig(container: ContainerHash, network: String)(implicit transid: TransactionId): Try[String] =
        getIpAddr(getDockerConfig(container, true), network)

    private def runDockerCmd(args: String*)(implicit transid: TransactionId): DockerOutput = runDockerCmd(false, args)

    /**
     * Synchronously runs the given docker command returning stdout if successful.
     */
    private def runDockerCmd(skipLogError: Boolean, args: Seq[String])(implicit transid: TransactionId): DockerOutput =
        ContainerUtils.runDockerCmd(dockerhost, skipLogError, args)(transid, logging)

    /**
     * Obtain the per container directory Docker maintains for each container.
     *
     * If this component is running outside a container, then logs files are in docker's own
     * /var/lib/docker/containers.  If running inside a container, is mounted at /containers.
     * Root access is needed when running outside the container.
     */
    private def dockerContainerDir(mounted: Boolean, containerId: ContainerHash) = {
        (if (mounted) "/containers/" else "/var/lib/docker/containers/") + containerId.hash.toString
    }

    /**
     * Gets the filename of the docker logs of other containers that is mapped back into the invoker.
     */
    private def getDockerLogFile(containerId: ContainerHash, mounted: Boolean) = {
        new java.io.File(s"""${dockerContainerDir(mounted, containerId)}/${containerId.hash}-json.log""").getCanonicalFile()
    }

    /**
     * Return docker config as a JsObject by reading the config.v2.json file for the given container.
     */
    private def getDockerConfig(containerId: ContainerHash, mounted: Boolean): JsObject = {
        val configFile = s"${dockerContainerDir(mounted, containerId)}/config.v2.json"
        val contents = scala.io.Source.fromFile(configFile).mkString
        contents.parseJson.asJsObject
    }

    /**
     * Extracts the IP addr from the docker config object with projection rather than converting the entire config.
     */
    private def getIpAddr(config: JsObject, network: String): Try[String] = Try {
        val networks = config.fields("NetworkSettings").asJsObject.fields("Networks").asJsObject
        val userland = networks.fields(network).asJsObject
        val ipAddr = userland.fields("IPAddress")
        ipAddr.convertTo[String]
    }

    private def parsePsOutput(line: String): ContainerState = {
        val tokens = line.split("\\s+")
        val hash = ContainerHash.fromString(tokens(0))
        val name = ContainerName.fromString(tokens.last)
        ContainerState(hash, tokens(1), name)
    }
}

object ContainerUtils {

    /**
     * Synchronously runs the given docker command returning stdout if successful.
     */
    def runDockerCmd(dockerhost: String, skipLogError: Boolean, args: Seq[String])(implicit transid: TransactionId, logging: Logging): DockerOutput = {
        val start = transid.started(this, LoggingMarkers.INVOKER_DOCKER_CMD(args(0)))

        try {
            val fullCmd = getDockerCmd(dockerhost) ++ args

            val (stdout, stderr, exitCode) = SimpleExec.syncRunCmd(fullCmd)

            if (exitCode == 0) {
                transid.finished(this, start)
                DockerOutput(stdout.trim)
            } else {
                if (!skipLogError) {
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

    @throws[FileNotFoundException]
    private def getDockerCmd(dockerhost: String): Seq[String] = {
        def file(path: String) = Try { new File(path) } filter { _.exists } toOption

        val dockerLoc = file("/usr/bin/docker") orElse file("/usr/local/bin/docker")

        val dockerBin = dockerLoc.map(_.toString).getOrElse {
            throw new FileNotFoundException("Failed to locate docker binary.")
        }

        if (dockerhost == "localhost") {
            Seq(dockerBin)
        } else {
            Seq(dockerBin, "--host", s"tcp://$dockerhost")
        }
    }

    /**
     * Pulls container images.
     */
    @throws[ContainerError]
    def pullImage(dockerhost: String, image: String)(implicit transid: TransactionId, logging: Logging): DockerOutput = {
        val cmd = Array("pull", image)
        val result = runDockerCmd(dockerhost, false, cmd)
        if (result != DockerOutput.unavailable) {
            result
        } else {
            throw new ContainerError(s"Failed to pull container image '$image'.")
        }
    }

}
