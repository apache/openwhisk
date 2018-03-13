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

import java.io.File

import common.{TestUtils, WskActorSystem}
import ActionContainer.withContainer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json._

@RunWith(classOf[JUnitRunner])
class Swift41CodableActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

  // note: "out" will likely not be empty in some swift build as the compiler
  // prints status messages and there doesn't seem to be a way to quiet them
  val enforceEmptyOutputStream = false
  lazy val swiftContainerImageName = "action-swift-v4.1"
  lazy val swiftBinaryName = "HelloSwift4Codable.zip"

  behavior of s"Codable $swiftContainerImageName"

  testEcho(Seq {
    (
      "swift echo",
      """
        |
        | extension FileHandle : TextOutputStream {
        |     public func write(_ string: String) {
        |         guard let data = string.data(using: .utf8) else { return }
        |         self.write(data)
        |     }
        | }
        |
        | struct AnInput: Codable {
        |  struct AnObject: Codable {
        |   let a: String?
        |  }
        |  let string: String?
        |  let numbers: [Int]?
        |  let object: AnObject?
        | }
        | func main(input: AnInput, respondWith: (AnInput?, Error?) -> Void) -> Void {
        |    print("hello stdout")
        |    var standardError = FileHandle.standardError
        |    print("hello stderr", to: &standardError)
        |    respondWith(input, nil)
        | }
      """.stripMargin)
  })

  testUnicode(Seq {
    (
      "swift unicode",
      """
        | struct AnInputOutput: Codable {
        |  let delimiter: String?
        |  let winter: String?
        |  let error: String?
        | }
        | func main(input: AnInputOutput, respondWith: (AnInputOutput?, Error?) -> Void) -> Void {
        |    if let str = input.delimiter as? String {
        |        let msg = "\(str) ☃ \(str)"
        |        print(msg)
        |        let answer = AnInputOutput(delimiter: nil, winter: msg, error: nil)
        |        respondWith(answer, nil)
        |    } else {
        |        let answer = AnInputOutput(delimiter: "no delimiter", winter: nil, error: nil)
        |        respondWith(answer, nil)
        |    }
        | }
      """.stripMargin.trim)
  })

  testEnv(
    Seq {
      (
        "swift environment",
        """
        | struct AnOutput: Codable {
        |  let api_host: String
        |  let api_key: String
        |  let namespace: String
        |  let action_name: String
        |  let activation_id: String
        |  let deadline: String
        | }
        | func main(respondWith: (AnOutput?, Error?) -> Void) -> Void {
        |     let env = ProcessInfo.processInfo.environment
        |     var a = "???"
        |     var b = "???"
        |     var c = "???"
        |     var d = "???"
        |     var e = "???"
        |     var f = "???"
        |     if let v : String = env["__OW_API_HOST"] {
        |         a = "\(v)"
        |     }
        |     if let v : String = env["__OW_API_KEY"] {
        |         b = "\(v)"
        |     }
        |     if let v : String = env["__OW_NAMESPACE"] {
        |         c = "\(v)"
        |     }
        |     if let v : String = env["__OW_ACTION_NAME"] {
        |         d = "\(v)"
        |     }
        |     if let v : String = env["__OW_ACTIVATION_ID"] {
        |         e = "\(v)"
        |     }
        |     if let v : String = env["__OW_DEADLINE"] {
        |         f = "\(v)"
        |     }
        |     let result = AnOutput(api_host:a, api_key:b, namespace:c, action_name:d, activation_id:e, deadline: f)
        |     respondWith(result, nil)
        | }
      """.stripMargin)
    },
    enforceEmptyOutputStream)

  it should "support actions using non-default entry points" in {
    withActionContainer() { c =>
      val code = """
                   | struct AnOutput: Codable {
                   |   let result: String?
                   | }
                   | func niam(respondWith: (AnOutput?, Error?) -> Void) -> Void {
                   |    respondWith(AnOutput(result: "it works"), nil)
                   | }
                   |""".stripMargin

      val (initCode, initRes) = c.init(initPayload(code, main = "niam"))
      initCode should be(200)

      val (_, runRes) = c.run(runPayload(JsObject()))
      runRes.get.fields.get("result") shouldBe Some(JsString("it works"))
    }
  }

  it should "return some error on action error" in {
    val (out, err) = withActionContainer() { c =>
      val code = """
                   | // You need an indirection, or swiftc detects the div/0
                   | // at compile-time. Smart.
                   | func div(x: Int, y: Int) -> Int {
                   |    return x/y
                   | }
                   | struct Result: Codable{
                   |    let divBy0: Int?
                   | }
                   | func main(respondWith: (Result?, Error?) -> Void) -> Void {
                   |    respondWith(Result(divBy0: div(x:5, y:0)), nil)
                   | }
                 """.stripMargin

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(502)

      runRes shouldBe defined
      runRes.get.fields.get("error") shouldBe defined
    }

    checkStreams(out, err, {
      case (o, e) =>
        if (enforceEmptyOutputStream) o shouldBe empty
        e shouldBe empty
    })
  }

  it should "support application errors" in {
    val (out, err) = withActionContainer() { c =>
      val code = """
                   | struct Result: Codable{
                   |    let error: String?
                   | }
                   | func main(respondWith: (Result?, Error?) -> Void) -> Void {
                   |    respondWith(Result(error: "sorry"), nil)
                   | }
                 """.stripMargin

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200) // action writer returning an error is OK

      runRes shouldBe defined
      runRes should be(Some(JsObject("error" -> JsString("sorry"))))
    }

    checkStreams(out, err, {
      case (o, e) =>
        if (enforceEmptyOutputStream) o shouldBe empty
        e shouldBe empty
    })
  }

  it should "support pre-compiled binary in a zip file" in {
    val zip = new File(TestUtils.getTestActionFilename(swiftBinaryName)).toPath
    val code = ResourceHelpers.readAsBase64(zip)

    val (out, err) = withActionContainer() { c =>
      val (initCode, initRes) = c.init(initPayload(code))
      initCode should be(200)

      val args = JsObject()
      val (runCode, runRes) = c.run(runPayload(args))

      runCode should be(200)
      runRes.get shouldBe JsObject("greeting" -> (JsString("Hello stranger!")))
    }

    checkStreams(out, err, {
      case (o, e) =>
        if (enforceEmptyOutputStream) o shouldBe empty
        e shouldBe empty
    })
  }

  // Helpers specific to swift actions
  override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
    withContainer(swiftContainerImageName, env)(code)
  }

}
