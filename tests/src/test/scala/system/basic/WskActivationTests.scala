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

package system.basic

import common.rest.WskRestOperations
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common._

import whisk.utils.retry

import scala.concurrent.duration._
import spray.json._
import spray.json.DefaultJsonProtocol._

@RunWith(classOf[JUnitRunner])
class WskActivationTests extends TestHelpers with WskTestHelpers with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk: WskOperations = new WskRestOperations

  behavior of "Whisk activations"

  it should "fetch logs using activation logs API" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "logFetch"
    val logFormat = "\\d+-\\d+-\\d+T\\d+:\\d+:\\d+.\\d+Z\\s+%s: %s"

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("log.js")))
    }

    val run = wsk.action.invoke(name)

    // Even though the activation was blocking, the activation itself might not have appeared in the database.
    withActivation(wsk.activation, run) { activation =>
      // Needs to be retried because there might be an SPI being plugged in which is handling logs not consistent with
      // the database where the activation itself comes from (activation in CouchDB, logs in Elasticsearch for
      // example).
      retry({
        val logs = wsk.activation.logs(Some(activation.activationId)).stdout

        logs should include regex logFormat.format("stdout", "this is stdout")
        logs should include regex logFormat.format("stderr", "this is stderr")
      }, 60 * 5, Some(1.second)) // retry for 5 minutes
    }
  }

  it should "fetch result using activation result API" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = "hello"
    val expectedResult = JsObject(
      "result" -> JsObject("payload" -> "hello, undefined!".toJson),
      "success" -> true.toJson,
      "status" -> "success".toJson)

    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("hello.js")))
    }

    withActivation(wsk.activation, wsk.action.invoke(name)) { activation =>
      wsk.activation.result(Some(activation.activationId)).stdout.parseJson.asJsObject shouldBe expectedResult
    }
  }

  it should "list activations with skip" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = withTimestamp("countdown")
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("countdown.js")))
    }

    val count = 2
    val run = wsk.action.invoke(name, Map("n" -> count.toJson))
    withActivation(wsk.activation, run) { activation =>
      //make query more robust
      val queryTime = activation.start.minusMillis(500)
      //the countdown function invokes itself two more times, we need to skip 2 invocations to get single activation
      val activations =
        wsk.activation.pollFor(N = 1, Some(name), since = Some(queryTime), skip = Some(2), retries = 80).length
      withClue(s"expected activations of action '$name' since $queryTime") {
        activations shouldBe 1
      }
    }
  }

  it should "get last activation" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val name = withTimestamp("countdown")
    assetHelper.withCleaner(wsk.action, name) { (action, _) =>
      action.create(name, Some(TestUtils.getTestActionFilename("countdown.js")))
    }

    val count = 1
    val run = wsk.action.invoke(name, Map("n" -> count.toJson))
    withActivation(wsk.activation, run) { activation =>
      val res = wsk.activation.get(None, last = Some(true))
      val jsonResponse = wsk.parseJsonString(res.stdout)
      jsonResponse.getFields("name").head.toString should include("countdown")
    }
  }
}
