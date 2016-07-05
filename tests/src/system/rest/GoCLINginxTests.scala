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

    val DownloadLinkGoCli = "/cli/go/download"
    val MacArchitecture = List("386", "amd64", "arm", "arm64")
    val LinuxArchitecture = List("386", "amd64", "arm", "arm64", "mips64", "mips64le", "ppc64", "ppc64le")
    val WindowsArchitecture = List("386", "amd64")

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
        for (macArc <- MacArchitecture) {
            responseString.contains(s"""<a href="${macArc}/">${macArc}/</a>""") should be(true)
        }
    }

    //// Check the Linux download page
    it should s"respond to ${DownloadLinkGoCli}/linux" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + DownloadLinkGoCli + "/linux")

        response.statusCode should be(200)
        val responseString = response.body.asString
        for (linuxArc <- LinuxArchitecture) {
            responseString.contains(s"""<a href="${linuxArc}/">${linuxArc}/</a>""") should be(true)
        }
    }

    //// Check the Windows download page
    it should s"respond to ${DownloadLinkGoCli}/windows" in {
        val response = RestAssured.given().config(sslconfig).
            get(getServiceURL() + DownloadLinkGoCli + "/windows")

        response.statusCode should be(200)
        val responseString = response.body.asString
        for (winArc <- WindowsArchitecture) {
            responseString.contains(s"""<a href="${winArc}/">${winArc}/</a>""") should be(true)
        }
    }

    //// Check the all the download links for the Go Cli binaries available in Mac
    for (macArc <- MacArchitecture) {
        val macBinaryLink = s"/cli/go/download/mac/${macArc}/openwhisk-mac-${macArc}.tar.gz"
        it should "respond to " + macBinaryLink + " for the Mac download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + macBinaryLink)

            response.statusCode should be(200)
        }
    }

    //// Check the all the download links for the Go Cli binaries available in Linux
    for (linuxArc <- LinuxArchitecture) {
        val linuxBinaryLink = s"/cli/go/download/linux/${linuxArc}/openwhisk-linux-${linuxArc}.tar.gz"
        it should s"respond to ${linuxBinaryLink} for the Linux download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + linuxBinaryLink)

            response.statusCode should be(200)
        }
    }

    //// Check the all the download links for the Go Cli binaries available in Windows
    for (windowsArc <- WindowsArchitecture) {
        val windowsBinaryLink = s"/cli/go/download/windows/${windowsArc}/openwhisk-win-${windowsArc}.zip"
        it should s"respond to ${windowsBinaryLink} for the Windows download link" in {
            val response = RestAssured.given().config(sslconfig).
                get(getServiceURL() + windowsBinaryLink)

            response.statusCode should be(200)
        }
    }
}
