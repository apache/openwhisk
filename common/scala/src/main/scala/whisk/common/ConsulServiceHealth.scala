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

package whisk.common
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.core.WhiskConfig.consulServices
import whisk.core.WhiskConfig.invokerHosts

object ConsulServiceHealth extends App {

    def checkHealth(consulServer: String) = {
        val requiredProperties = consulServices ++ invokerHosts
        val config = new WhiskConfig(requiredProperties)
        val consul = new ConsulServiceCheck(consulServer)

        if (config.isValid) {
            // retrieve set of services to check and invoker0 to the list which exists in all deployments
            // TODO: add invokers specific to a deployment
            val servicesToCheck = config.consulServices.split(",").toSet + "invoker0"
            val passing = consul.getAllPassing()
            val critical = consul.getAllCritical()
            val warning = consul.getAllWarning()
            val nonpassing = servicesToCheck -- passing
            val notfound = nonpassing -- critical -- warning
            // check that all known services are passing
            if (nonpassing.isEmpty) {
                println("All passing")
            }
            if (!critical.isEmpty) {
                print("Critical: ")
                critical.foreach(service => print(service + " "))
                println
            }
            if (!warning.isEmpty) {
                print("Warning: ")
                warning.foreach(service => print(service + " "))
                println
            }
            if (!notfound.isEmpty) {
                print("Unknown: ")
                notfound.foreach(service => print(service + " "))
                println
            }
        } else {
            println("No valid config")
        }
    }

    checkHealth(args(0))
}
