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
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import common.TestHelpers
import common.TestUtils
import common.TestUtils._
import common.WskOperations
import common.WskProps
import common.WskTestHelpers
import io.restassured.RestAssured
import spray.json._
import spray.json.DefaultJsonProtocol._
import system.rest.RestUtil

/**
 * Basic tests of the download link for Go CLI binaries
 */
@RunWith(classOf[JUnitRunner])
abstract class ApiGwEndToEndTests
    extends FlatSpec
    with Matchers
    with RestUtil
    with TestHelpers
    with WskTestHelpers
    with BeforeAndAfterAll {

  implicit val wskprops: common.WskProps = WskProps()
  val wsk: WskOperations
  lazy val namespace: String = wsk.namespace.whois()
  val createCode: Int

  // Custom CLI properties file
  val cliWskPropsFile: java.io.File = File.createTempFile("wskprops", ".tmp")

  /*
   * Create a CLI properties file for use by the tests
   */
  override def beforeAll: Unit = {
    cliWskPropsFile.deleteOnExit()
    val wskprops = WskProps(token = "SOME TOKEN")
    wskprops.writeFile(cliWskPropsFile)
    println(s"wsk temporary props file created here: ${cliWskPropsFile.getCanonicalPath()}")
  }

  def verifyAPICreated(rr: RunResult): Unit = {
    rr.stdout should include("ok: created API")
    val apiurl = rr.stdout.split("\n")(1)
    println(s"apiurl: '$apiurl'")
  }

  def verifyAPIList(rr: RunResult,
                    actionName: String,
                    testurlop: String,
                    testapiname: String,
                    testbasepath: String,
                    testrelpath: String): Unit = {
    rr.stdout should include("ok: APIs")
    rr.stdout should include regex (s"$actionName\\s+$testurlop\\s+$testapiname\\s+")
    rr.stdout should include(testbasepath + testrelpath)
  }

  def verifyAPISwaggerCreated(rr: RunResult): Unit = {
    rr.stdout should include("ok: created API")
  }

  def writeSwaggerFile(rr: RunResult): File = {
    val swaggerfile = File.createTempFile("api", ".json")
    swaggerfile.deleteOnExit()
    val bw = new BufferedWriter(new FileWriter(swaggerfile))
    bw.write(rr.stdout)
    bw.close()
    swaggerfile
  }

  def getSwaggerApiUrl(rr: RunResult): String = rr.stdout.split("\n")(1)

  behavior of "Wsk api"

  it should s"create an API and successfully invoke that API" in {
    val testName = "APIGW_HEALTHTEST1"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_echo"
    val urlqueryparam = "name"
    val urlqueryvalue = testName

    try {
      println("Namespace: " + namespace)

      // Delete any lingering stale api from previous run that may not have been deleted properly
      wsk.api.delete(
        basepathOrApiName = testbasepath,
        expectedExitCode = DONTCARE_EXIT,
        cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))

      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo-web-http.js")
      println("action creation Namespace: " + namespace)
      wsk.action.create(
        name = actionName,
        artifact = Some(file),
        expectedExitCode = createCode,
        annotations = Map("web-export" -> true.toJson))

      println("creation Namespace: " + namespace)
      // Create the API
      var rr = wsk.api.create(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        responsetype = Some("http"),
        cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
      verifyAPICreated(rr)

      // Validate the API was successfully created
      // List result will look like:
      // ok: APIs
      // Action                            Verb             API Name  URL
      // /_//whisk.system/utils/echo          get  APIGW_HEALTHTEST1 API Name  http://172.17.0.1:9001/api/ab9082cd-ea8e-465a-8a65-b491725cc4ef/APIGW_HEALTHTEST1_bp/path
      rr = wsk.api.list(
        basepathOrApiName = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
      verifyAPIList(rr, actionName, testurlop, testapiname, testbasepath, testrelpath)

      // Recreate the API using a JSON swagger file
      rr = wsk.api.get(basepathOrApiName = Some(testbasepath), cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
      val swaggerfile = writeSwaggerFile(rr)

      // Delete API to that it can be recreated again using the generated swagger file
      val deleteApiResult = wsk.api.delete(
        basepathOrApiName = testbasepath,
        expectedExitCode = DONTCARE_EXIT,
        cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))

      // Create the API again, but use the swagger file this time
      rr = wsk.api
        .create(swagger = Some(swaggerfile.getAbsolutePath()), cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
      verifyAPISwaggerCreated(rr)
      val swaggerapiurl = getSwaggerApiUrl(rr)
      println(s"Returned api url: '${swaggerapiurl}'")

      // Call the API URL and validate the results
      val start = java.lang.System.currentTimeMillis
      val apiToInvoke = s"$swaggerapiurl?$urlqueryparam=$urlqueryvalue&guid=$start"
      println(s"Invoking: '${apiToInvoke}'")
      val response = org.apache.openwhisk.utils.retry({
        val response = RestAssured.given().config(sslconfig).get(s"$apiToInvoke")
        println("URL invocation response status: " + response.statusCode)
        response.statusCode should be(200)
        response
      }, 6, Some(2.second))
      val end = java.lang.System.currentTimeMillis
      val elapsed = end - start
      println("Elapsed time (milliseconds) for a successful response: " + elapsed)
      val responseString = response.body.asString
      println("URL invocation response: " + responseString)
      responseString.parseJson.asJsObject.fields(urlqueryparam).convertTo[String] should be(urlqueryvalue)

    } finally {
      println("Deleting action: " + actionName)
      val finallydeleteActionResult = wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      println("Deleting API: " + testbasepath)
      val finallydeleteApiResult = wsk.api.delete(
        basepathOrApiName = testbasepath,
        expectedExitCode = DONTCARE_EXIT,
        cliCfgFile = Some(cliWskPropsFile.getCanonicalPath()))
    }
  }
}
