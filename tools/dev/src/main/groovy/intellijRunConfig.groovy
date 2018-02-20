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

assert args : "Expecting the OpenWhisk home directory to passed"
owHome = args[0]

//Launch config template
def configTemplate = '''<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="${name}" type="Application" factoryName="Application">
    <extension name="coverage" enabled="false" merge="false" sample_coverage="true" runner="idea" />
    <option name="MAIN_CLASS_NAME" value="$main" />
    <option name="VM_PARAMETERS" value="$sysProps" />
    <option name="PROGRAM_PARAMETERS" value="0" />
    <option name="WORKING_DIRECTORY" value="$workingDir" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="false" />
    <option name="ALTERNATIVE_JRE_PATH" />
    <option name="ENABLE_SWING_INSPECTOR" value="false" />
    <option name="ENV_VARIABLES" />
    <option name="PASS_PARENT_ENVS" value="true" />
    <module name="${type}_main" />
    <envs>
      <% env.each { k,v -> %><env name="$k" value="$v" />
      <% } %>
    </envs>
    <method />
  </configuration>
</component>
'''

def meta = [
        controller : [main:"whisk.core.controller.Controller"],
        invoker : [main:"whisk.core.invoker.Invoker"]
]

//Get names of all running containers
def containerNames = 'docker ps --format {{.Names}}'.execute().text.split("\n")

//Command for transformEnvironment script
transformEnvScript = "/bin/bash $owHome/common/scala/transformEnvironment.sh"

config = getConfig()

containerNames.each{cn ->
    //Inspect the specific container
    def inspectResult = "docker inspect $cn".execute().text
    def json = new JsonSlurper().parseText(inspectResult)

    def imageName = json[0].'Config'.'Image'
    if (imageName.contains("controller") || imageName.contains("invoker")){
        String type = imageName.contains("controller") ? "controller" : "invoker"

        def mappedPort = json.'NetworkSettings'.'Ports'.'8080/tcp'[0][0].'HostPort'

        def env = json[0].'Config'.'Env'
        def overrides = [
                'PORT' : mappedPort,
                'WHISK_LOGS_DIR' : "$owHome/core/$type/build/tmp"
        ]
        def envMap = getEnv(env, type, overrides)

        //Prepare system properties
        def sysProps = getSysProps(envMap,type)

        def templateBinding = [
                main: meta[type].main,
                type:type,
                name:cn,
                env: encodeForXML(envMap),
                sysProps : sysProps,
                USER_HOME : '$USER_HOME$',
                workingDir : getWorkDir(type)
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
    def sysProps = transformEnvScript.execute(getEnvAsList(envMap), new File(".")).text
    sysProps += props.collect{k,v -> "-D$k='$v'"}.join(' ')
    sysProps.replace('\'','')
}

/**
 * Inspect command from docker returns the environment variables as list of string of form key=value
 * This method converts it to map and add provided overrides with overrides from config
 */
def getEnv(def env, String type, Map overrides){
    def ignoredKeys = ['PATH']
    def overridesFromConfig = config[type].env
    Map envMap = env.collectEntries {String e ->
        def eqIndex = e.indexOf('=')
        def k = e.substring(0, eqIndex)
        def v = e.substring(eqIndex + 1)
        [(k):v]
    }

    envMap.putAll(overrides)

    //Config override come last
    envMap.putAll(overridesFromConfig)

    //Remove ignored keys like PATH which should be inherited
    ignoredKeys.each {envMap.remove(it)}

    //Returned sorted view of env
    new TreeMap(envMap)
}

def getEnvAsList(Map envMap){
    envMap.collect{k,v -> "$k=$v"}
}

def encodeForXML(Map map){
    map.collectEntries {k,v -> [(k): v.replace('"', '&quot;')]}
}
