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

package whisk.common

import scala.concurrent.Future

import org.apache.commons.codec.binary.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer

import java.util.NoSuchElementException

import akka.stream.scaladsl._
import spray.json._
import spray.json.DefaultJsonProtocol._
import scala.util.Try

/**
 * Client to access Consul's <a href="https://www.consul.io/docs/agent/http.html">
 * HTTP API</a>
 */
class ConsulClient(hostAndPort: String)(implicit val actorSystem: ActorSystem) {
    // A consequence of hostAndPort being merged in config.
    private val host :: port :: Nil = hostAndPort.split(":").toList

    private implicit val executionContext = actorSystem.dispatcher
    private implicit val materializer = ActorMaterializer()

    private val base = Uri().withScheme("http").withHost(host).withPort(port.toInt)

    val kv = new ConsulKeyValueApi(base)
    val health = new ConsulHealthApi(base)
    val catalog = new ConsulCatalogApi(base)
}

/*
 * Consul API for the key/value store
 */

case class ConsulEntry(key: String, value: Option[String]) {
    val decodedValue = value map { value =>
        new String(Base64.decodeBase64(value))
    }
}
object ConsulEntry extends DefaultJsonProtocol {
    // Consul's JSON responses have capitalized keynames, scala standard
    // is lowercased fields though, so we explicitly name the parameters
    // for ConsulEntry here.
    implicit val serdes = jsonFormat(ConsulEntry.apply, "Key", "Value")
}

/**
 * Client to access Consul's Key/Value-Store API
 */
class ConsulKeyValueApi(base: Uri)(implicit val actorSystem: ActorSystem, val materializer: ActorMaterializer) extends KeyValueStore {
    private implicit val executionContext = actorSystem.dispatcher

    private def uriWithKey(key: String) = base.withPath(Uri.Path(s"/v1/kv/$key"))

    override def get(key: String): Future[String] = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.GET,
                uri = uriWithKey(key)))
        r.flatMap { response =>
            if (response.status == StatusCodes.OK) {
                Unmarshal(response.entity).to[List[ConsulEntry]]
            } else {
                response.entity.dataBytes.runWith(Sink.ignore)
                Future.failed(new NoSuchElementException())
            }
        } map { _.head.decodedValue.getOrElse(throw new NoSuchElementException()) }
    }

    override def put(key: String, value: String): Future[Any] = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.PUT,
                uri = uriWithKey(key),
                entity = value))
        r.flatMap { response =>
            Unmarshal(response).to[Any]
        }
    }

    override def del(key: String): Future[Any] = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.DELETE,
                uri = uriWithKey(key)))
        r.flatMap { response =>
            Unmarshal(response).to[Any]
        }
    }

    /**
     * Gets all entries in a path
     *
     * @param root the key under which to get the entries
     * @return a future that completes with key/value pairs
     */
    def getRecurse(root: String): Future[Map[String, String]] = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.GET,
                uri = uriWithKey(root).withQuery(Uri.Query("recurse" -> "true"))))
        r.flatMap { response =>
            if (response.status != StatusCodes.NotFound) {
                Unmarshal(response).to[List[ConsulEntry]]
            } else {
                Future.failed(new NoSuchElementException())
            }
        } map { entries =>
            entries.map(e => e.key -> e.decodedValue.getOrElse("")).toMap
        }
    }
}

trait KeyValueStore {
    /**
     * Gets the entry from the key/value store with the given key
     *
     * @param key the key to get
     * @return a future that completes the value from the store
     */
    def get(key: String): Future[String]

    /**
     * Writes the key/value entry with the given key.
     * If the key already exists, the value is overridden
     *
     * @param key the key to store
     * @param value the value to store
     * @return a future that completes on success of the operation
     */
    def put(key: String, value: String): Future[Any]

    /**
     * Deletes the entry with the given key from the
     * key/value store
     *
     * @param key the key to delete
     * @return a future that completes on success of the operation
     */
    def del(key: String): Future[Any]
}

/*
 * Consul Services API
 */

case class ConsulService(name: String, id: String)
object ConsulService extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat(ConsulService.apply, "Service", "ID")
}

case class ConsulServiceHealthEntry(service: ConsulService)
object ConsulServiceHealthEntry extends DefaultJsonProtocol {
    implicit val serdes = jsonFormat(ConsulServiceHealthEntry.apply(_), "Service")
}

/**
 * Client to access Consul's Health API
 */
