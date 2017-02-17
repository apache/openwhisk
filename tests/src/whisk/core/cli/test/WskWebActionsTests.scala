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
 * Tests web actions.
 */
@RunWith(classOf[JUnitRunner])
class WskWebActionsTests
    extends TestHelpers
    with WskTestHelpers
    with RestUtil {

    val MAX_URL_LENGTH = 8192 // 8K matching nginx default

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val namespace = WskAdmin.getUser(wskprops.authKey)._2

    behavior of "Wsk Web Actions"

    /**
     * Tests web actions, plus max url limit.
     */
    it should "create a web action accessible via HTTPS" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "webaction"
            val file = Some(TestUtils.getTestActionFilename("echo.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, annotations = Map("web-export" -> true.toJson))
            }

            val host = getServiceURL()
            val requestPath = host + s"/api/v1/experimental/web/$namespace/default/webaction.text/a?a="
            val padAmount = MAX_URL_LENGTH - requestPath.length
            Seq(("A", 200),
                ("A" * padAmount, 200),
                // ideally the bad case is just +1 but there's some differences
                // in how characters are counted i.e., whether these count "https://:443"
                // or not; it seems sufficient to test right around the boundary
                ("A" * (padAmount + 100), 414))
                .foreach {
                    case (pad, code) =>
                        val url = (requestPath + pad)
                        val response = RestAssured.given().config(sslconfig).get(url)
                        val responseCode = response.statusCode

                        withClue(s"response code: $responseCode, url length: ${url.length}, pad amount: ${pad.length}, url: $url") {
                            responseCode shouldBe code
                            if (code == 200) {
                                response.body().asString() shouldBe pad
                            } else {
                                response.body().asString() should include("414 Request-URI Too Large") // from nginx
                            }
                        }
                }
    }

    /**
     * Tests web action requiring authentication.
     */
    it should "create a web action requiring authentication accessible via HTTPS" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            val name = "webaction"
            val file = Some(TestUtils.getTestActionFilename("echo.js"))

            assetHelper.withCleaner(wsk.action, name) {
                (action, _) =>
                    action.create(name, file, annotations = Map("web-export" -> true.toJson, "require-whisk-auth" -> true.toJson))
            }

            val host = getServiceURL()
            val url = host + s"/api/v1/experimental/web/$namespace/default/webaction.text/__ow_meta_namespace"

            val unauthorizedResponse = RestAssured.given().config(sslconfig).get(url)
            unauthorizedResponse.statusCode shouldBe 401

            val authorizedResponse = RestAssured
                .given()
                .config(sslconfig)
                .auth().preemptive().basic(wskprops.authKey.split(":")(0), wskprops.authKey.split(":")(1))
                .get(url)
            authorizedResponse.statusCode shouldBe 200
            authorizedResponse.body().asString() shouldBe namespace
    }
}
