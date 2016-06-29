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

import scala.annotation.implicitNotFound
import scala.concurrent.Future
import scala.language.postfixOps

import org.apache.commons.codec.binary.Base64

import akka.actor.ActorSystem
import spray.client.pipelining.Delete
import spray.client.pipelining.Get
import spray.client.pipelining.Put
import spray.client.pipelining.WithTransformerConcatenation
import spray.client.pipelining.sendReceive
import spray.client.pipelining.sendReceive$default$3
import spray.client.pipelining.unmarshal
import spray.http.HttpRequest
import spray.http.Uri
import spray.http.Uri.Path
import spray.http.Uri.Query
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol
import java.util.NoSuchElementException

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
 * Client to access Consul's <a href="https://www.consul.io/docs/agent/http/kv.html">
 * key/value store</a>
 */
class ConsulClient(host: String)(implicit val actorSystem: ActorSystem) {

    private implicit val executionContext = actorSystem.dispatcher

    private val kv = Path("/v1/kv")

    private val pipeline = sendReceive
    private val listPipeline: HttpRequest => Future[List[ConsulEntry]] = (
        sendReceive
        ~> unmarshal[List[ConsulEntry]])

    private def uriWithKey(key: String) = Uri("http://" + host) withPath kv / key

    /**
     * Gets the entry from Consul with the given key
     *
     * @param key the key to get
     * @return a future that completes with the Base64
     *         decoded value of the Consul entry
     */
    def get(key: String): Future[String] = {
        listPipeline(Get(uriWithKey(key))) flatMap {
            _.head.decodedValue match {
                case Some(value) => Future successful value
                case None        => Future failed new NoSuchElementException
            }
        }
    }

    /**
     * Writes the key/value entry with the given key.
     * If the key already exists, the value is overridden
     *
     * @param key the key to store
     * @param value the value to store
     * @return a future that completes on success of the operation
     */
    def put(key: String, value: String): Future[Any] = {
        pipeline(Put(uriWithKey(key), value))
    }

    /**
     * Deletes the entry with the given key from the
     * key/value store
     *
     * @param key the key to delete
     * @return a future that completes on success of the operation
     */
    def del(key: String): Future[Any] = {
        pipeline(Delete(uriWithKey(key)))
    }

    /**
     * Gets all entries in a path
     *
     * @param root the key under which to get the entries
     * @return a future that completes with key/value pairs
     */
    def getRecurse(root: String): Future[Map[String, String]] = {
        val url = uriWithKey(root) withQuery Query("recurse" -> "true")
        listPipeline(Get(url)) map { entries =>
            entries map { entry =>
                entry.key -> entry.decodedValue.getOrElse("")
            } toMap
        }
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
