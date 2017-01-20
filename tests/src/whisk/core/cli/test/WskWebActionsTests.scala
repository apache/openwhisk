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

package whisk.core.cli.test

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil

/**
 * Tests for basic CLI usage. Some of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
class WskWebActionsTests
    extends TestHelpers
    with WskTestHelpers
    with RestUtil {

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val namespace = WskAdmin.getUser(wskprops.authKey)._2

    behavior of "Wsk Web Actions"

    it should "create a web action accessible via HTTPS" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "webaction"
            val file = Some(TestUtils.getTestActionFilename("echo.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, annotations = Map("web-export" -> true.toJson))
            }

            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + s"/api/v1/experimental/web/$namespace/default/webaction.text/a?a=A")

            response.statusCode() should be(200)
            response.body().asString() shouldBe "A"
    }

}
