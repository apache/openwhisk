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

import akka.http.scaladsl.model.ContentTypes
import common.StreamLogging
import spray.json._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entity.Attachments.{Attached, Inline}
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.core.entity.{
  BlackBoxExec,
  CodeExecAsAttachment,
  CodeExecAsString,
  Exec,
  ExecManifest,
  WhiskAction
}

import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class ExecTests extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterAll {
  behavior of "exec deserialization"

  val config = new WhiskConfig(ExecManifest.requiredProperties)
  ExecManifest.initialize(config)

  override protected def afterAll(): Unit = {
    ExecManifest.initialize(config)
    super.afterAll()
  }

  it should "read existing code string as attachment" in {
    val json = """{
                 |  "name": "action_tests_name2",
                 |  "_id": "anon-Yzycx8QnIYDp3Tby0Fnj23KcMtH/action_tests_name2",
                 |  "publish": false,
                 |  "annotations": [],
                 |  "version": "0.0.1",
                 |  "updated": 1533623651650,
                 |  "entityType": "action",
                 |  "exec": {
                 |    "kind": "nodejs:10",
                 |    "code": "foo",
                 |    "binary": false
                 |  },
                 |  "parameters": [
                 |    {
                 |      "key": "x",
                 |      "value": "b"
                 |    }
                 |  ],
                 |  "limits": {
                 |    "timeout": 60000,
                 |    "memory": 256,
                 |    "logs": 10
                 |  },
                 |  "namespace": "anon-Yzycx8QnIYDp3Tby0Fnj23KcMtH"
                 |}""".stripMargin.parseJson.asJsObject
    val action = WhiskAction.serdes.read(json)
    action.exec should matchPattern { case CodeExecAsAttachment(_, Inline("foo"), None, false) => }
  }

  it should "properly determine binary property" in {
    val j1 = """{
               |  "kind": "nodejs:10",
               |  "code": "SGVsbG8gT3BlbldoaXNr",
               |  "binary": false
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j1) should matchPattern {
      case CodeExecAsAttachment(_, Inline("SGVsbG8gT3BlbldoaXNr"), None, true) =>
    }

    val j2 = """{
               |  "kind": "nodejs:10",
               |  "code": "while (true)",
               |  "binary": false
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j2) should matchPattern {
      case CodeExecAsAttachment(_, Inline("while (true)"), None, false) =>
    }

    //Defaults to binary
    val j3 = """{
               |  "kind": "nodejs:10",
               |  "code": "while (true)"
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j3) should matchPattern {
      case CodeExecAsAttachment(_, Inline("while (true)"), None, false) =>
    }
  }

  it should "read code stored as attachment" in {
    val json = """{
                 |  "kind": "java:8",
                 |  "code": {
                 |    "attachmentName": "foo:bar",
                 |    "attachmentType": "application/java-archive",
                 |    "length": 32768,
                 |    "digest": "sha256-foo"
                 |  },
                 |  "binary": true,
                 |  "main": "hello"
                 |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(json) should matchPattern {
      case CodeExecAsAttachment(_, Attached("foo:bar", _, Some(32768), Some("sha256-foo")), Some("hello"), true) =>
    }
  }

  it should "read code stored as jar property" in {
    val j1 = """{
               |  "kind": "nodejs:10",
               |  "jar": "SGVsbG8gT3BlbldoaXNr",
               |  "binary": false
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j1) should matchPattern {
      case CodeExecAsAttachment(_, Inline("SGVsbG8gT3BlbldoaXNr"), None, true) =>
    }
  }

  it should "read existing code string as string with old manifest" in {
    val oldManifestJson =
      """{
        |  "runtimes": {
        |    "nodejs": [
        |      {
        |        "kind": "nodejs:10",
        |        "default": true,
        |        "image": {
        |          "prefix": "openwhisk",
        |          "name": "nodejs6action",
        |          "tag": "latest"
        |        },
        |        "deprecated": false,
        |        "stemCells": [{
        |          "initialCount": 2,
        |          "memory": "256 MB"
        |        }]
        |      }
        |    ]
        |  }
        |}""".stripMargin.parseJson.compactPrint

    val oldConfig =
      new TestConfig(Map(WhiskConfig.runtimesManifest -> oldManifestJson), ExecManifest.requiredProperties)
    ExecManifest.initialize(oldConfig)
    val j1 = """{
               |  "kind": "nodejs:10",
               |  "code": "SGVsbG8gT3BlbldoaXNr",
               |  "binary": false
             |}""".stripMargin.parseJson.asJsObject

    Exec.serdes.read(j1) should matchPattern {
      case CodeExecAsString(_, "SGVsbG8gT3BlbldoaXNr", None) =>
    }

    //Reset config back
    ExecManifest.initialize(config)
  }

  behavior of "blackbox exec deserialization"

  it should "read existing code string as attachment" in {
    val json = """{
                 |  "name": "action_tests_name2",
                 |  "_id": "anon-Yzycx8QnIYDp3Tby0Fnj23KcMtH/action_tests_name2",
                 |  "publish": false,
                 |  "annotations": [],
                 |  "version": "0.0.1",
                 |  "updated": 1533623651650,
                 |  "entityType": "action",
                 |  "exec": {
                 |    "kind": "blackbox",
                 |    "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
                 |    "code": "foo",
                 |    "binary": false
                 |  },
                 |  "parameters": [
                 |    {
                 |      "key": "x",
                 |      "value": "b"
                 |    }
                 |  ],
                 |  "limits": {
                 |    "timeout": 60000,
                 |    "memory": 256,
                 |    "logs": 10
                 |  },
                 |  "namespace": "anon-Yzycx8QnIYDp3Tby0Fnj23KcMtH"
                 |}""".stripMargin.parseJson.asJsObject
    val action = WhiskAction.serdes.read(json)
    action.exec should matchPattern { case BlackBoxExec(_, Some(Inline("foo")), None, false, false) => }
  }

  it should "properly determine binary property" in {
    val j1 = """{
               |    "kind": "blackbox",
               |    "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
               |    "code": "SGVsbG8gT3BlbldoaXNr",
               |    "binary": false
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j1) should matchPattern {
      case BlackBoxExec(_, Some(Inline("SGVsbG8gT3BlbldoaXNr")), None, false, true) =>
    }

    val j2 = """{
               |  "kind": "blackbox",
               |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
               |  "code":  "while (true)",
               |  "binary": false
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j2) should matchPattern {
      case BlackBoxExec(_, Some(Inline("while (true)")), None, false, false) =>
    }

    //Empty code should resolve as None
    val j3 = """{
               |  "kind": "blackbox",
               |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
               |  "code": " "
               |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j3) should matchPattern {
      case BlackBoxExec(_, None, None, false, false) =>
    }

    val j4 = """{
                 |  "kind": "blackbox",
                 |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
                 |  "code": {
                 |    "attachmentName": "foo:bar",
                 |    "attachmentType": "application/octet-stream",
                 |    "length": 32768,
                 |    "digest": "sha256-foo"
                 |  },
                 |  "binary": true,
                 |  "main": "hello"
                 |}""".stripMargin.parseJson.asJsObject
    Exec.serdes.read(j4) should matchPattern {
      case BlackBoxExec(_, Some(Attached("foo:bar", _, Some(32768), Some("sha256-foo"))), Some("hello"), false, true) =>
    }
  }

  behavior of "blackbox exec serialization"

  it should "serialize with inline attachment" in {
    val bb = BlackBoxExec(
      ImageName.fromString("docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1").get,
      Some(Inline("foo")),
      None,
      false,
      false)
    val js = Exec.serdes.write(bb)

    val js2 = """{
                |  "kind": "blackbox",
                |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
                |  "binary": false,
                |  "code": "foo"
                |}""".stripMargin.parseJson.asJsObject
    js shouldBe js2
  }

  it should "serialize with attached attachment" in {
    val bb = BlackBoxExec(
      ImageName.fromString("docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1").get,
      Some(Attached("foo", ContentTypes.`application/octet-stream`, Some(42), Some("sha1-42"))),
      None,
      false,
      true)
    val js = Exec.serdes.write(bb)

    val js2 = """{
                |  "kind": "blackbox",
                |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
                |  "binary": true,
                |  "code": {
                |    "attachmentName": "foo",
                |    "attachmentType": "application/octet-stream",
                |    "length": 42,
                |    "digest": "sha1-42"
                |  }
                |}""".stripMargin.parseJson.asJsObject
    js shouldBe js2
  }

  private class TestConfig(val props: Map[String, String], requiredProperties: Map[String, String])
      extends WhiskConfig(requiredProperties) {
    override protected def getProperties() = mutable.Map(props.toSeq: _*)
  }
}
