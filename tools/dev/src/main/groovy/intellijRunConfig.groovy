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
import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import org.apache.commons.lang3.SystemUtils

assert args : "Expecting the OpenWhisk home directory to passed"
owHome = args[0]

//Launch config template
def configTemplate = '''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="${name}" type="Application" factoryName="Application">
    <extension name="coverage" enabled="false" merge="false" sample_coverage="true" runner="idea" />
    <option name="MAIN_CLASS_NAME" value="$main" />
    <option name="VM_PARAMETERS" value="$sysProps" />
    <option name="PROGRAM_PARAMETERS" value="$programParams" />
    <option name="WORKING_DIRECTORY" value="$workingDir" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="false" />
    <option name="ALTERNATIVE_JRE_PATH" />
    <option name="ENABLE_SWING_INSPECTOR" value="false" />
    <option name="ENV_VARIABLES" />
    <option name="PASS_PARENT_ENVS" value="true" />
    <module name="openwhisk.core.${type}.main" />
    <envs>
      <% env.each { k,v -> %><env name="$k" value="$v" />
      <% } %>
    </envs>
    <method />
  </configuration>
</component>
'''

def meta = [
        controller : [main:"org.apache.openwhisk.core.controller.Controller"],
        invoker : [main:"org.apache.openwhisk.core.invoker.Invoker"]
]

//Get names of all running containers
def containerNames = 'docker ps --format {{.Names}}'.execute().text.split("\n")

config = getConfig()

Map controllerEnv = null
Map invokerEnv = null

containerNames.each{cn ->

    //Inspect the specific container
    def inspectResult = "docker inspect $cn".execute().text
    def json = new JsonSlurper().parseText(inspectResult)

    def imageName = json[0].'Config'.'Image'
    if (imageName.contains("controller") || imageName.contains("invoker")){
        // pre-configure the local ports for controller and invoker
        def mappedPort = imageName.contains("controller") ? '10001' : '12001'

        def envBaseMap = getEnvMap(json[0].'Config'.'Env')
        String type
        if (imageName.contains("controller")){
            type = "controller"
            controllerEnv = envBaseMap
        } else {
            type = "invoker"
            invokerEnv = envBaseMap
        }

        def overrides = [
                'PORT' : mappedPort,
                'WHISK_LOGS_DIR' : "$owHome/core/$type/build/tmp"
        ]

        def envMap = getEnv(envBaseMap, type, overrides)

        //Prepare system properties
        def sysProps = getSysProps(envMap,type)
        // disable log collection. See more at: https://github.com/apache/openwhisk/issues/3195
        sysProps += " -Dwhisk.log-limit.max=0 -Dwhisk.log-limit.std=0"
        // disable https protocol for controller and invoker
        sysProps = sysProps.replaceAll("protocol=https", "protocol=http")
        if (SystemUtils.IS_OS_MAC){
            sysProps = sysProps.replaceAll("use-runc=True", "use-runc=False")
            sysProps += " -Dwhisk.spi.ContainerFactoryProvider=org.apache.openwhisk.core.containerpool.docker.DockerForMacContainerFactoryProvider"
        }

        def templateBinding = [
                main: meta[type].main,
                type:type,
                name:cn,
                env: encodeForXML(envMap),
                sysProps : sysProps,
                USER_HOME : '$USER_HOME$',
                workingDir : getWorkDir(type),
                programParams: imageName.contains("controller") ? '0' : '--id  0'
        ]

        def engine = new SimpleTemplateEngine()
        def template = engine.createTemplate(configTemplate).make(templateBinding)

        def launchFile = new File("$owHome/.idea/runConfigurations/${cn}.xml")
        launchFile.parentFile.mkdirs()
        launchFile.text = template
        println "Created ${launchFile.absolutePath}"
    }
}

/**
 * Computes the env values which are common and then specific to controller and invoker
 * and dumps them to a file. This can be used for docker-compose
 */