class ConsulHealthApi(base: Uri)(implicit val actorSystem: ActorSystem, val materializer: ActorMaterializer) {
    private implicit val executionContext = actorSystem.dispatcher

    /**
     * Gets the health status of the given service
     *
     * @param name name of the service to query for
     * @param passingOnly get only passing nodes of the service
     * @return a future that completes with the matching services
     */
    def service(name: String, passingOnly: Boolean = false): Future[List[ConsulService]] = {
        val healthUri = base.withPath(Uri.Path(s"/v1/health/service/$name"))
        val serviceUri = if (passingOnly) healthUri.withQuery(Uri.Query("passing")) else healthUri

        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.GET,
                uri = serviceUri))
        r.flatMap { response =>
            Unmarshal(response.entity).to[List[ConsulServiceHealthEntry]]
        } map { _.map(_.service) }
    }
}

/**
 * Client to access Consul's Catalog API
 */
class ConsulCatalogApi(base: Uri)(implicit val actorSystem: ActorSystem, val materializer: ActorMaterializer) {
    private implicit val executionContext = actorSystem.dispatcher

    /**
     * Gets all services in that consul instance
     *
     * @return a future that completes with a set of service names
     */
    def services(): Future[Set[String]] = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.GET,
                uri = base.withPath(Uri.Path(s"/v1/catalog/services"))))
        r.flatMap { response =>
            Unmarshal(response.entity).to[Map[String, JsValue]]
        } map { _.keys.toSet }
    }
}

object ConsulClient {
    /**
     * Drops the first level of the keys of the consul entries
     * as it is redundant most of the times.
     *
     * <b>Note:</b> This does not take key differences into account, the
     * caller needs to make sure that no information is lost
     *
     * @param nested a map containing nested keys, e.g.
     *        <code>Map("nested/k1" -> "v1", "inner/k2" -> "v2")</code>
     * @return a map with the first level of all keys removed, e.g.
     *         <code>Map("k1" -> "v1", "k2" -> "v2")</code>
     */
    def dropKeyLevel(nested: Map[String, String]): Map[String, String] = {
        nested map {
            case (key, value) => key.split("/").tail.mkString("/") -> value
        }
    }

    /**
     * Nests a map of consul entries one level by grouping by the
     * first level of the map's keys.
     *
     * @param flat a map with nested keys in a flat manor, e.g.
     *        <code>Map("k1/nested" -> "v1", "k2/nested" -> "v2")</code>
     * @return a nested map grouped by the first level of the keys e.g.
     *         <code>Map("k1" -> Map("nested" -> "v1"), "k2" -> Map("nested" -> "v2"))</code>
     */
    def toNestedMap(flat: Map[String, String]): Map[String, Map[String, String]] = {
        flat groupBy {
            case (key, value) => key.split("/").head
        } mapValues { dropKeyLevel(_) }
    }
}

object ConsulKV {
    object InvokerKeys {
        // All invoker written information written here.
        // Underneath this, each invoker has its own path.
        val allInvokers = "invokers" // we store a small amount of data here
        val allInvokersData = "invokersData" // we store large amounts of data here
        private val invokerKeyPrefix = "invoker"
        def instancePath(instance: Int) = s"${allInvokers}/${invokerKeyPrefix}${instance}"
        def instanceDataPath(instance: Int) = s"${allInvokersData}/${invokerKeyPrefix}${instance}"

        // Invokers store the hostname they are running on here.
        def hostname(instance: Int) = s"${instancePath(instance)}/hostname"

        // Invokers store when they start here.
        val startKey = "start"
        def start(instance: Int) = s"${instancePath(instance)}/$startKey"

        // Invokers store their most recent check in time here
        val statusKey = "status"
        def status(instance: Int) = s"${instancePath(instance)}/${statusKey}"

        // Extract index from just the element of the path such as "invoker5"
        def extractInvokerIndex(key: String): Int = key.substring(invokerKeyPrefix.length).toInt

        // Get the invoker index given a key somewhere in that invoker's KV sub-hierarchy
        def getInvokerIndexFromAny(key: String): Option[Int] = {
            val prefix = s"${allInvokers}/${invokerKeyPrefix}"
            if (key.startsWith(prefix)) {
                val middle = key.substring(prefix.length)
                val slashIndex = middle.indexOf("/")
                if (slashIndex > 0) {
                    Try { middle.substring(0, slashIndex).toInt }.toOption
                } else None
            } else None
        }

    }

    // All keys for information written from the controller are here.
    object ControllerKeys {
        val component = "controller"
        val userActivationCountKey = s"${component}/userActivationCount"
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
