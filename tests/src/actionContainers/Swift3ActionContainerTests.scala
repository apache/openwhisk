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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.json.JsObject
import spray.json.JsString

@RunWith(classOf[JUnitRunner])
class Swift3ActionContainerTests extends SwiftActionContainerTests {

    override val enforceEmptyOutputStream = false
    override lazy val swiftContainerImageName = "whisk/swift3action"

    ignore should "properly use KituraNet and Dispatch" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                | import KituraNet
                | import Foundation
                | import Dispatch
                | func main(args:[String: Any]) -> [String:Any] {
                |       print("Entering Swift3ActionContainer KituraNet test")
                |       let Retries = 3
                |       var respStr = "No response"
                |       var attempts = 0
                |       while attempts < Retries {
                |           let group = dispatch_group_create()
                |           dispatch_group_async(group, dispatch_get_global_queue(0,0), {
                |               HTTP.get("http://httpbin.org/get", callback: { response in
                |                   if let response = response {
                |                       print("Status code is \(response.statusCode)")
                |                           do {
                |                           if let str = try response.readString() {
                |                                   respStr = str
                |                                   print("Got string2 \(str)")
                |                              } else {
                |                                   print("Could not read string")
                |                               }
                |                           } catch {
                |                               print("Error reading string body")
                |                           }
                |                   }
                |               })
                |           })
                |       let maxWait = dispatch_time(DISPATCH_TIME_NOW, Int64(10 * NSEC_PER_SEC))
                |       let code = dispatch_group_wait(group, maxWait)
                |       if code == 0 {
                |           let data = respStr.data(using: NSUTF8StringEncoding, allowLossyConversion: true)!
                |           do {
                |               if let result = try NSJSONSerialization.jsonObject(with: data, options: []) as? [String: Any] {
                |                   return ["reply": result]
                |               }
                |           } catch {
                |                print("Error \(error)")
                |           }
                |           return ["reply": respStr, "code": code]
                |       } else {
                |           print("WAIT timed out or failed on \(attempts) attempts with code \(code), retrying")
                |           attempts = attempts + 1
                |       }
                |   }
                |   return ["status": "Exceeded \(Retries) tries, returning."]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val argss = List(
                JsObject("getUrl" -> JsString("https://httpbin.org/get")))

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
                o shouldBe empty
                e shouldBe empty
        })
    }

    ignore should "make Watson SDKs available to action authors" in {
        val (out, err) = withActionContainer() { c =>
            val code = """
                | import RestKit
                | import InsightsForWeather
                | import AlchemyVision
                |
                | func main(args: [String:Any]) -> [String:Any] {
                |   return ["message": "I compiled and was able to import Watson SDKs"]
                | }
            """.stripMargin

            val (initCode, _) = c.init(initPayload(code))

            initCode should be(200)

            val (runCode, out) = c.run(runPayload(JsObject()))
            runCode should be(200)
        }

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }
}
