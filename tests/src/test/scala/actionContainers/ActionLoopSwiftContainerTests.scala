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

package actionContainers

//import java.util.concurrent.TimeoutException

import actionContainers.ActionContainer.withContainer
import common.WskActorSystem
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

//import spray.json.JsNumber
import spray.json.{JsObject, JsString}
//import spray.json.JsBoolean

@RunWith(classOf[JUnitRunner])
class ActionLoopSwiftContainerTests extends ActionProxyContainerTestUtils with WskActorSystem {

  val swiftCompiler = "actionloop-swift-v4.1"
  val image = swiftCompiler

  import ResourceHelpers._

  // "example" is the image build by /sdk/docker
  def withActionLoopContainer(code: ActionContainer => Unit) = withContainer(image)(code)

  behavior of image

  private def swiftCodeHello(file: String, main: String) = Seq(
    Seq(s"${file}.swift") ->
      s"""
         |func ${main}(args: [String:Any]) -> [String:Any] {
         |    if let name = args["name"] as? String {
         |        print(name)
         |        return [ "${file}-${main}" : "Hello, \\(name)!" ]
         |    } else {
         |        return [ "${file}${main}" : "Hello swift 4!" ]
         |    }
         |}
         |
       """.stripMargin)

  private def helloMsg(name: String = "Demo") =
    runPayload(JsObject("name" -> JsString(name)))

  private def okMsg(key: String, value: String) =
    200 -> Some(JsObject(key -> JsString(value)))

  it should "run sample with init that does nothing" in {
    val (out, err) = withActionLoopContainer { c =>
      c.init(JsObject())._1 should be(200)
      c.run(JsObject())._1 should be(400)
    }
  }

  it should "buid and run a swift main exe " in {
    val exe = ExeBuilder.mkBase64Exe(
      swiftCompiler, swiftCodeHello("main", "main"), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(exe))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("main-main", "Hello, Demo!"))
    }
  }

  it should "build and run a swift main zipped exe" in {
    val zip = ExeBuilder.mkBase64Zip(
      swiftCompiler, swiftCodeHello("main", "main"), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(zip))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("main-main", "Hello, Demo!"))
    }
  }

  it should "buid and run a swift hello exe " in {
    val exe = ExeBuilder.mkBase64Exe(
      swiftCompiler, swiftCodeHello("hello", "hello"), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(exe, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello-hello", "Hello, Demo!"))
    }
  }

  it should "build and run a swift hello zipped exe" in {
    val zip = ExeBuilder.mkBase64Zip(
      swiftCompiler, swiftCodeHello("hello", "hello"), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(zip, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello-hello", "Hello, Demo!"))
    }
  }

  val mainSrc =
    """
      |func main(args: [String:Any]) -> [String:Any] {
      |    if let name = args["name"] as? String {
      |        print(name)
      |        return [ "main" : "Hello \(name)!" ]
      |    } else {
      |        return [ "main" : "Hello swif4!" ]
      |    }
      |}
    """.stripMargin

  val helloSrc =
    """
      |func hello(args: [String:Any]) -> [String:Any] {
      |    if let name = args["name"] as? String {
      |        print(name)
      |        return [ "hello" : "Hello \(name)!" ]
      |    } else {
      |        return [ "hello" : "Hello swif4!" ]
      |    }
      |}
    """.stripMargin

  it should "deploy a src main action " in {
    var src = ExeBuilder.mkBase64Src(Seq(
      Seq("main") -> mainSrc
    ), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("main", "Hello Demo!"))
    }
  }

  it should "deploy a src hello action " in {
    var src = ExeBuilder.mkBase64Src(Seq(
      Seq("hello") -> helloSrc
    ), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello", "Hello Demo!"))
    }
  }

  it should "deploy a zip main src action" in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("main.swift") -> mainSrc
    ), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("main", "Hello Demo!"))
    }
  }


  it should "deploy a zip src hello action " in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("hello.swift") -> helloSrc
    ), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello", "Hello Demo!"))
    }
  }
}
