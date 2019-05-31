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

import io.restassured.RestAssured

import spray.json._
import DefaultJsonProtocol._

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class GoCLINginxTests extends FlatSpec with Matchers with RestUtil {
  val DownloadLinkGoCli = "cli/go/download"
  val ServiceURL = getServiceURL()

  it should s"respond to all files in root directory" in {
    val response = RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli")
    response.statusCode should be(200)
    val responseString = response.body.asString
    responseString should include("""<a href="content.json">content.json</a>""")
    val responseJSON = RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli/content.json")
    responseJSON.statusCode should be(200)
    val cli = responseJSON.body.asString.parseJson.asJsObject
      .fields("cli")
      .convertTo[Map[String, Map[String, Map[String, String]]]]
    cli.foreach {
      case (os, arch) => responseString should include(s"""<a href="$os/">$os/</a>""")
    }
  }

  it should "respond to all operating systems and architectures in HTML index" in {
    val responseJSON = RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli/content.json")
    responseJSON.statusCode should be(200)
    val cli = responseJSON.body.asString.parseJson.asJsObject
      .fields("cli")
      .convertTo[Map[String, Map[String, Map[String, String]]]]
    cli.foreach {
      case (os, arch) =>
        val response = RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli/$os")
        response.statusCode should be(200)
        val responseString = response.body.asString
        arch.foreach {
          case (arch, path) =>
            if (arch != "default") {
              responseString should include(s"""<a href="$arch/">$arch/</a>""")
            }
        }
    }
  }

  it should "respond to the download paths in content.json" in {
    val response = RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli/content.json")
    response.statusCode should be(200)
    val cli =
      response.body.asString.parseJson.asJsObject.fields("cli").convertTo[Map[String, Map[String, Map[String, String]]]]
    cli.values.flatMap(_.values).flatMap(_.values).foreach { path =>
      RestAssured.given().config(sslconfig).get(s"$ServiceURL/$DownloadLinkGoCli/$path").statusCode should be(200)
    }
  }
}