if (controllerEnv != null && invokerEnv != null){
    Set<String> commonKeys = controllerEnv.keySet().intersect(invokerEnv.keySet())

    SortedMap commonEnv = new TreeMap()
    SortedMap controllerSpecificEnv = new TreeMap(controllerEnv)
    SortedMap invokerSpecificEnv = new TreeMap(invokerEnv)
    commonKeys.each{ key ->
        if (controllerEnv[key] == invokerEnv[key]){
            commonEnv[key] = controllerEnv[key]
            controllerSpecificEnv.remove(key)
            invokerSpecificEnv.remove(key)
        }
    }

    copyEnvToFile(commonEnv,"whisk-common.env")
    copyEnvToFile(controllerSpecificEnv,"whisk-controller.env")
    copyEnvToFile(invokerSpecificEnv,"whisk-invoker.env")
}

def copyEnvToFile(SortedMap envMap,String envFileName){
    File envFile = new File(getEnvFileDir(), envFileName)
    envFile.withPrintWriter {pw ->
        envMap.each{k,v ->
            pw.println("$k=$v")

        }
    }
    println "Wrote env to ${envFile.absolutePath}"
}

private File getEnvFileDir() {
    File dir = new File(new File("build"), "env")
    dir.mkdirs()
    return dir
}

/**
 * Reads config from intellij-run-config.groovy file
 */
def getConfig(){
    def configFile = new File(owHome, 'intellij-run-config.groovy')
    def config = configFile.exists() ? new ConfigSlurper().parse(configFile.text) : new ConfigObject()
    if (configFile.exists()) {
        println "Reading config from ${configFile.absolutePath}"
    }
    config
}

def getWorkDir(String type){
    def dir = config[type].workingDir
    if (dir){
        File f = new File(dir)
        if (!f.exists()) {
            f.mkdirs()
        }
        dir
    }else {
        'file://$MODULE_DIR$'
    }
}

def getSysProps(def envMap, String type){
    def props = config[type].props
    def sysProps = transformEnv(envMap)
    sysProps.putAll(props)
    sysProps.collect{k,v -> "-D$k='$v'"}.join(' ').replace('"','').replace('\'','')
}

//Implements the logic from transformEnvironment.sh
//to ensure comparability as sed -r is not supported on Mac
def transformEnv(Map<String, String> envMap){
    def transformedMap = [:]
    envMap.each{String k,String v ->
        if (!k.startsWith("CONFIG_") || v.isEmpty()) return
        k = k.substring("CONFIG_".length())
        def parts = k.split("\\_")
        def transformedKey = parts.collect {p ->
            if (Character.isUpperCase(p[0] as char)){
                // if the current part starts with an uppercase letter (is PascalCased)
                // leave it alone
                return p
            } else {
                // rewrite camelCased to kebab-cased
                return p.replaceAll(/([a-z0-9])([A-Z])/,/$1-$2/).toLowerCase()
            }
        }

        //Resolve values which again refer to env variables
        if (v.startsWith('$')) {
            def valueAsKey = v.substring(1)
            if (envMap.containsKey(valueAsKey)){
                v = envMap.get(valueAsKey)
            }
        }
        transformedMap[transformedKey.join('.')] = v
    }
    return transformedMap
}

/**
 * Inspect command from docker returns the environment variables as list of string of form key=value
 * This method converts it to map and add provided overrides with overrides from config
 */
def getEnv(Map envMap, String type, Map overrides){
    def ignoredKeys = ['PATH','JAVA_HOME','JAVA_VERSION','JAVA_TOOL_OPTIONS']
    def overridesFromConfig = config[type].env
    Map sortedMap = new TreeMap(envMap)
    sortedMap.putAll(overrides)

    //Config override come last
    sortedMap.putAll(overridesFromConfig)

    //Remove ignored keys like PATH which should be inherited
    ignoredKeys.each {sortedMap.remove(it)}
    sortedMap
}

def getEnvMap(def env){
    def envMap = env.collectEntries {String e ->
        def eqIndex = e.indexOf('=')
        def k = e.substring(0, eqIndex)
        def v = e.substring(eqIndex + 1)
        [(k):v]
    }
    def sortedMap = new TreeMap()
    sortedMap.putAll(envMap)
    Collections.unmodifiableSortedMap(sortedMap)
}

def getEnvAsList(Map envMap){
    envMap.collect{k,v -> "$k=$v"}
}

def encodeForXML(Map map){
    map.collectEntries {k,v -> [(k): v.replace('"', '&quot;')]}
}
