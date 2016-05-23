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
import spray.json.{JsBoolean, JsObject}

@RunWith(classOf[JUnitRunner])
class NodeJs6ActionContainerTests extends NodeJsActionContainerTests {

    override val nodejsContainerImageName = "whisk/nodejs6action"

    behavior of "whisk/nodejs6action"

    it should "support default function parameters" in {
        val (out, err) = withNodeJsContainer { c =>
            val code = """
                         | function main(args) {
                         |     let foo = 3;
                         |     return {isValid: (function (a, b = 2) {return a === 3 && b === 2;}(foo))};
                         | }
                       """.stripMargin

            val (initCode, _) = c.init(initPayload(code))
            initCode should be(200)

            val (runCode, runRes) = c.run(runPayload(JsObject()))
            runCode should be(200)
            runRes should be(Some(JsObject("isValid" -> JsBoolean(true))))

        }

        filtered(out).trim shouldBe empty
        filtered(err).trim shouldBe empty
    }

}
