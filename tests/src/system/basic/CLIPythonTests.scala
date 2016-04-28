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

package system.basic;

import java.io.File
import scala.collection.mutable.ListBuffer
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.ParallelTestExecution
import org.scalatest.TestData
import org.scalatest.junit.JUnitRunner
import common.DeleteFromCollection
import common.RunWskAdminCmd
import common.RunWskCmd
import common.TestUtils
import common.TestUtils._
import common.Wsk
import common.WskAction
import common.WskProps
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.PimpedAny
import common.TestHelpers
import common.WskTestHelpers
import common.WskProps

@RunWith(classOf[JUnitRunner])
class CLIPythonTests
    extends TestHelpers
    with WskTestHelpers
    with Matchers {

    implicit val wskprops = WskProps()
    val wsk = new Wsk()


    behavior of "Native Python Action"

    it should "invoke a blocking action and get the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "basicInvoke"
            assetHelper.withCleaner(wsk.action, name) {
                (action, _) => action.create(name, Some(TestUtils.getCatalogFilename("samples/hello.py")))
            }
            wsk.action.invoke(name, Map("name" -> "Prince".toJson), blocking = true, result = true)
                .stdout should include regex ("""Prince""")
    }
}
