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

package system.rest

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

import spray.json._
import DefaultJsonProtocol._
/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class GoCLINginxTests extends FlatSpec with Matchers with RestUtil {

    val ProductName = "OpenWhisk_CLI"
    val DownloadLinkGoCli = "cli/go/download"
    val OperatingSystems = List("mac", "linux", "windows")
    val Architectures = List("386", "amd64")
    val ServiceURL = getServiceURL()

    it should s"respond to all files in root directory" in {
        val response = RestAssured.given().config(sslconfig).
            get(s"$ServiceURL/$DownloadLinkGoCli")
        response.statusCode should be(200)
        val responseString = response.body.asString
        for (os <- OperatingSystems) {
            responseString.contains(s"""<a href="$os/">$os/</a>""") should be(true)
        }
        responseString.contains("""<a href="content.json">content.json</a>""") should be(true)
    }

    it should "respond to all operating systems and architectures in HTML index" in {
        for (os <- OperatingSystems) {
            val response = RestAssured.given().config(sslconfig).
              get(s"$ServiceURL/$DownloadLinkGoCli/$os")
            response.statusCode should be(200)
            val responseString = response.body.asString
            for (arch <- Architectures) {
                responseString.contains(s"""<a href="$arch/">$arch/</a>""") should be(true)
            }
        }
    }

    it should "respond to the download paths in content.json" in {
        val response = RestAssured.given().config(sslconfig).
          get(s"$ServiceURL/$DownloadLinkGoCli/content.json")
        response.statusCode should be(200)
        val jsObj = response.body.asString.parseJson.asJsObject.fields("cli").asJsObject
        jsObj shouldBe getExpectedJSONIndex
        val urls = Seq(
            jsObj.fields("mac").asJsObject.fields("386").asJsObject.fields("path").convertTo[String],
            jsObj.fields("mac").asJsObject.fields("default").asJsObject.fields("path").convertTo[String],
            jsObj.fields("mac").asJsObject.fields("amd64").asJsObject.fields("path").convertTo[String],
            jsObj.fields("linux").asJsObject.fields("386").asJsObject.fields("path").convertTo[String],
            jsObj.fields("linux").asJsObject.fields("default").asJsObject.fields("path").convertTo[String],
            jsObj.fields("linux").asJsObject.fields("amd64").asJsObject.fields("path").convertTo[String],
            jsObj.fields("windows").asJsObject.fields("386").asJsObject.fields("path").convertTo[String],
            jsObj.fields("windows").asJsObject.fields("default").asJsObject.fields("path").convertTo[String],
            jsObj.fields("windows").asJsObject.fields("amd64").asJsObject.fields("path").convertTo[String]
        )
        for (url <- urls) {
            RestAssured.given().config(sslconfig).
              get(s"$ServiceURL/$DownloadLinkGoCli/$url").statusCode should be(200)
        }
    }

    def getExpectedJSONIndex = JsObject(
        "mac" -> JsObject(
            "386" -> JsObject(
                "path" -> JsString("mac/386/OpenWhisk_CLI-mac-32bit.zip")
            ),
            "default" -> JsObject(
                "path" -> JsString("mac/amd64/OpenWhisk_CLI-mac.zip")
            ),
            "amd64" -> JsObject(
                "path" -> JsString("mac/amd64/OpenWhisk_CLI-mac.zip")
            )
        ),
        "linux" -> JsObject(
            "386" -> JsObject(
                "path" -> JsString("linux/386/OpenWhisk_CLI-linux-32bit.tgz")
            ),
            "default" -> JsObject(
                "path" -> JsString("linux/amd64/OpenWhisk_CLI-linux.tgz")
            ),
            "amd64" -> JsObject(
                "path" -> JsString("linux/amd64/OpenWhisk_CLI-linux.tgz")
            )
        ),
        "windows" -> JsObject(
            "386" -> JsObject(
                "path" -> JsString("windows/386/OpenWhisk_CLI-windows-32bit.zip")
            ),
            "default" -> JsObject(
                "path" -> JsString("windows/amd64/OpenWhisk_CLI-windows.zip")
            ),
            "amd64" -> JsObject(
                "path" -> JsString("windows/amd64/OpenWhisk_CLI-windows.zip")
            )
        )
    )
}
