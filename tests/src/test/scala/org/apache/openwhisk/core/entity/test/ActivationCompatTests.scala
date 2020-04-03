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

package org.apache.openwhisk.core.entity.test

import org.apache.openwhisk.common.WhiskInstants
import org.apache.openwhisk.core.connector.Activation
import org.apache.openwhisk.core.entity.{
  ActionLimits,
  ActivationId,
  ActivationResponse,
  EntityName,
  EntityPath,
  LogLimit,
  MemoryLimit,
  Parameters,
  Subject,
  TimeLimit,
  WhiskActivation
}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._

import scala.concurrent.duration._
import org.apache.openwhisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class ActivationCompatTests extends FlatSpec with Matchers with WhiskInstants with DefaultJsonProtocol {
  behavior of "ActivationResponse"

  val activationResponseJs = """
     |{
     |  "result": {
     |    "res": "hello"
     |  },
     |  "statusCode": 0
     |}""".stripMargin.parseJson

  val whiskActivationJs = """
    |{
    |  "activationId": "be97c2fed5dc43d097c2fed5dc73d085",
    |  "annotations": [{
    |    "key": "causedBy",
    |    "value": "sequence"
    |  }, {
    |    "key": "path",
    |    "value": "ns2/a"
    |  }, {
    |    "key": "waitTime",
    |    "value": 5
    |  }, {
    |    "key": "kind",
    |    "value": "testkind"
    |  }, {
    |    "key": "limits",
    |    "value": {
    |      "concurrency": 1,
    |      "logs": 1,
    |      "memory": 128,
    |      "timeout": 1000
    |    }
    |  }, {
    |    "key": "initTime",
    |    "value": 10
    |  }],
    |  "duration": 123,
    |  "end": 1570013740007,
    |  "logs": [],
    |  "name": "a",
    |  "namespace": "ns",
    |  "publish": false,
    |  "response": {
    |    "result": {
    |      "res": 1
    |    },
    |    "statusCode": 0
    |  },
    |  "start": 1570013740005,
    |  "subject": "anon-HfJWZZSG9YE38Y8DJbgp9Xn0YyN",
    |  "version": "0.0.1"
    |}""".stripMargin.parseJson

  val whiskActivationErrorJs = """
    |{
    |  "activationId": "be97c2fed5dc43d097c2fed5dc73d085",
    |  "annotations": [{
    |    "key": "causedBy",
    |    "value": "sequence"
    |  }, {
    |    "key": "path",
    |    "value": "ns2/a"
    |  }, {
    |    "key": "waitTime",
    |    "value": 5
    |  }, {
    |    "key": "kind",
    |    "value": "testkind"
    |  }, {
    |    "key": "limits",
    |    "value": {
    |      "concurrency": 1,
    |      "memory": 128,
    |      "timeout": 1000
    |    }
    |  }, {
    |    "key": "initTime",
    |    "value": 10
    |  }],
    |  "duration": 123,
    |  "end": 1570013740007,
    |  "logs": [],
    |  "name": "a",
    |  "namespace": "ns",
    |  "publish": false,
    |  "response": {
    |    "result": {
    |      "error": {
    |         "statusCode": 404,
    |         "body": "Requested resource not found"
    |      }
    |    },
    |    "statusCode": 0
    |  },
    |  "start": 1570013740005,
    |  "subject": "anon-HfJWZZSG9YE38Y8DJbgp9Xn0YyN",
    |  "version": "0.0.1"
    |}""".stripMargin.parseJson

  val activationJs = """
     |{
     |  "causedBy": "sequence",
     |  "activationId": "be97c2fed5dc43d097c2fed5dc73d085",
     |  "conductor": false,
     |  "duration": 123,
     |  "initTime": 10,
     |  "kind": "testkind",
     |  "memory": 128,
     |  "name": "ns2/a",
     |  "statusCode": 0,
     |  "waitTime": 5
     |}""".stripMargin.parseJson

  val activationWithActionStatusCodeJs =
    """
      |{
      |  "userDefinedStatusCode": 404,
      |  "activationId": "be97c2fed5dc43d097c2fed5dc73d085",
      |  "causedBy": "sequence",
      |  "conductor": false,
      |  "duration": 123,
      |  "initTime": 10,
      |  "kind": "testkind",
      |  "memory": 128,
      |  "name": "ns2/a",
      |  "statusCode": 0,
      |  "waitTime": 5
      |}""".stripMargin.parseJson

  it should "deserialize without error" in {
    val activationResponse = ActivationResponse.serdes.read(activationResponseJs)
    val whiskActivation = WhiskActivation.serdes.read(whiskActivationJs)
    val activation = Activation.activationFormat.read(activationJs)
    val whiskActivationWithError = WhiskActivation.serdes.read(whiskActivationErrorJs)
    val activationWithActionStatus = Activation.activationFormat.read(activationWithActionStatusCodeJs)
  }

  def generateJsons(): Unit = {
    val resp = ActivationResponse.success(Some(JsObject("res" -> JsString("hello"))))
    val whiskActivation = WhiskActivation(
      namespace = EntityPath("ns"),
      name = EntityName("a"),
      Subject(),
      activationId = ActivationId.generate(),
      start = nowInMillis(),
      end = nowInMillis(),
      response = ActivationResponse.success(Some(JsObject("res" -> JsNumber(1)))),
      annotations = Parameters("limits", ActionLimits(TimeLimit(1.second), MemoryLimit(128.MB), LogLimit(1.MB)).toJson) ++
        Parameters(WhiskActivation.waitTimeAnnotation, 5.toJson) ++
        Parameters(WhiskActivation.initTimeAnnotation, 10.toJson) ++
        Parameters(WhiskActivation.kindAnnotation, "testkind") ++
        Parameters(WhiskActivation.pathAnnotation, "ns2/a") ++
        Parameters(WhiskActivation.causedByAnnotation, "sequence"),
      duration = Some(123))

    val activation = Activation.from(whiskActivation).get

    println(resp.toJson.prettyPrint)
    println(whiskActivation.toJson.prettyPrint)
    println(activation.toJson.prettyPrint)
  }
}
