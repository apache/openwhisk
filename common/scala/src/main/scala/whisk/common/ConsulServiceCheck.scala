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

import spray.json.JsString
import spray.json.pimpString
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import org.apache.jute.compiler.JString
import spray.json.JsNull
import scala.util.Try
import spray.json.JsArray
import spray.json.JsObject
import scala.language.postfixOps

/**
 * Interface with consul receives the host where a consul agent is running
 */
class ConsulServiceCheck(agent: String) {

    def getAllServices(): Set[String] = {
        val servicesRes = consulAgent.doget(servicesEndpoint, Map(), 10000, true)._2
        val services = Try { new String(servicesRes, "utf-8").parseJson.asJsObject } getOrElse JsObject()
        services.fields.keySet
    }

    def getAllPassing() = getAllByState(State.Passing)
    def getAllCritical() = getAllByState(State.Critical)
    def getAllWarning() = getAllByState(State.Warning)
    def getAllAnyState() = getAllByState(State.Any)

    override def finalize() = {
        /** Closes HTTP connection to consul. */
        consulClient.close()
    }

    private def getAllByState(state: State.Value): List[String] = {
        val passing = Try {
            val response = consulAgent.doget(stateEndpoint + state.toString(), Map(), 10000, true)._2
            val asString = new String(response, "utf-8")
            asString.parseJson
        } getOrElse JsArray()

        passing match {
            case JsArray(services) =>
                services flatMap {
                    case JsObject(service) =>
                        val JsString(name) = service("ServiceName")
                        Some(name)
                    case _ => None
                } toList
            case _ => List()
        }
    }

    private val consulClient = HttpUtils.makeHttpClient(30000, true)
    private val consulAgent = new HttpUtils(consulClient, agent)
    private val servicesEndpoint = "/v1/catalog/services"
    private val stateEndpoint = "/v1/health/state/"
}

object State extends Enumeration {
    type State = Value
    val Passing = Value("passing")
    val Critical = Value("critical")
    val Warning = Value("warning")
    val Any = Value("any")
}

object ConsulServiceCheck extends App {
    def requiredProperties = consulServer

    private val config = new WhiskConfig(requiredProperties)

    if (config.isValid) {
        val consul = new ConsulServiceCheck(config.consulServer)
        val allServices = consul.getAllServices()
        val allPassing = consul.getAllPassing()
        val any = consul.getAllAnyState()
        allServices foreach { println(_) }
        allPassing foreach (println)
        any foreach (println)
    } else {
        println("No valid configuration")
    }
}
