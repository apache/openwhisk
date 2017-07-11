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

package apigw.healthtests

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

import scala.concurrent.duration.DurationInt
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

//import com.jayway.restassured.RestAssured

import common.WskAdmin
import common.TestHelpers
import common.TestUtils
import common.TestUtils.DONTCARE_EXIT
import common.rest.WskRest
import common.WskProps
import common.WskTestHelpers
import common.rest.RestResult
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil

import spray.http.StatusCodes.OK

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
class ApiGwRestEndToEndTests
    extends FlatSpec
    with Matchers
    with RestUtil
    with TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll {

    val systemId = "whisk.system"
    implicit val wskprops = WskProps(authKey = WskAdmin.listKeys(systemId)(0)._1, namespace = systemId)
    val wsk = new WskRest

    behavior of "Wsk api"

    it should s"create an API and successfully invoke that API" in {
        val testName = Random.alphanumeric.take(7).mkString.toLowerCase
        val testbasepath = "/" + testName + "_bp"
        val testrelpath = "/path"
        val testurlop = "get"
        val testapiname = testName + " API Name"
        val actionName = testName + "_echo"
        val urlqueryparam = "name"
        val urlqueryvalue = testName

        try {
            // Create the action for the API.  It must be a "web-action" action.
            val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
            wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = OK.intValue, annotations = Map("web-export" -> true.toJson))

            // Create the API
            var rr = wsk.api.create(
                basepath = Some(testbasepath),
                relpath = Some(testrelpath),
                operation = Some(testurlop),
                action = Some(actionName),
                apiname = Some(testapiname),
                responsetype = Some("http")
            )

            rr.statusCode shouldBe OK
            rr.respData should include regex (s""""action": "$actionName"""")
            rr.respData should include regex (s""""title": "$testapiname"""")
            rr.respData should include regex (""""operationId": "getPath"""")
            rr.respData should include regex (s""""basePath": "$testbasepath"""")

            // Validate the API was successfully created
            rr = wsk.api.list(
                basepathOrApiName = Some(testbasepath),
                relpath = Some(testrelpath),
                operation = Some(testurlop)
            )

            rr.statusCode shouldBe OK
            rr.respData should include regex (s""""action": "$actionName"""")
            rr.respData should include regex (s""""title": "$testapiname"""")
            rr.respData should include regex (""""operationId": "getPath"""")
            rr.respData should include regex (s""""basePath": "$testbasepath"""")

            // Recreate the API using a JSON swagger file
            var result = wsk.api.get(
                basepathOrApiName = Some(testbasepath)
            )
            val apiValue = RestResult.getFieldJsObject(result, "value")
            val apiURL = RestResult.getField(apiValue, "gwApiUrl")

            val respData = result.toString
            respData should include regex (s""""action":"$actionName"""")
            respData should include regex (s""""title":"$testapiname"""")
            respData should include regex (""""operationId":"getPath"""")
            respData should include regex (s""""basePath":"$testbasepath"""")
            apiURL should include regex (s":9001/api/${wskprops.authKey.split(":")(0)}$testbasepath")

            val swaggerfile = File.createTempFile("api", ".json")
            swaggerfile.deleteOnExit()
            val bw = new BufferedWriter(new FileWriter(swaggerfile))
            bw.write(RestResult.getFieldJsValue(apiValue, "apidoc").toString)
            bw.close()

            // Delete API to that it can be recreated again using the generated swagger file
            val deleteApiResult = wsk.api.delete(
                basepathOrApiName = Some(testbasepath),
                expectedExitCode = OK.intValue
            )

            // Create the API again, but use the swagger file this time
            rr = wsk.api.create(
                swagger = Some(swaggerfile.getAbsolutePath())
            )

            rr.statusCode shouldBe OK
            rr.respData should include regex (s""""action": "$actionName"""")
            rr.respData should include regex (s""""title": "$testapiname"""")
            rr.respData should include regex (""""operationId": "getPath"""")
            rr.respData should include regex (s""""basePath": "$testbasepath"""")

            // Call the API URL and validate the results
            val start = java.lang.System.currentTimeMillis
            val map = Map[String, String]("guid" -> s"$start".toString, s"$urlqueryparam" -> s"$urlqueryvalue".toString)
            val response = whisk.utils.retry({
                val response = wsk.api.getApi(testbasepath, map)
                println("URL invocation response status: " + response.statusCode.intValue)
                response.statusCode.intValue shouldBe OK.intValue
                response
            }, 6, Some(2.second))

            val end = java.lang.System.currentTimeMillis
            val elapsed = end - start
            println("Elapsed time (milliseconds) for a successful response: " + elapsed)
            val responseString = response.respBody
            println("URL invocation response: " + responseString)
            responseString.fields(urlqueryparam).convertTo[String] should be(urlqueryvalue)
        } finally {
            println("Deleting API: " + testbasepath)
            val finallydeleteApiResult = wsk.api.delete(
                basepathOrApiName = Some(testbasepath),
                expectedExitCode = DONTCARE_EXIT
            )
            println("Deleting action: " + actionName)
            val finallydeleteActionResult = wsk.action.delete(actionName, expectedExitCode = DONTCARE_EXIT)
        }
    }
}
