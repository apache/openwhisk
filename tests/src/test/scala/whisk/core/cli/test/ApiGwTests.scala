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

package whisk.core.cli.test

import java.io.File
import java.io.BufferedWriter
import java.io.FileWriter

import org.junit.runner.RunWith

import org.scalatest.junit.JUnitRunner

import common.TestUtils._
import common.TestUtils
import common.WskProps

/**
 * Tests for testing the CLI "api" subcommand.  Most of these tests require a deployed backend.
 */
@RunWith(classOf[JUnitRunner])
abstract class ApiGwTests extends BaseApiGwTests {

  val clinamespace = wsk.namespace.whois()
  val createCode: Int

  def verifyBadCommands(rr: RunResult, badpath: String): Unit = {
    rr.stderr should include(s"'${badpath}' must begin with '/'")
  }

  def verifyBadCommandsDelete(rr: RunResult, badpath: String): Unit = {
    verifyBadCommands(rr, badpath)
  }

  def verifyBadCommandsList(rr: RunResult, badpath: String): Unit = {
    verifyBadCommands(rr, badpath)
  }

  def verifyInvalidCommands(rr: RunResult, badverb: String): Unit = {
    rr.stderr should include(s"'${badverb}' is not a valid API verb.  Valid values are:")
  }

  def verifyInvalidCommandsDelete(rr: RunResult, badverb: String): Unit = {
    verifyInvalidCommands(rr, badverb)
  }

  def verifyInvalidCommandsList(rr: RunResult, badverb: String): Unit = {
    verifyInvalidCommands(rr, badverb)
  }

  def verifyNonJsonSwagger(rr: RunResult, filename: String): Unit = {
    rr.stderr should include(s"Error parsing swagger file '${filename}':")
  }

  def verifyMissingField(rr: RunResult): Unit = {
    rr.stderr should include(s"Swagger file is invalid (missing basePath, info, paths, or swagger fields")
  }

  def verifyApiCreated(rr: RunResult): Unit = {
    rr.stdout should include("ok: created API")
  }

  def verifyApiList(rr: RunResult,
                    clinamespace: String,
                    actionName: String,
                    testurlop: String,
                    testbasepath: String,
                    testrelpath: String,
                    testapiname: String): Unit = {
    rr.stdout should include("ok: APIs")
    rr.stdout should include regex (s"Action:\\s+/${clinamespace}/${actionName}\n")
    rr.stdout should include regex (s"Verb:\\s+${testurlop}\n")
    rr.stdout should include regex (s"Base path:\\s+${testbasepath}\n")
    rr.stdout should include regex (s"Path:\\s+${testrelpath}\n")
    rr.stdout should include regex (s"API Name:\\s+${testapiname}\n")
    rr.stdout should include regex (s"URL:\\s+")
    rr.stdout should include(testbasepath + testrelpath)
  }

  def verifyApiBaseRelPath(rr: RunResult, testbasepath: String, testrelpath: String): Unit = {
    rr.stdout should include(testbasepath + testrelpath)
  }

