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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.json.JsObject

@RunWith(classOf[JUnitRunner])
class NodeJs8ActionContainerTests extends NodeJsActionContainerTests {

  override lazy val nodejsContainerImageName = "action-nodejs-v8"

  it should "support async and await" in {
    withNodeJsContainer { c =>
      val code = """
                   | const util = require('util');
                   | const fs = require('fs');
                   |
                   | const stat = util.promisify(fs.stat);
                   |
                   | async function main() {
                   |   const stats = await stat('.');
                   |   return stats
                   | }
                 """.stripMargin;

      val (initCode, _) = c.init(initPayload(code))
      initCode should be(200)

      val (runCode, runRes) = c.run(runPayload(JsObject()))
      runCode should be(200) // action writer returning an error is OK

      runRes shouldBe defined
      runRes.get.fields.get("uid") shouldBe defined
    }
  }

}
