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
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import ActionContainer.withContainer
import spray.json._

@RunWith(classOf[JUnitRunner])
class DockerSkeletonContainerTests extends FlatSpec with Matchers {

    val dockerSdkContainerImageName = "whisk/dockerskeleton"

    // Helpers specific to dockerskeleton
    def withDockerSdkContainer(code: ActionContainer => Unit) = {
        withContainer(dockerSdkContainerImageName)(code)
    }

    def runPayload(args: JsValue) = JsObject("value" -> args)

    behavior of "whisk/dockerskeleton"

    it should "support valid flows without init" in {
        val (out, err) = withDockerSdkContainer { c =>
            val args = List(
                JsObject(),
                JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))))

            for (arg <- args) {
                val (runCode, out) = c.run(runPayload(arg))

                runCode should be(200)
                out shouldBe defined
                out.get shouldBe a[JsObject]

                val JsObject(fields) = out.get
                fields.contains("msg") should be(true)
                fields("msg") should be(JsString("Hello from arbitrary C program!"))
                fields("args") should be(arg)
            }
        }

        out.trim shouldBe empty
        err.trim shouldBe empty
    }

    it should "support valid flows with init" in {
        val (out, err) = withDockerSdkContainer { c =>
            val initPayload = JsObject("dummy init" -> JsString("dummy value"))
            val (initCode, _) = c.init(initPayload)

            initCode should be(200)

            val args = List(
                JsObject(),
                JsObject("numbers" -> JsArray(JsNumber(42), JsNumber(1))))

            for (arg <- args) {
                val (runCode, out) = c.run(runPayload(arg))

                runCode should be(200)
                out shouldBe defined
                out.get shouldBe a[JsObject]

                val JsObject(fields) = out.get
                fields.contains("msg") should be(true)
                fields("msg") should be(JsString("Hello from arbitrary C program!"))
                fields("args") should be(arg)
            }
        }

        out.trim shouldBe empty
        err.trim shouldBe empty
    }

}
