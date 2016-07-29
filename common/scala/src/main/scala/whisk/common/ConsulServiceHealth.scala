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

object ConsulServiceHealth extends App {

    def checkHealth(consulServer: String, consulServices: String) = {
        val consul = new ConsulServiceCheck(consulServer)

        // retrieve set of services to check and invoker0 to the list which exists in all deployments
        // TODO: add invokers specific to a deployment
        val servicesToCheck = consulServices.split(",").toSet + "invoker0"
        val passing = consul.getAllPassing()
        val critical = consul.getAllCritical()
        val warning = consul.getAllWarning()
        val nonpassing = servicesToCheck -- passing
        val notfound = nonpassing -- critical -- warning
        // check that all known services are passing
        if (nonpassing.isEmpty) {
            println("All passing")
        }
        if (critical.nonEmpty) {
            print("Critical: ")
            critical.foreach(service => print(service + " "))
            println
        }
        if (warning.nonEmpty) {
            print("Warning: ")
            warning.foreach(service => print(service + " "))
            println
        }
        if (notfound.nonEmpty) {
            print("Unknown: ")
            notfound.foreach(service => print(service + " "))
            println
        }
    }

    checkHealth(args(0), args(1))
}
