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
import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.Span.convertDurationToSpan

import akka.actor.ActorSystem
import whisk.common.ConsulClient
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.utils.ExecutionContextFactory

@RunWith(classOf[JUnitRunner])
class ConsulClientTests extends FlatSpec with ScalaFutures with Matchers {

    implicit val system = ActorSystem()
    implicit val ec = system.dispatcher

    implicit val testConfig = PatienceConfig(5 seconds)

    private val config = new WhiskConfig(consulServer)
    private val consul = new ConsulClient(config.consulServer)

    "ConsulClient" should "be able to put, get and delete" in {
        val key = "emperor"
        val value = "palpatine"

        // Create an entry
        noException should be thrownBy consul.put(key, value).futureValue

        // Gets the entry
        consul.get(key).futureValue should equal(value)

        // Deletes the entry
        noException should be thrownBy consul.del(key).futureValue

        // Asserts that the entry is gone
        an[Exception] should be thrownBy consul.get(key).futureValue
    }

    it should "return an Exception if a non-existent key is queried" in {
        val key = "no_such_key"
        an[Exception] should be thrownBy consul.get(key).futureValue
    }

    it should "be able to retrieve many keys recursively and checking against stored values" in {
        val prefix = "xxx"
        val values = Map(
            s"$prefix/k1" -> "key1",
            s"$prefix/k2" -> "key2",
            s"$prefix/k3/inner" -> "key3")

        // Write all values to the key/value store
        values foreach {
            case (key, value) => noException should be thrownBy consul.put(key, value).futureValue
        }

        consul.getRecurse(prefix).futureValue should equal(values)
    }

    it should "drop the first part of the key" in {
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
