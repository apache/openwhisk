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
class ActionLoopGoContainerTests extends ActionProxyContainerTestUtils with WskActorSystem {

  val goCompiler = "actionloop-golang-v1.9"
  val image = goCompiler

  def withActionLoopContainer(code: ActionContainer => Unit) = withContainer(image)(code)

  behavior of image

  import ResourceHelpers._

  private def checkresponse(res: Option[JsObject], args: JsObject = JsObject()) = {
    res shouldBe defined
    res.get.fields("error") shouldBe JsString("no action defined yet")
    //res.get.fields("args") shouldBe args
  }

  private def goCodeHello(file: String, main: String) = Seq(
    Seq(s"${file}.go") ->
      s"""
         |package action
         |
         |import (
         |	"encoding/json"
         |	"fmt"
         |)
         |
         |func ${main}(event json.RawMessage) (json.RawMessage, error) {
         |	var obj map[string]interface{}
         |	json.Unmarshal(event, &obj)
         |	name, ok := obj["name"].(string)
         |	if !ok {
         |		name = "Stranger"
         |	}
         |	fmt.Printf("name=%s\\n", name)
         |	msg := map[string]string{"${file}-${main}": ("Hello, " + name + "!")}
         |	return json.Marshal(msg)
         |}
         |
          """.stripMargin
  )

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

  it should "accept a binary main" in {
    val exe = ExeBuilder.mkBase64Exe(
      goCompiler, goCodeHello("main", "Main"), "main")

    withActionLoopContainer {
      c =>
        c.init(initPayload(exe))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("main-Main", "Hello, Demo!"))
    }
  }

  //def pr(x: Any) = { println(x) ; x}

  it should "build and run a go main zipped exe" in {
    val zip = ExeBuilder.mkBase64Zip(
      goCompiler, goCodeHello("main", "Main"), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(zip))._1 should be(200)
        c.run(helloMsg()) should be(okMsg("main-Main", "Hello, Demo!"))
    }
  }

  it should "buid and run a go hello exe " in {
    val exe = ExeBuilder.mkBase64Exe(
      goCompiler, goCodeHello("hello", "Hello"), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(exe, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello-Hello", "Hello, Demo!"))
    }
  }

  it should "build and run a go hello zipped exe" in {
    val zip = ExeBuilder.mkBase64Zip(
      goCompiler, goCodeHello("hello", "Hello"), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(zip, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("hello-Hello", "Hello, Demo!"))
    }
  }

  val helloSrc =
    """
      |package action
      |
      |import (
      |	"encoding/json"
      |	"fmt"
      |)
      |
      |func Hello(event json.RawMessage) (json.RawMessage, error) {
      |	var obj struct {
      |		Name string `json:",omitempty"`
      |	}
      |	err := json.Unmarshal(event, &obj)
      |	if err != nil {
      |		return nil, err
      |	}
      |	name := obj.Name
      |	if name == "" {
      |		name = "Stranger"
      |	}
      |	fmt.Printf("name=%s\n", name)
      |	msg := map[string]string{"Hello": ("Hello, " + name + "!")}
      |	return json.Marshal(msg)
      |}
    """.stripMargin

  val mainSrc =
    """
      |package action
      |
      |import (
      |	"encoding/json"
      |	"fmt"
      |)
      |
      |func Main(event json.RawMessage) (json.RawMessage, error) {
      |	var obj map[string]interface{}
      |	json.Unmarshal(event, &obj)
      |	name, ok := obj["name"].(string)
      |	if !ok {
      |		name = "Stranger"
      |	}
      |	fmt.Printf("name=%s\n", name)
      |	msg := map[string]string{"Main": ("Hello, " + name + "!")}
      |	return json.Marshal(msg)
      |}
    """.stripMargin

  it should "deploy a src main action " in {
    var src = ExeBuilder.mkBase64Src(Seq(
      Seq("main") -> mainSrc
    ), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Main", "Hello, Demo!"))
    }
  }

  it should "deploy a src hello action " in {
    var src = ExeBuilder.mkBase64Src(Seq(
      Seq("hello") -> helloSrc
    ), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Hello", "Hello, Demo!"))
    }
  }

  it should "deploy a zip main src action" in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("main.go") -> mainSrc
    ), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Main", "Hello, Demo!"))
    }
  }

  it should "deploy a zip main src subdir action" in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("action", "main.go") -> mainSrc
    ), "main")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Main", "Hello, Demo!"))
    }
  }

  it should "deploy a zip src hello action " in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("hello.go") -> helloSrc
    ), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Hello", "Hello, Demo!"))
    }
  }


  it should "deploy a zip src hello action in subdir" in {
    var src = ExeBuilder.mkBase64SrcZip(Seq(
      Seq("action", "hello.go") -> helloSrc
    ), "hello")
    withActionLoopContainer {
      c =>
        c.init(initPayload(src, "hello"))._1 shouldBe (200)
        c.run(helloMsg()) should be(okMsg("Hello", "Hello, Demo!"))
    }
  }
}

