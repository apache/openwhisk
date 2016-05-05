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
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import spray.json._

import ActionContainer.withContainer

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class Swift3ActionContainerTests extends SwiftActionContainerTests {

    override val checkStdOutEmpty = false
    override val swiftContainerImageName = "whisk/swift3action"

    behavior of "whisk/swift3action"

    it should "properly use KituraNet and Dispatch" in {
        val (out, err) = withSwiftContainer { c =>
            val code = """
                | import Foundation
                | import Dispatch
                | import KituraNet
                | func main(args: [String: Any]) -> [String: Any] {
                |   var str = "No response"
                |   let url = args["getUrl"] as? String
                |   dispatch_sync(dispatch_get_global_queue(0,0)) {
                |       Http.get(url!) { response in
                |           do {
                |               if let response = response {
                |                   str = try response.readString()!
                |               }
                |           } catch {
                |               print("Error reading server response: \(error)")
                |           }
                |       }
                |   }
                |   var result: [String:Any]?
                |   let data = str.bridge().dataUsingEncoding(NSUTF8StringEncoding)!
                |   do {
                |       result = try NSJSONSerialization.jsonObject(with: data, options:[]) as? [String:Any]
                |   } catch {
                |      print("Error serializing server response: \(error)")
                |   }
                |   if let result = result {
                |       return result
                |   }
                |   return ["message":str]
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

        if (checkStdOutEmpty) out.trim shouldBe empty
        err.trim shouldBe empty
    }

}
