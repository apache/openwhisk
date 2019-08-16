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

package org.apache.openwhisk.core.monitoring.metrics

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class ActivationsTest extends FlatSpec with Matchers {
  behavior of "Activation"

  it should "deserialize normal values" in {
    val json = """{
                 |  "body": {
                 |    "statusCode": 0,
                 |    "duration": 3,
                 |    "name": "whisk.system/invokerHealthTestAction0",
                 |    "waitTime": 583915671,
                 |    "conductor": false,
                 |    "kind": "nodejs:6",
                 |    "initTime": 0,
                 |    "memory": 256
                 |  },
                 |  "eventType": "Activation",
                 |  "source": "invoker0",
                 |  "subject": "demo",
                 |  "timestamp": 1524476122676,
                 |  "userId": "d0888ad5-5a92-435e-888a-d55a92935e54",
                 |  "namespace": "demo"
                 |}""".stripMargin
    val a = EventMessage.parse(json).get.body.asInstanceOf[Activation]
    a.duration shouldBe 3.millis
    a.initTime shouldBe Duration.Zero
    a.waitTime shouldBe 583915671.millis
  }

  it should "deserialize with negative durations" in {
    val json = """{
                 |    "statusCode": 0,
                 |    "duration": 3,
                 |    "name": "whisk.system/invokerHealthTestAction0",
                 |    "waitTime": -50,
                 |    "conductor": false,
                 |    "kind": "nodejs:6",
                 |    "initTime": 0,
                 |    "memory": 256
                 |}""".stripMargin
    val a = Activation.parse(json).get
    a.waitTime shouldBe Duration.Zero
  }
}