  def verifyApiGet(rr: RunResult): Unit = {
    rr.stdout should include regex (s""""operationId":\\s+"getPathWithSub_pathsInIt"""")
  }

  def verifyApiFullList(rr: RunResult,
                        clinamespace: String,
                        actionName: String,
                        testurlop: String,
                        testbasepath: String,
                        testrelpath: String,
                        testapiname: String): Unit = {

    rr.stdout should include("ok: APIs")
    if (clinamespace == "") {
      rr.stdout should include regex (s"/[@\\w._\\-]+/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
    } else {
      rr.stdout should include regex (s"/${clinamespace}/${actionName}\\s+${testurlop}\\s+${testapiname}\\s+")
    }
    rr.stdout should include(testbasepath + testrelpath)

  }

  def verifyApiFullListDouble(rr: RunResult,
                              clinamespace: String,
                              actionName: String,
                              testurlop: String,
                              testbasepath: String,
                              testrelpath: String,
                              testapiname: String,
                              newEndpoint: String): Unit = {
    verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
    rr.stdout should include(testbasepath + newEndpoint)
  }

  def verifyApiDeleted(rr: RunResult): Unit = {
    rr.stdout should include("ok: deleted API")
  }

  def verifyApiDeletedRelpath(rr: RunResult, testrelpath: String, testbasepath: String, op: String = ""): Unit = {
    if (op != "")
      rr.stdout should include("ok: deleted " + testrelpath + " " + op.toUpperCase() + " from " + testbasepath)
    else
      rr.stdout should include("ok: deleted " + testrelpath + " from " + testbasepath)
  }

  def verifyApiNameGet(rr: RunResult, testbasepath: String, actionName: String, responseType: String = "json"): Unit = {
    rr.stdout should include(testbasepath)
    rr.stdout should include(s"${actionName}")
    rr.stdout should include regex (""""cors":\s*\{\s*\n\s*"enabled":\s*true""")
    rr.stdout should include regex (s""""target-url":\\s+.*${actionName}.${responseType}""")
  }

  def verifyInvalidSwagger(rr: RunResult): Unit = {
    rr.stderr should include(s"Swagger file is invalid")
  }

  def verifyApiOp(rr: RunResult, testurlop: String, testapiname: String): Unit = {
    rr.stdout should include regex (s"\\s+${testurlop}\\s+${testapiname}\\s+")
  }

  def verifyApiOpVerb(rr: RunResult, testurlop: String): Unit = {
    rr.stdout should include regex (s"Verb:\\s+${testurlop}")
  }

  def verifyInvalidKey(rr: RunResult): Unit = {
    rr.stderr should include("The supplied authentication is invalid")
  }

  behavior of "Wsk api"

  it should "reject an api commands with an invalid path parameter" in {
    val badpath = "badpath"

    var rr = apiCreate(
      basepath = Some("/basepath"),
      relpath = Some(badpath),
      operation = Some("GET"),
      action = Some("action"),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyBadCommands(rr, badpath)

    rr = apiDelete(
      basepathOrApiName = "/basepath",
      relpath = Some(badpath),
      operation = Some("GET"),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyBadCommandsDelete(rr, badpath)

    rr = apiList(
      basepathOrApiName = Some("/basepath"),
      relpath = Some(badpath),
      operation = Some("GET"),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyBadCommandsList(rr, badpath)
  }

  it should "reject an api commands with an invalid verb parameter" in {
    val badverb = "badverb"

    var rr = apiCreate(
      basepath = Some("/basepath"),
      relpath = Some("/path"),
      operation = Some(badverb),
      action = Some("action"),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyInvalidCommands(rr, badverb)

    rr = apiDelete(
      basepathOrApiName = "/basepath",
      relpath = Some("/path"),
      operation = Some(badverb),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyInvalidCommandsDelete(rr, badverb)

    rr = apiList(
      basepathOrApiName = Some("/basepath"),
      relpath = Some("/path"),
      operation = Some(badverb),
      expectedExitCode = ANY_ERROR_EXIT)
    verifyInvalidCommandsList(rr, badverb)
  }

  it should "reject an api create command that specifies a nonexistent configuration file" in {
    val configfile = "/nonexistent/file"

    val rr = apiCreate(swagger = Some(configfile), expectedExitCode = ANY_ERROR_EXIT)
    rr.stderr should include(s"Error reading swagger file '${configfile}'")
  }

  it should "reject an api create command specifying a non-JSON configuration file" in {
    val file = File.createTempFile("api.json", ".txt")
    file.deleteOnExit()
    val filename = file.getAbsolutePath()

    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("a=A")
    bw.close()

    val rr = apiCreate(swagger = Some(filename), expectedExitCode = ANY_ERROR_EXIT)
    verifyNonJsonSwagger(rr, filename)
  }

  it should "reject an api create command specifying a non-swagger JSON configuration file" in {
    val file = File.createTempFile("api.json", ".txt")
    file.deleteOnExit()
    val filename = file.getAbsolutePath()

    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("""|{
                    |   "swagger": "2.0",
                    |   "info": {
                    |      "title": "My API",
                    |      "version": "1.0.0"
                    |   },
                    |   "BADbasePath": "/bp",
                    |   "paths": {
                    |     "/rp": {
                    |       "get":{}
                    |     }
                    |   }
                    |}""".stripMargin)
    bw.close()

    val rr = apiCreate(swagger = Some(filename), expectedExitCode = ANY_ERROR_EXIT)
    verifyMissingField(rr)
  }

  it should "verify full list output" in {
    val testName = "CLI_APIGWTEST_RO1"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      println("cli namespace: " + clinamespace)
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      println("api create: " + rr.stdout)
      verifyApiCreated(rr)
      rr = apiList(
        basepathOrApiName = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        full = Some(true))
      println("api list: " + rr.stdout)
      verifyApiList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath)
    }
  }

  it should "verify successful creation and deletion of a new API" in {
    val testName = "CLI_APIGWTEST1"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path/with/sub_paths/in/it"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      println("cli namespace: " + clinamespace)

      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
      rr = apiGet(basepathOrApiName = Some(testbasepath))
      verifyApiGet(rr)
      val deleteresult = apiDelete(basepathOrApiName = testbasepath)
      verifyApiDeleted(deleteresult)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify get API name " in {
    val testName = "CLI_APIGWTEST3"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiGet(basepathOrApiName = Some(testapiname))
      verifyApiNameGet(rr, testbasepath, actionName)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify delete API name " in {
    val testName = "CLI_APIGWTEST4"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiDelete(basepathOrApiName = testapiname)
      verifyApiDeleted(rr)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify delete API basepath " in {
    val testName = "CLI_APIGWTEST5"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiDelete(basepathOrApiName = testbasepath)
      verifyApiDeleted(rr)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify adding endpoints to existing api" in {
    val testName = "CLI_APIGWTEST6"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path2"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val newEndpoint = "/newEndpoint"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(newEndpoint),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath))
      verifyApiFullListDouble(
        rr,
        clinamespace,
        actionName,
        testurlop,
        testbasepath,
        newEndpoint,
        testapiname,
        newEndpoint)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful creation with swagger doc as input" in {
    // NOTE: These values must match the swagger file contents
    val testName = "CLI_APIGWTEST7"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val swaggerPath = TestUtils.getTestApiGwFilename("testswaggerdoc1")
    try {
      var rr = apiCreate(swagger = Some(swaggerPath))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      println("list stdout: " + rr.stdout)
      println("list stderr: " + rr.stderr)
      verifyApiFullList(rr, "", actionName, testurlop, testbasepath, testrelpath, testapiname)

    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify adding endpoints to two existing apis" in {
    val testName = "CLI_APIGWTEST8"
    val testbasepath = "/" + testName + "_bp"
    val testbasepath2 = "/" + testName + "_bp2"
    val testrelpath = "/path2"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val testapiname2 = testName + " API Name 2"
    val actionName = testName + "_action"
    val newEndpoint = "/newEndpoint"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiCreate(
        basepath = Some(testbasepath2),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname2))
      verifyApiCreated(rr)

      // Update both APIs - each with a new endpoint
      rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(newEndpoint),
        operation = Some(testurlop),
        action = Some(actionName))
      verifyApiCreated(rr)
      rr = apiCreate(
        basepath = Some(testbasepath2),
        relpath = Some(newEndpoint),
        operation = Some(testurlop),
        action = Some(actionName))
      verifyApiCreated(rr)

      rr = apiList(basepathOrApiName = Some(testbasepath))
      verifyApiFullListDouble(
        rr,
        clinamespace,
        actionName,
        testurlop,
        testbasepath,
        testrelpath,
        testapiname,
        newEndpoint)

      rr = apiList(basepathOrApiName = Some(testbasepath2))
      verifyApiFullListDouble(
        rr,
        clinamespace,
        actionName,
        testurlop,
        testbasepath2,
        testrelpath,
        testapiname2,
        newEndpoint)

    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful creation of a new API using an action name using all allowed characters" in {
    // Be aware: full action name is close to being truncated by the 'list' command
    // e.g. /lime@us.ibm.com/CLI_APIGWTEST9a-c@t ion  is currently at the 40 char 'list' display max
    val testName = "CLI_APIGWTEST9"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "a-c@t ion"
    try {
      println("cli namespace: " + clinamespace)

      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
      val deleteresult = apiDelete(basepathOrApiName = testbasepath)
      verifyApiDeleted(deleteresult)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify failed creation with invalid swagger doc as input" in {
    val testName = "CLI_APIGWTEST10"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdocinvalid")
    try {
      val rr = apiCreate(swagger = Some(swaggerPath), expectedExitCode = ANY_ERROR_EXIT)
      println("api create stdout: " + rr.stdout)
      println("api create stderr: " + rr.stderr)
      verifyInvalidSwagger(rr)
    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify delete basepath/path " in {
    val testName = "CLI_APIGWTEST11"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      var rr2 = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testnewrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr2)
      rr = apiDelete(basepathOrApiName = testbasepath, relpath = Some(testrelpath))
      verifyApiDeletedRelpath(rr, testrelpath, testbasepath)
      rr2 = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testnewrelpath))
      verifyApiFullList(rr2, clinamespace, actionName, testurlop, testbasepath, testnewrelpath, testapiname)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify delete single operation from existing API basepath/path/operation(s) " in {
    val testName = "CLI_APIGWTEST12"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path2"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testurlop2 = "post"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop2),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
      verifyApiFullList(rr, clinamespace, actionName, testurlop2, testbasepath, testrelpath, testapiname)
      rr = apiDelete(basepathOrApiName = testbasepath, relpath = Some(testrelpath), operation = Some(testurlop2))
      verifyApiDeletedRelpath(rr, testrelpath, testbasepath, testurlop2)

      rr = apiList(basepathOrApiName = Some(testbasepath))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful creation with complex swagger doc as input" in {
    val testName = "CLI_APIGWTEST13"
    val testbasepath = "/test1/v1"
    val testrelpath = "/whisk_system/utils/echo"
    val testrelpath2 = "/whisk_system/utils/split"
    val testurlop = "get"
    val testurlop2 = "post"
    val testapiname = testName + " API Name"
    val actionName = "test1a"
    val swaggerPath = TestUtils.getTestApiGwFilename(s"testswaggerdoc2")
    try {
      var rr = apiCreate(swagger = Some(swaggerPath))
      println("api create stdout: " + rr.stdout)
      println("api create stderror: " + rr.stderr)
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath))
      verifyApiFullList(rr, "", actionName, testurlop, testbasepath, testrelpath, testapiname)
      verifyApiFullList(rr, "", actionName, testurlop2, testbasepath, testrelpath2, testapiname)
    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify successful creation and deletion with multiple base paths" in {
    val testName = "CLI_APIGWTEST14"
    val testbasepath = "/" + testName + "_bp"
    val testbasepath2 = "/" + testName + "_bp2"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val testapiname2 = testName + " API Name 2"
    val actionName = testName + "_action"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
      rr = apiCreate(
        basepath = Some(testbasepath2),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname2))
      verifyApiCreated(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath2), relpath = Some(testrelpath), operation = Some(testurlop))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath2, testrelpath, testapiname2)
      rr = apiDelete(basepathOrApiName = testbasepath2)
      verifyApiDeleted(rr)
      rr = apiList(basepathOrApiName = Some(testbasepath), relpath = Some(testrelpath), operation = Some(testurlop))
      verifyApiFullList(rr, clinamespace, actionName, testurlop, testbasepath, testrelpath, testapiname)
      rr = apiDelete(basepathOrApiName = testbasepath)
      verifyApiDeleted(rr)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath2, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "verify API with http response type " in {
    val testName = "CLI_APIGWTEST17"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testnewrelpath = "/path_new"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"
    val responseType = "http"
    try {
      // Create the action for the API.  It must be a "web-action" action.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      var rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        responsetype = Some(responseType))
      verifyApiCreated(rr)

      rr = apiGet(basepathOrApiName = Some(testapiname))
      verifyApiNameGet(rr, testbasepath, actionName, responseType)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "reject deletion of a non-existent api" in {
    val nonexistentApi = "/not-there"

    val rr = apiDelete(basepathOrApiName = nonexistentApi, expectedExitCode = ANY_ERROR_EXIT)
    rr.stderr should include(s"API '${nonexistentApi}' does not exist")
  }

  it should "successfully list an API whose endpoints are not mapped to actions" in {
    val testName = "CLI_APIGWTEST23"
    val testapiname = "A descriptive name"
    val testbasepath = "/NoActions"
    val testrelpath = "/"
    val testops: Seq[String] = Seq("put", "delete", "get", "head", "options", "patch", "post")
    val swaggerPath = TestUtils.getTestApiGwFilename(s"endpoints.without.action.swagger.json")

    try {
      var rr = apiCreate(swagger = Some(swaggerPath))
      println("api create stdout: " + rr.stdout)
      println("api create stderror: " + rr.stderr)
      this.verifyApiCreated(rr)

      rr = apiList(basepathOrApiName = Some(testbasepath))
      println("api list:\n" + rr.stdout)
      testops foreach { testurlop =>
        verifyApiOp(rr, testurlop, testapiname)
      }
      verifyApiBaseRelPath(rr, testbasepath, testrelpath)

      rr = apiList(basepathOrApiName = Some(testbasepath), full = Some(true))
      println("api full list:\n" + rr.stdout)
      testops foreach { testurlop =>
        verifyApiOpVerb(rr, testurlop)
      }
      verifyApiBaseRelPath(rr, testbasepath, testrelpath)

    } finally {
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }

  it should "reject creation of an API with invalid auth key" in {
    val testName = "CLI_APIGWTEST24"
    val testbasepath = "/" + testName + "_bp"
    val testrelpath = "/path"
    val testurlop = "get"
    val testapiname = testName + " API Name"
    val actionName = testName + "_action"

    try {
      // Create the action for the API.
      val file = TestUtils.getTestActionFilename(s"echo.js")
      wsk.action.create(name = actionName, artifact = Some(file), expectedExitCode = createCode, web = Some("true"))

      // Set an invalid auth key
      val badWskProps = WskProps(authKey = "bad-auth-key")

      val rr = apiCreate(
        basepath = Some(testbasepath),
        relpath = Some(testrelpath),
        operation = Some(testurlop),
        action = Some(actionName),
        apiname = Some(testapiname),
        expectedExitCode = ANY_ERROR_EXIT)(badWskProps)
      verifyInvalidKey(rr)
    } finally {
      wsk.action.delete(name = actionName, expectedExitCode = DONTCARE_EXIT)
      apiDelete(basepathOrApiName = testbasepath, expectedExitCode = DONTCARE_EXIT)
    }
  }
}
