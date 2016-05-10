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

import scala.language.postfixOps
import scala.util.Try

import org.apache.commons.codec.binary.Base64

import spray.json.JsArray
import spray.json.JsNull
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.pimpString

/**
 * See https://www.consul.io/intro/getting-started/kv.html
 *
 * Set up a Consul KV interface at the given agent address.
 */
class ConsulKV(agent: String) {

    private val consulClient = HttpUtils.makeHttpClient(30000, true)
    private val consulAgent = new HttpUtils(consulClient, agent)
    private val kvEndpoint = "/v1/kv/"

    override def finalize() = {
        /** Closes HTTP connection to consul. */
        consulClient.close()
    }

    /**
     * Performs the given put, adding a new entry or over-writing an existing one.
     */
    def put(key: String, value: JsValue) = {
        val endpoint = kvEndpoint + key
        consulAgent.doput(endpoint, value)
    }

    /**
     * Retrieves the given value.  If the key is absent or the entry is ill-formed, JsNull is returned.
     */
    def get(key: String): JsValue = {
        val endpoint = kvEndpoint + key
        val response = new String(consulAgent.doget(endpoint, Map("raw" -> "true"))._2)
        Try { response.parseJson } getOrElse JsNull
    }

    def delete(key: String) = {
        val endpoint = kvEndpoint + key
        consulAgent.dodelete(endpoint)
    }

    def getRecurse(key: String): Map[String, JsValue] = {
        val endpoint = kvEndpoint + key
        val response = new String(consulAgent.doget(endpoint, Map("recurse" -> "true"))._2)
        // Typical entry: {"CreateIndex":97,"ModifyIndex":97,"Key":"web/key1","Flags":0,"Value":"dGVzdA=="}
        Try { response.parseJson } getOrElse JsNull match {
            case JsArray(entries) =>
                entries flatMap {
                    case (entry: JsObject) => entry.getFields("Key", "Value") match {
                        case Seq(JsString(k), JsString(v)) => {
                            val decoded = decodeBase64(v)
                            val parsedValue = Try { decoded.parseJson } getOrElse JsString(decoded)
                            Some(k, parsedValue)
                        }
                        case Seq(JsString(k), JsNull) =>
                            Some(k, JsString(""))
                        case _ =>
                            Some("malformedEntry", entry) // Unless Consul is messed up, this should not happen
                    }
                    case _ => None
                } toMap
            case _ => Map()
        }
    }

    /**
     * Retrieves all keys under a particular path.
     * To retrieve all values, use the empty string.
     * Currently, we don't return the value here because of base64 encoding.
     * Perhaps we should do the conversion locally instead of going back to KV.
     */
    def getKeys(key: String): List[String] = {
        val endpoint = kvEndpoint + key
        val response = new String(consulAgent.doget(endpoint, Map("recurse" -> "true"))._2)
        // Typical entry: {"CreateIndex":97,"ModifyIndex":97,"Key":"web/key1","Flags":0,"Value":"dGVzdA=="}
        Try { response.parseJson } getOrElse JsNull match {
            case JsArray(entries) =>
                entries map {
                    case (entry: JsObject) => entry.getFields("Key") match {
                        case Seq(JsString(k)) => k
                        case _                => "malformed_key" // Unless Consul is messed up, this should not happen
                    }
                    case _ => "malformed_key" // Unless Consul is messed up, this should not happen
                } toList
            case _ => List()
        }
    }

    /**
     * Converts base64 back to UTF-8
     */
    private def decodeBase64(str: String): String = {
        val decoded = Base64.decodeBase64(str)
        new String(decoded, "UTF-8")
    }
}

object ConsulKV {

    object InvokerKeys {
        // All invoker written information written here.
        // Underneath this, each invoker has its own path.
        val allInvokers = "invokers" // we store a small amount of data here
        val allInvokersData = "invokersData" // we store large amounts of data here
        def instancePath(instance: Int) = s"${allInvokers}/invoker${instance}"
        def instanceDataPath(instance: Int) = s"${allInvokersData}/invoker${instance}"

        // Invokers store the hostname they are running on here.
        def hostname(instance: Int) = s"${instancePath(instance)}/hostname"

        // Invokers store when they start here.
        def start(instance: Int) = s"${instancePath(instance)}/start"

        // Invokers store how many activations they have processed here.
        def activationCount(instance: Int) = s"${instancePath(instance)}/activationCount"

        // Invokers store how many activations they have processed per user here.
        private val userActivationCountKey = "userActivationCount"
        def userActivationCount(instance: Int) = s"${instanceDataPath(instance)}/${userActivationCountKey}"

        // Invokers store their most recent check in time here
        private val statusKey = "status"
        def status(instance: Int) = s"${instancePath(instance)}/${statusKey}"
    }

    // All load balancer written information under here.
    object LoadBalancerKeys {
        val component = "loadBalancer"
        val hostnameKey = s"${component}/hostname"
        val startKey = s"${component}/start"
        val statusKey = s"${component}/status"
        val activationCountKey = s"${component}/activationCount"
        val overloadKey = s"${component}/overload"
        val invokerHealth = s"${component}/invokerHealth"
        val userActivationCountKey = s"${component}/userActivationCount"
    }

    object WhiskProps {
        val whiskProps = "whiskprops"
    }
}
