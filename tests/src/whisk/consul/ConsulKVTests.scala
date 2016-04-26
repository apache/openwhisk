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

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import whisk.common.ConsulKV
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.consulServer
import whisk.utils.retry
import spray.json.JsNumber
import spray.json.JsString
import spray.json.JsNull
import spray.json.JsValue
import scala.language.postfixOps

@RunWith(classOf[JUnitRunner])
class ConsulKVTests extends FlatSpec with Matchers {

    def requiredProperties = consulServer
    private val config = new WhiskConfig(requiredProperties)
    private val consul = new ConsulKV(config.consulServer)

    "ConsulKV" should "be able to do a put and a get" in {
        val key = "emperor"
        val value = JsString("palpatine")
        consul.put(key, value)
        retry { assert(consul.get(key) == value) }
        consul.delete(key)
        retry { assert(consul.get(key) == JsNull) }
    }

    it should "be able to do a get on a non-existent key" in {
        val key = "no_such_key"
        val retrieved = consul.get(key)
        // No prior action - no retry needed
        assert(retrieved == JsNull)
    }

    it should "be able to retrieve many keys recursively and checking against stored values" in {
        val keyPrefix = "xxx"
        val kvInner = List(("k1", JsNumber(55)), ("k2", JsString("dog")), ("k3/inner", JsString("monkey")))
        val kv = kvInner.map { case (k, v) => (s"$keyPrefix/$k", v) }
        kv foreach { case (k, v) => consul.put(k, v) }
        retry {
            val retrieved: Map[String, JsValue] = consul.getRecurse(keyPrefix)
            assert(retrieved.size == kv.size)
            kv.foreach {
                case (k, v) =>
                    println(s"$k -> $v")
                    assert(retrieved.get(k) == Some(v)) // not a KV operation - no inner retry
            }
        }
        kv.foreach({
            case (k, v) =>
                consul.delete(k)
                retry { assert(consul.get(k) == JsNull) }
        })
    }

    it should "be able to delete a key" in {
        val key = "testKey"
        val value = JsString("testValue")

        consul.put(key, value)
        retry { assert(consul.get(key) == value) }

        consul.delete(key)
        retry { assert(consul.get(key) == JsNull) }
    }

}
