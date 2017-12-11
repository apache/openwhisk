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

package system.rest

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import com.jayway.restassured.RestAssured

/**
 * Basic tests of the download redirects for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class GoCLINginxTests extends FlatSpec with Matchers with RestUtil {
  val ProductName = "OpenWhisk_CLI"
  val DownloadLinkGoCli = "cli/go/download"
  val OperatingSystems = List("mac", "linux", "windows")
  val Architectures = List("386", "amd64")
  val ServiceURL = getServiceURL()

  it should s"check redirect for root directory request" in {
    val response =
      RestAssured.given().config(sslconfig).redirects().follow(false).get(s"$ServiceURL/$DownloadLinkGoCli")
    response.statusCode should be(301)
    response.header("Location") should include("https://github.com/apache/incubator-openwhisk-cli/releases")
    println(response.header("Location"))
  }

  it should "check redirect exists for all operating system and architecture pairs" in {
    for (os <- OperatingSystems) {
      for (arch <- Architectures) {
        val response = RestAssured
          .given()
          .config(sslconfig)
          .redirects()
          .follow(false)
          .get(s"$ServiceURL/$DownloadLinkGoCli/$os/$arch")
        response.statusCode should be(301)
        response.header("Location") should include(s"$os-$arch")
      }
    }
  }
}
