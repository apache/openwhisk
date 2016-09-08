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

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class GoCLINginxTests extends FlatSpec with Matchers with RestUtil {

    val ProductName = "OpenWhisk_CLI"
    val DownloadLinkGoCli = "/cli/go/download"
    val Architectures = List("386", "amd64")

    //// Check the download page for all the Go Cli binaries
    it should s"respond to ${DownloadLinkGoCli}" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + DownloadLinkGoCli)

        response.statusCode should be(200)
        val responseString = response.body.asString
        responseString.contains("""<a href="mac/">mac/</a>""") should be(true)
        responseString.contains("""<a href="linux/">linux/</a>""") should be(true)
        responseString.contains("""<a href="windows/">windows/</a>""") should be(true)
    }

    //// Check the Mac download page
    it should s"respond to ${DownloadLinkGoCli}/mac" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + s"${DownloadLinkGoCli}/mac")

        response.statusCode should be(200)
        val responseString = response.body.asString
        for (arch <- Architectures) {
            responseString.contains(s"""<a href="${arch}/">${arch}/</a>""") should be(true)
        }
    }

    //// Check the Linux download page
    it should s"respond to ${DownloadLinkGoCli}/linux" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + DownloadLinkGoCli + "/linux")

        response.statusCode should be(200)
        val responseString = response.body.asString
        for (arch <- Architectures) {
            responseString.contains(s"""<a href="${arch}/">${arch}/</a>""") should be(true)
        }
    }

    //// Check the Windows download page
    it should s"respond to ${DownloadLinkGoCli}/windows" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + DownloadLinkGoCli + "/windows")

        response.statusCode should be(200)
        val responseString = response.body.asString
        for (arch <- Architectures) {
            responseString.contains(s"""<a href="${arch}/">${arch}/</a>""") should be(true)
        }
    }

    // Check the all the download links for the Go Cli binaries available in Mac
    for (arch <- Architectures) {
        val macBinaryLink = getCompressedName("mac", arch, "zip")
        it should "respond to " + macBinaryLink + " for the Mac download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + macBinaryLink)

            response.statusCode should be(200)
        }
    }

    // Check the all the download links for the Go Cli binaries available in Linux
    for (arch <- Architectures) {
        val linuxBinaryLink = getCompressedName("linux", arch, "tgz")
        it should s"respond to ${linuxBinaryLink} for the Linux download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + linuxBinaryLink)

            response.statusCode should be(200)
        }
    }

    //// Check the all the download links for the Go Cli binaries available in Windows
    for (arch <- Architectures) {
        val windowsBinaryLink = getCompressedName("windows", arch, "zip")
        it should s"respond to ${windowsBinaryLink} for the Windows download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + windowsBinaryLink)

            response.statusCode should be(200)
        }
    }

    def getCompressedName(os: String, arch: String, ext: String) = if (arch == "amd64") {
        s"/cli/go/download/${os}/${arch}/${ProductName}-${os}.${ext}"
    } else if (arch == "386") {
        s"/cli/go/download/${os}/${arch}/${ProductName}-${os}-32bit.${ext}"
    } else {
        s"/cli/go/download/${os}/${arch}/${ProductName}-${os}-${arch}.${ext}"
    }
}
