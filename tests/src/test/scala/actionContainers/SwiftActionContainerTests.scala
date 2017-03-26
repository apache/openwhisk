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

package actionContainers

import java.io.File

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import common.WskActorSystem
import spray.json.JsObject
import spray.json.JsString
import common.TestUtils

@RunWith(classOf[JUnitRunner])
class SwiftActionContainerTests extends BasicActionRunnerTests with WskActorSystem {

    // note: "out" will likely not be empty in some swift build as the compiler
    // prints status messages and there doesn't seem to be a way to quiet them
    val enforceEmptyOutputStream = false
    lazy val swiftContainerImageName = "swift3action"
    lazy val envCode = makeEnvCode("ProcessInfo.processInfo")

    def makeEnvCode(processInfo: String) = ("""
         |func main(args: [String: Any]) -> [String: Any] {
         |     let env = """ + processInfo + """.environment
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
         |     return ["api_host": a, "api_key": b, "namespace": c, "action_name": d, "activation_id": e, "deadline": f]
         |}
         """).stripMargin

    lazy val errorCode = """
                | // You need an indirection, or swiftc detects the div/0
                | // at compile-time. Smart.
                | func div(x: Int, y: Int) -> Int {
                |     return x/y
                | }
                | func main(args: [String: Any]) -> [String: Any] {
                |     return [ "divBy0": div(x:5, y:0) ]
                | }
            """.stripMargin

    // Helpers specific to swift actions
    override def withActionContainer(env: Map[String, String] = Map.empty)(code: ActionContainer => Unit) = {
        withContainer(swiftContainerImageName, env)(code)
    }

    behavior of swiftContainerImageName

    // remove this test: it will not even compile under Swift 3 anymore
    // so it should not be possible to write an action that does not return
    // a [String:Any]
    /*testNotReturningJson(
        """
        |func main(args: [String: Any]) -> String {
        |    return "not a json object"
        |}
        """.stripMargin)
    */

    testEcho(Seq {
        ("swift", """
         | import Foundation
         |
         | extension FileHandle : TextOutputStream {
         |     public func write(_ string: String) {
         |         guard let data = string.data(using: .utf8) else { return }
         |         self.write(data)
         |     }
         | }
         |
         | func main(args: [String: Any]) -> [String: Any] {
         |     print("hello stdout")
         |     var standardError = FileHandle.standardError
         |     print("hello stderr", to: &standardError)
         |     return args
         | }
        """.stripMargin)
    })

    testUnicode(Seq {
        ("swift", """
         | func main(args: [String: Any]) -> [String: Any] {
         |     if let str = args["delimiter"] as? String {
         |         let msg = "\(str) ☃ \(str)"
         |         print(msg)
         |         return [ "winter" : msg ]
         |     } else {
         |         return [ "error" : "no delimiter" ]
         |     }
         | }
         """.stripMargin.trim)
    })

    testEnv(Seq {
        ("swift", envCode)
    }, enforceEmptyOutputStream)

    it should "support actions using non-default entry points" in {
        withActionContainer() { c =>
            val code = """
                | func niam(args: [String: Any]) -> [String: Any] {
                |     return [ "result": "it works" ]
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
            val code = errorCode

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

    it should "log compilation errors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
              | 10 PRINT "Hello!"
              | 20 GOTO 10
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should not be (200)
        }

        checkStreams(out, err, {
            case (o, e) =>
                if (enforceEmptyOutputStream) o shouldBe empty
                e.toLowerCase should include("error")
        })
    }

    it should "support application errors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                | func main(args: [String: Any]) -> [String: Any] {
                |     return [ "error": "sorry" ]
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
        val zip = new File(TestUtils.getTestActionFilename("helloSwift.zip")).toPath
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
                o shouldBe empty
                e shouldBe empty
        })
    }

    it should "properly use KituraNet and Dispatch" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                | import KituraNet
                | import Foundation
                | import Dispatch
                | func main(args:[String: Any]) -> [String:Any] {
                |       let retries = 3
                |       var resp = [String:Any]()
                |       var attempts = 0
                |       if let url = args["getUrl"] as? String {
                |           while attempts < retries {
                |               let group = DispatchGroup()
                |               let queue = DispatchQueue.global(qos: .default)
                |               group.enter()
                |               queue.async {
                |                   HTTP.get(url, callback: { response in
                |                       if let response = response {
                |                           do {
                |                               var jsonData = Data()
                |                               try response.readAllData(into: &jsonData)
                |                               if let dic = WhiskJsonUtils.jsonDataToDictionary(jsonData: jsonData) {
                |                                   resp = dic
                |                               } else {
                |                                   resp = ["error":"response from server is not JSON"]
                |                               }
                |                           } catch {
                |                              resp["error"] = error.localizedDescription
                |                           }
                |                       }
                |                       group.leave()
                |                   })
                |               }
                |            switch group.wait(timeout: DispatchTime.distantFuture) {
                |                case DispatchTimeoutResult.success:
                |                    resp["attempts"] = attempts
                |                    return resp
                |                case DispatchTimeoutResult.timedOut:
                |                    attempts = attempts + 1
                |            }
                |        }
                |     }
                |     return ["status":"Exceeded \(retries) attempts, aborting."]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val argss = List(
                JsObject("getUrl" -> JsString("https://openwhisk.ng.bluemix.net/api/v1")))

            for (args <- argss) {
                val (runCode, out) = c.run(runPayload(args))
                runCode should be(200)
            }
        }

        // in side try catch finally print (out file)
        // in catch block an error has occurred, get docker logs and print
        // throw

        checkStreams(out, err, {
            case (o, e) =>
                //o shouldBe empty
                e shouldBe empty
        })
    }

    it should "make Watson SDKs available to action authors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                | import RestKit
                | import WeatherCompanyData
                | import AlchemyVision
                |
                | func main(args: [String:Any]) -> [String:Any] {
                |     return ["message": "I compiled and was able to import Watson SDKs"]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val (runCode, out) = c.run(runPayload(JsObject()))
            runCode should be(200)
        }

        checkStreams(out, err, {
            case (o, e) =>
                //o shouldBe empty
                e shouldBe empty
        })
    }
}
