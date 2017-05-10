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

package whisk.core

import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.Try

import akka.actor.ActorSystem
import whisk.common.Config
import whisk.common.ConsulClient
import whisk.common.ConsulKV
import whisk.common.Logging

/**
 * A set of properties which might be needed to run a whisk microservice implemented
 * in scala.
 *
 * @param requiredProperties a Map whose keys define properties that must be bound to
 * a value, and whose values are default values. A null value in the Map means there is
 * no default value specified, so it must appear in the properties file.
 * @param optionalProperties a set of optional properties (which may not be defined).
 * @param whiskPropertiesFile a File object, the whisk.properties file, which if given contains the property values.
 */

class WhiskConfig(
    requiredProperties: Map[String, String],
    optionalProperties: Set[String] = Set(),
    propertiesFile: File = null,
    env: Map[String, String] = sys.env)(implicit val system: ActorSystem, logging: Logging)
    extends Config(requiredProperties, optionalProperties)(env) {

    /**
     * Loads the properties as specified above.
     *
     * @return a pair which is the Map defining the properties, and a boolean indicating whether validation succeeded.
     */
    override protected def getProperties() = {
        val properties = super.getProperties()
        WhiskConfig.readPropertiesFromFile(properties, Option(propertiesFile) getOrElse (WhiskConfig.whiskPropertiesFile))
        WhiskConfig.readPropertiesFromConsul(properties)
        properties
    }

    val logsDir = this(WhiskConfig.logsDir)
    val servicePort = this(WhiskConfig.servicePort)
    val dockerRegistry = this(WhiskConfig.dockerRegistry)
    val dockerEndpoint = this(WhiskConfig.dockerEndpoint)
    val selfDockerEndpoint = this(WhiskConfig.selfDockerEndpoint)
    val dockerPort = this(WhiskConfig.dockerPort)

    val dockerImagePrefix = this(WhiskConfig.dockerImagePrefix)
    val dockerImageTag = this(WhiskConfig.dockerImageTag)

    val invokerContainerNetwork = this(WhiskConfig.invokerContainerNetwork)
    val invokerContainerPolicy = if (this(WhiskConfig.invokerContainerPolicy) == "") None else Some(this(WhiskConfig.invokerContainerPolicy))
    val invokerContainerDns = if (this(WhiskConfig.invokerContainerDns) == "") Seq() else this(WhiskConfig.invokerContainerDns).split(" ").toSeq
    val invokerNumCore = this(WhiskConfig.invokerNumCore)
    val invokerCoreShare = this(WhiskConfig.invokerCoreShare)
    val invokerSerializeDockerOp = this(WhiskConfig.invokerSerializeDockerOp)
    val invokerSerializeDockerPull = this(WhiskConfig.invokerSerializeDockerPull)
    val invokerUseRunc = this(WhiskConfig.invokerUseRunc)
    val invokerUseReactivePool = this(WhiskConfig.invokerUseReactivePool)

    val wskApiHost = this(WhiskConfig.wskApiProtocol) + "://" + this(WhiskConfig.wskApiHostname) + ":" + this(WhiskConfig.wskApiPort)
    val controllerHost = this(WhiskConfig.controllerHostName) + ":" + this(WhiskConfig.controllerHostPort)
    val controllerBlackboxFraction = this.getAsDouble(WhiskConfig.controllerBlackboxFraction, 0.10)
    val loadbalancerActivationCountBeforeNextInvoker = this.getAsInt(WhiskConfig.loadbalancerActivationCountBeforeNextInvoker, 10)

    val edgeHost = this(WhiskConfig.edgeHostName) + ":" + this(WhiskConfig.edgeHostApiPort)
    val kafkaHost = this(WhiskConfig.kafkaHostName) + ":" + this(WhiskConfig.kafkaHostPort)
    val loadbalancerHost = this(WhiskConfig.loadbalancerHostName) + ":" + this(WhiskConfig.loadbalancerHostPort)

    val edgeHostName = this(WhiskConfig.edgeHostName)

    val zookeeperHost = this(WhiskConfig.zookeeperHostName) + ":" + this(WhiskConfig.zookeeperHostPort)
    val consulServer = this(WhiskConfig.consulServerHost) + ":" + this(WhiskConfig.consulPort)
    val invokerHosts = this(WhiskConfig.invokerHostsList)

    val dbProvider = this(WhiskConfig.dbProvider)
    val dbUsername = this(WhiskConfig.dbUsername)
    val dbPassword = this(WhiskConfig.dbPassword)
    val dbProtocol = this(WhiskConfig.dbProtocol)
    val dbHost = this(WhiskConfig.dbHost)
    val dbPort = this(WhiskConfig.dbPort)
    val dbWhisk = this(WhiskConfig.dbWhisk)
    val dbAuths = this(WhiskConfig.dbAuths)
    val dbActivations = this(WhiskConfig.dbActivations)
    val dbPrefix = this(WhiskConfig.dbPrefix)

    val edgeDockerEndpoint = this(WhiskConfig.edgeDockerEndpoint)
    val kafkaDockerEndpoint = this(WhiskConfig.kafkaDockerEndpoint)
    val mainDockerEndpoint = this(WhiskConfig.mainDockerEndpoint)

    val runtimesManifest = this(WhiskConfig.runtimesManifest)

    val actionInvokePerMinuteLimit = this(WhiskConfig.actionInvokePerMinuteDefaultLimit, WhiskConfig.actionInvokePerMinuteLimit)
    val actionInvokeConcurrentLimit = this(WhiskConfig.actionInvokeConcurrentDefaultLimit, WhiskConfig.actionInvokeConcurrentLimit)
    val triggerFirePerMinuteLimit = this(WhiskConfig.triggerFirePerMinuteDefaultLimit, WhiskConfig.triggerFirePerMinuteLimit)
    val actionInvokeSystemOverloadLimit = this(WhiskConfig.actionInvokeSystemOverloadDefaultLimit, WhiskConfig.actionInvokeSystemOverloadLimit)
    val actionSequenceLimit = this(WhiskConfig.actionSequenceDefaultLimit)
}

