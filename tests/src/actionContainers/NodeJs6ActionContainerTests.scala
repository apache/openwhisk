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

import whisk.core.entity.NodeJS6Exec

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import spray.json.JsBoolean
import spray.json.JsObject

@RunWith(classOf[JUnitRunner])
class NodeJs6ActionContainerTests extends NodeJsActionContainerTests {

    override lazy val nodejsContainerImageName = "nodejs6action"

    override def exec(code: String) = NodeJS6Exec(code)

    behavior of nodejsContainerImageName

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

        checkStreams(out, err, {
            case (o, e) =>
                o shouldBe empty
                e shouldBe empty
        })
    }

}
