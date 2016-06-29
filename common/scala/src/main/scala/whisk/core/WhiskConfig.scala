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

import whisk.common.Config
import java.io.File
import whisk.common.Crypt

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
    requiredProperties: Map[String, String])
    extends Config(requiredProperties, WhiskConfig.whiskPropertiesFile) {

    val logsDir = this(WhiskConfig.logsDir)
    val servicePort = this(WhiskConfig.servicePort)
    val dockerRegistry = this(WhiskConfig.dockerRegistry)
    val dockerEndpoint = this(WhiskConfig.dockerEndpoint)
    val selfDockerEndpoint = this(WhiskConfig.selfDockerEndpoint)
    val dockerPort = this(WhiskConfig.dockerPort)

    val dockerImageTag = this(WhiskConfig.dockerImageTag)

    val invokerContainerNetwork = this(WhiskConfig.invokerContainerNetwork)

    val controllerHost = this(WhiskConfig.controllerHostName) + ":" + this(WhiskConfig.controllerHostPort)
    val edgeHost = this(WhiskConfig.edgeHostName) + ":" + this(WhiskConfig.edgeHostApiPort)
    val elkHost = this(WhiskConfig.elkHostName) + ":" + this(WhiskConfig.elkHostPort)
    val kafkaHost = this(WhiskConfig.kafkaHostName) + ":" + this(WhiskConfig.kafkaHostPort)
    val kafkaPartitions = this(WhiskConfig.kafkaPartitions)
    val loadbalancerHost = this(WhiskConfig.loadbalancerHostName) + ":" + this(WhiskConfig.loadbalancerHostPort)
    val messagehubtriggerHost = this(WhiskConfig.messagehubtriggerHostName) + ":" + this(WhiskConfig.messagehubtriggerHostPort)

    val edgeHostName = this(WhiskConfig.edgeHostName)

    val monitorHost = this(WhiskConfig.monitorHostName) + ":" + this(WhiskConfig.monitorHostPort)
    val zookeeperHost = this(WhiskConfig.zookeeperHostName) + ":" + this(WhiskConfig.zookeeperHostPort)
    val consulServer = this(WhiskConfig.consulServerHost) + ":" + this(WhiskConfig.consulPort)
    val consulServices = this(WhiskConfig.consulServiceList)
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
    val dbCipher = this(WhiskConfig.dbCipher)
    val dbPrefix = this(WhiskConfig.dbPrefix)
    val authKey = this(WhiskConfig.authKey)

    val entitlementHost = this(WhiskConfig.entitlementHostName) + ":" + this(WhiskConfig.entitlementHostPort)
    val routerHost = this(WhiskConfig.routerHost)
    val cliApiHost = this(WhiskConfig.cliApiHost)

    val edgeDockerEndpoint = this(WhiskConfig.edgeDockerEndpoint)
    val kafkaDockerEndpoint = this(WhiskConfig.kafkaDockerEndpoint)
    val mainDockerEndpoint = this(WhiskConfig.mainDockerEndpoint)
    val routerDockerEndpoint = this(WhiskConfig.routerDockerEndpoint)
    val elkDockerEndpoint = this(WhiskConfig.elkDockerEndpoint)
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
    val kafkaPartitions = "kafka.numpartitions"

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

    val dbCipher = "datastore.key"
    val authKey = "auth.key"

    // these are not private because they are needed
    // in the invoker (they are part of the environment
    // passed to the user container)
    val edgeHostName = "edge.host"
    val whiskVersionDate = "whisk.version.date"
    val whiskVersionBuildno = "whisk.version.buildno"

    val whiskVersion = Map(whiskVersionDate -> null, whiskVersionBuildno -> null)

    val dockerImageTag = "docker.image.tag"

    val invokerContainerNetwork = "invoker.container.network"

    val routerHost = "router.host"
    val cliApiHost = "cli.api.host"

    val edgeDockerEndpoint = "edge.docker.endpoint"
    val kafkaDockerEndpoint = "kafka.docker.endpoint"
    val mainDockerEndpoint = "main.docker.endpoint"
    val routerDockerEndpoint = "router.docker.endpoint"
    val elkDockerEndpoint = "elk.docker.endpoint"

    private val controllerHostName = "controller.host"
    private val elkHostName = "elk.host"
    private val kafkaHostName = "kafka.host"
    private val loadbalancerHostName = "loadbalancer.host"
    private val messagehubtriggerHostName = "messagehubtrigger.host"
    private val monitorHostName = "monitor.host"
    private val zookeeperHostName = "zookeeper.host"

    private val controllerHostPort = "controller.host.port"
    private val edgeHostApiPort = "edge.host.apiport"
    private val elkHostPort = "elk.host.port"
    private val kafkaHostPort = "kafka.host.port"
    private val loadbalancerHostPort = "loadbalancer.host.port"
    private val messagehubtriggerHostPort = "messagehubtrigger.host.port"
    private val monitorHostPort = "monitor.host.port"
    private val zookeeperHostPort = "zookeeper.host.port"

    private val consulServerHost = "consulserver.host"
    private val consulPort = "consul.host.port4"
    private val consulServiceList = "consul.servicelist"
    private val invokerHostsList = "invoker.hosts"

    private val entitlementHostName = "entitlement.host"
    private val entitlementHostPort = "entitlement.host.port"

    val edgeHost = Map(edgeHostName -> null, edgeHostApiPort -> null)
    val consulServer = Map(consulServerHost -> null, consulPort -> null)
    val consulServices = Map(consulServiceList -> null)
    val invokerHosts = Map(invokerHostsList -> null)
    val elkHost = Map(elkHostName -> null, elkHostPort -> null)
    val kafkaHost = Map(kafkaHostName -> null, kafkaHostPort -> null)
    val controllerHost = Map(controllerHostName -> null, controllerHostPort -> null)
    val loadbalancerHost = Map(loadbalancerHostName -> null, loadbalancerHostPort -> null)
    val messagehubtriggerHost = Map(messagehubtriggerHostName -> null, messagehubtriggerHostPort -> null)
    val monitorHost = Map(monitorHostName -> null, monitorHostPort -> null)
    val zookeeperHost = Map(zookeeperHostName -> null, zookeeperHostPort -> null)

    // use empty string as default for entitlement host as this is an optional service
    // and the way to prevent the configuration checker from failing is to provide a value;
    // an empty string is permitted but null is not
    val entitlementHost = Map(entitlementHostName -> "", entitlementHostPort -> "")
}