object WhiskConfig {

    private def whiskPropertiesFile: File = {
        def propfile(dir: String, recurse: Boolean = false): File =
            if (dir != null) {
                val base = new File(dir)
                val file = new File(base, "whisk.properties")
                if (file.exists())
                    file
                else if (recurse)
                    propfile(base.getParent, true)
                else null
            } else null

        val dir = sys.props.get("user.dir")
        if (dir.isDefined) {
            propfile(dir.get, true)
        } else {
            null
        }
    }

    /**
     * Reads a Map of key-value pairs from the Consul service -- store them in the
     * mutable properties object.
     */
    def readPropertiesFromConsul(properties: scala.collection.mutable.Map[String, String])(implicit system: ActorSystem, logging: Logging) = {
        //try to get consulServer prop
        val consulString = for {
            server <- properties.get(consulServerHost).filter(s => s != null && s.trim.nonEmpty)
            port <- properties.get(consulPort).filter(_ != null)
        } yield server + ":" + port

        consulString match {
            case Some(consulServer) => Try {
                logging.info(this, s"reading properties from consul at $consulServer")
                val consul = new ConsulClient(consulServer)

                val whiskProps = Await.result(consul.kv.getRecurse(ConsulKV.WhiskProps.whiskProps), 1.minute)
                properties.keys foreach { p =>
                    val kvp = ConsulKV.WhiskProps.whiskProps + "/" + p.replace('.', '_').toUpperCase
                    whiskProps.get(kvp) foreach { properties += p -> _ }
                }
            } recover {
                case ex => logging.warn(this, s"failed to read properties from consul: ${ex.getMessage}")
            }
            case _ => logging.info(this, "no consul server defined")
        }
    }

    /**
     * Reads a Map of key-value pairs from the environment (sys.env) -- store them in the
     * mutable properties object.
     */
    def readPropertiesFromFile(properties: scala.collection.mutable.Map[String, String], file: File)(implicit logging: Logging) = {
        if (file != null && file.exists) {
            logging.info(this, s"reading properties from file $file")
            for (line <- Source.fromFile(file).getLines if line.trim != "") {
                val parts = line.split('=')
                if (parts.length >= 1) {
                    val p = parts(0).trim
                    val v = if (parts.length == 2) parts(1).trim else ""
                    if (properties.contains(p)) {
                        properties += p -> v
                        logging.debug(this, s"properties file set value for $p")
                    }
                } else {
                    logging.warn(this, s"ignoring properties $line")
                }
            }
        }
    }

    def asEnvVar(key: String): String =
        if (key != null)
            key.replace('.', '_').toUpperCase
        else null

