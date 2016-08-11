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

package whisk.consul

import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.Span.convertDurationToSpan

import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.stream.ActorMaterializer
import common.WskActorSystem
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.ConsulClient
import whisk.common.ConsulService
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer

@RunWith(classOf[JUnitRunner])
class ConsulClientTests extends FlatSpec with ScalaFutures with Matchers with WskActorSystem {

    implicit val testConfig = PatienceConfig(5.seconds)
    implicit val materializer = ActorMaterializer()

    val config = new WhiskConfig(consulServer)
    val consul = new ConsulClient(config.consulServer)

    val consulHost :: consulPort :: Nil = config.consulServer.split(":").toList
    val consulUri = Uri().withScheme("http").withHost(consulHost).withPort(consulPort.toInt)
    val checkInterval = 1.second

    /**
     * Registers a service in consul with a given healthcheck
     */
    def registerService(name: String, id: String, checkScript: Option[String] = None) = {
        val obj = Map(
            "ID" -> id.toJson,
            "Name" -> name.toJson
        ) ++ checkScript.map { script =>
                "Check" -> JsObject(
                    "Script" -> script.toJson,
                    "Interval" -> s"${checkInterval.toSeconds}s".toJson
                )
            }

        Marshal(obj.toJson).to[RequestEntity].flatMap { entity =>
            val r = Http().singleRequest(
                HttpRequest(
                    method = HttpMethods.PUT,
                    uri = consulUri.withPath(Uri.Path("/v1/agent/service/register")),
                    entity = entity
                )
            )
            r.flatMap { response =>
                Unmarshal(response).to[Any]
            }
        }
    }

    /**
     * Deregisters a service in consul
     */
    def deregisterService(id: String) = {
        val r = Http().singleRequest(
            HttpRequest(
                method = HttpMethods.PUT,
                uri = consulUri.withPath(Uri.Path(s"/v1/agent/service/deregister/$id"))
            )
        )
        r.flatMap { response =>
            Unmarshal(response).to[Any]
        }
    }

    "Consul KV client" should "be able to put, get and delete" in {
        val key = "emperor"
        val value = "palpatine"

        // Create an entry
        noException should be thrownBy consul.kv.put(key, value).futureValue

        // Gets the entry
        consul.kv.get(key).futureValue should equal(value)

        // Deletes the entry
        noException should be thrownBy consul.kv.del(key).futureValue

        // Asserts that the entry is gone
        consul.kv.get(key).failed.futureValue shouldBe a[NoSuchElementException]
    }

    it should "return an Exception if a non-existent key is queried" in {
        val key = "no_such_key"
        consul.kv.get(key).failed.futureValue shouldBe a[NoSuchElementException]
    }

    it should "be able to retrieve many keys recursively and checking against stored values" in {
        val prefix = "xxx"
        val values = Map(
            s"$prefix/k1" -> "key1",
            s"$prefix/k2" -> "key2",
            s"$prefix/k3/inner" -> "key3")

        // Write all values to the key/value store
        values foreach {
            case (key, value) => noException should be thrownBy consul.kv.put(key, value).futureValue
        }

        consul.kv.getRecurse(prefix).futureValue should equal(values)
    }

    "Consul Catalog client" should "return a list of all services" in {
        consul.catalog.services().futureValue.size should be > 0
    }

    "Consul Health client" should "return an empty list of health results" in {
        val services = consul.health.service("bogus").futureValue
        services shouldBe List.empty
    }

    it should "return a list of health results" in {
        val service = ConsulService("testservice", "testservice_1")
        registerService(service.name, service.id).futureValue

        val services = consul.health.service(service.name).futureValue
        services.head shouldBe service

        deregisterService(service.id).futureValue
    }

    it should "return only the passing service" in {
        val passing = ConsulService("testservice", "testservice_passing")
        val failing = ConsulService("testservice", "testservice_failing")
        registerService(passing.name, passing.id, Some("exit 0")).futureValue
        registerService(failing.name, failing.id, Some("exit 1")).futureValue

        Thread.sleep(checkInterval.toMillis)

        val services = consul.health.service(passing.name, true).futureValue
        services.head shouldBe passing

        deregisterService(passing.id).futureValue
        deregisterService(failing.id).futureValue
    }

    "ConsulClient helper methods" should "drop the first part of the key" in {
        val test = Map(
            "nested/k1" -> "v1",
            "inner/k2" -> "v2")

        val flattened = Map(
            "k1" -> "v1",
            "k2" -> "v2")

        ConsulClient.dropKeyLevel(test) should equal(flattened)
    }

    it should "create a nested map by grouping by the first part of the key" in {
        val test = Map(
            "invoker0/count" -> "1",
            "invoker0/status" -> "true",
            "invoker1/count" -> "2",
            "invoker1/status" -> "false")

        val nested = Map(
            "invoker0" -> Map("count" -> "1", "status" -> "true"),
            "invoker1" -> Map("count" -> "2", "status" -> "false"))

        ConsulClient.toNestedMap(test) should equal(nested)
    }

}
