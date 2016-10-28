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
import whisk.common.Config.Settings
import whisk.common.ConsulClient
import whisk.common.ConsulKV
import whisk.common.Logging

/**
 * A set of properties which might be needed to run a whisk microservice implemented
 * in scala.
 *
 * @param requiredProperties a Map whose keys define properties that must be bound to
 * a value, and whose values are default values.   A null value in the Map means there is
 * no default value specified, so it must appear in the properties file
 * @param whiskPropertiesFile a File object, the whisk.properties file
 */

class WhiskConfig(
    requiredProperties: Map[String, String],
    optionalProperties: Set[String] = Set(),
    propertiesFile: File = null)(implicit val system: ActorSystem)
    extends Config(requiredProperties, optionalProperties) {

    /**
     * Loads the properties as specified above.
     *
     * @return a pair which is the Map defining the properties, and a boolean indicating whether validation succeeded.
     */
    override protected def getProperties(): (Map[String, String], Boolean) = {
        val properties = scala.collection.mutable.Map[String, String]() ++= requiredProperties
        Config.readPropertiesFromEnvironment(properties)
        WhiskConfig.readPropertiesFromFile(properties, Option(propertiesFile) getOrElse (WhiskConfig.whiskPropertiesFile))
        WhiskConfig.readPropertiesFromConsul(properties, optionalProperties)
        (properties.toMap, Config.validateProperties(requiredProperties, properties))
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
    val invokerNumCore = this(WhiskConfig.invokerNumCore)
    val invokerCoreShare = this(WhiskConfig.invokerCoreShare)
    val invokerSerializeDockerOp = this(WhiskConfig.invokerSerializeDockerOp)
    val invokerSerializeDockerPull = this(WhiskConfig.invokerSerializeDockerPull)

    val controllerHost = this(WhiskConfig.controllerHostName) + ":" + this(WhiskConfig.controllerHostPort)
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

    val entitlementHost = this(WhiskConfig.entitlementHostName) + ":" + this(WhiskConfig.entitlementHostPort)
    val routerHost = this(WhiskConfig.routerHost)
    val cliApiHost = this(WhiskConfig.cliApiHost)

    val edgeDockerEndpoint = this(WhiskConfig.edgeDockerEndpoint)
    val kafkaDockerEndpoint = this(WhiskConfig.kafkaDockerEndpoint)
    val mainDockerEndpoint = this(WhiskConfig.mainDockerEndpoint)

    val actionInvokePerMinuteLimit = this(WhiskConfig.actionInvokePerMinuteDefaultLimit, WhiskConfig.actionInvokePerMinuteLimit)
    val actionInvokeConcurrentLimit = this(WhiskConfig.actionInvokeConcurrentDefaultLimit, WhiskConfig.actionInvokeConcurrentLimit)
    val triggerFirePerMinuteLimit = this(WhiskConfig.triggerFirePerMinuteDefaultLimit, WhiskConfig.triggerFirePerMinuteLimit)
    val actionInvokeSystemOverloadLimit = this(WhiskConfig.actionInvokeSystemOverloadDefaultLimit, WhiskConfig.actionInvokeSystemOverloadLimit)
    val actionSequenceLimit = this(WhiskConfig.actionSequenceDefaultLimit)
}

object WhiskConfig extends Logging {

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
    def readPropertiesFromConsul(properties: Settings, optionalProperties: Set[String])(implicit system: ActorSystem) = {
        //try to get consulServer prop
        val consulString = for {
            server <- properties.get(consulServerHost)
            port <- properties.get(consulPort)
        } yield server + ":" + port

        consulString match {
            case Some(consulServer) => Try {
                info(this, s"reading properties from consul at $consulServer")
                val consul = new ConsulClient(consulServer)

                val whiskProps = Await.result(consul.kv.getRecurse(ConsulKV.WhiskProps.whiskProps), 1.minute)
                (properties.keys ++ optionalProperties) foreach { p =>
                    val kvp = ConsulKV.WhiskProps.whiskProps + "/" + p.replace('.', '_').toUpperCase
                    whiskProps.get(kvp) foreach { properties += p -> _ }
                }
            }
            case _ => info(this, "no consul server defined")
        }
    }

    /**
     * Reads a Map of key-value pairs from the environment (sys.env) -- store them in the
     * mutable properties object.
     */
    def readPropertiesFromFile(properties: Settings, file: File) = {
        if (file != null && file.exists) {
            info(this, s"reading properties from file $file")
            for (line <- Source.fromFile(file).getLines if line.trim != "") {
                val parts = line.split('=')
                if (parts.length >= 1) {
                    val p = parts(0).trim
                    val v = if (parts.length == 2) parts(1).trim else ""
                    properties += p -> v
                    info(this, s"properties file set value for $p")
                } else {
                    warn(this, s"ignoring properties $line")
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
    val dbActivations = dbWhisk // map to the same db for now

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
    val invokerNumCore = "invoker.numcore"
    val invokerCoreShare = "invoker.coreshare"
    val invokerSerializeDockerOp = "invoker.serializeDockerOp"
    val invokerSerializeDockerPull = "invoker.serializeDockerPull"

    val routerHost = "router.host"
    val cliApiHost = "cli.api.host"

    val edgeDockerEndpoint = "edge.docker.endpoint"
    val kafkaDockerEndpoint = "kafka.docker.endpoint"
    val mainDockerEndpoint = "main.docker.endpoint"

    private val controllerHostName = "controller.host"
    val kafkaHostName = "kafka.host"
    val loadbalancerHostName = "loadbalancer.host"
    private val zookeeperHostName = "zookeeper.host"

    private val controllerHostPort = "controller.host.port"
    private val edgeHostApiPort = "edge.host.apiport"
    val kafkaHostPort = "kafka.host.port"
    private val loadbalancerHostPort = "loadbalancer.host.port"
    private val zookeeperHostPort = "zookeeper.host.port"

    val consulServerHost = "consulserver.host"
    val consulPort = "consul.host.port4"
    val invokerHostsList = "invoker.hosts"

    private val entitlementHostName = "entitlement.host"
    private val entitlementHostPort = "entitlement.host.port"

    val edgeHost = Map(edgeHostName -> null, edgeHostApiPort -> null)
    val consulServer = Map(consulServerHost -> null, consulPort -> null)
    val invokerHosts = Map(invokerHostsList -> null)
    val kafkaHost = Map(kafkaHostName -> null, kafkaHostPort -> null)
    val controllerHost = Map(controllerHostName -> null, controllerHostPort -> null)
    val loadbalancerHost = Map(loadbalancerHostName -> null, loadbalancerHostPort -> null)

    // use empty string as default for entitlement host as this is an optional service
    // and the way to prevent the configuration checker from failing is to provide a value;
    // an empty string is permitted but null is not
    val entitlementHost = Map(entitlementHostName -> "", entitlementHostPort -> "")

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