    val logsDir = "whisk.logs.dir"
    val servicePort = "port"
    val dockerRegistry = "docker.registry"
    val dockerPort = "docker.port"

    val dockerEndpoint = "main.docker.endpoint"
    val selfDockerEndpoint = "self.docker.endpoint"

    val dbProvider = "db.provider"
    val dbProtocol = "db.protocol"
    val dbHost = "db.host"
    val dbPort = "db.port"
    val dbUsername = "db.username"
    val dbPassword = "db.password"
    val dbWhisk = "db.whisk.actions"
    val dbAuths = "db.whisk.auths"
    val dbPrefix = "db.prefix"
    val dbActivations = "db.whisk.activations"

    // these are not private because they are needed
    // in the invoker (they are part of the environment
    // passed to the user container)
    val edgeHostName = "edge.host"
    val whiskVersionDate = "whisk.version.date"
    val whiskVersionBuildno = "whisk.version.buildno"

    val whiskVersion = Map(whiskVersionDate -> null, whiskVersionBuildno -> null)

    val dockerImagePrefix = "docker.image.prefix"
    val dockerImageTag = "docker.image.tag"

    val invokerContainerNetwork = "invoker.container.network"
    val invokerContainerPolicy = "invoker.container.policy"
    val invokerContainerDns = "invoker.container.dns"
    val invokerNumCore = "invoker.numcore"
    val invokerCoreShare = "invoker.coreshare"
    val invokerSerializeDockerOp = "invoker.serializeDockerOp"
    val invokerSerializeDockerPull = "invoker.serializeDockerPull"
    val invokerUseRunc = "invoker.useRunc"
    val invokerUseReactivePool = "invoker.useReactivePool"

    val wskApiProtocol = "whisk.api.host.proto"
    val wskApiPort = "whisk.api.host.port"
    val wskApiHostname = "whisk.api.host.name"
    val wskApiHost = Map(wskApiProtocol -> "https", wskApiPort -> 443.toString, wskApiHostname -> null)

    val edgeDockerEndpoint = "edge.docker.endpoint"
    val kafkaDockerEndpoint = "kafka.docker.endpoint"
    val mainDockerEndpoint = "main.docker.endpoint"

    private val controllerHostName = "controller.host"
    private val controllerHostPort = "controller.host.port"
    private val controllerBlackboxFraction = "controller.blackboxFraction"

    val loadbalancerActivationCountBeforeNextInvoker = "loadbalancer.activationCountBeforeNextInvoker"

    val kafkaHostName = "kafka.host"
    val loadbalancerHostName = "loadbalancer.host"
    private val zookeeperHostName = "zookeeper.host"

    private val edgeHostApiPort = "edge.host.apiport"
    val kafkaHostPort = "kafka.host.port"
    private val loadbalancerHostPort = "loadbalancer.host.port"
    private val zookeeperHostPort = "zookeeper.host.port"

    val consulServerHost = "consulserver.host"
    val consulPort = "consul.host.port4"
    val invokerHostsList = "invoker.hosts"

    val edgeHost = Map(edgeHostName -> null, edgeHostApiPort -> null)
    val consulServer = Map(consulServerHost -> null, consulPort -> null)
    val invokerHosts = Map(invokerHostsList -> null)
    val kafkaHost = Map(kafkaHostName -> null, kafkaHostPort -> null)
    val controllerHost = Map(controllerHostName -> null, controllerHostPort -> null)
    val loadbalancerHost = Map(loadbalancerHostName -> null, loadbalancerHostPort -> null)

    val runtimesManifest = "runtimes.manifest"

    val actionInvokePerMinuteDefaultLimit = "defaultLimits.actions.invokes.perMinute"
    val actionInvokeConcurrentDefaultLimit = "defaultLimits.actions.invokes.concurrent"
    val actionInvokeSystemOverloadDefaultLimit = "defaultLimits.actions.invokes.concurrentInSystem"
    val triggerFirePerMinuteDefaultLimit = "defaultLimits.triggers.fires.perMinute"
    val actionSequenceDefaultLimit = "defaultLimits.actions.sequence.maxLength"
    val actionInvokePerMinuteLimit = "limits.actions.invokes.perMinute"
    val actionInvokeConcurrentLimit = "limits.actions.invokes.concurrent"
    val actionInvokeSystemOverloadLimit = "limits.actions.invokes.concurrentInSystem"
    val triggerFirePerMinuteLimit = "limits.triggers.fires.perMinute"
}
