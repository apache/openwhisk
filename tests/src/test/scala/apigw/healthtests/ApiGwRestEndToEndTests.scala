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

import akka.http.scaladsl.model.StatusCodes.OK

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import common.TestUtils._
import common.rest.WskRestOperations
import common.rest.RestResult
import common.WskActorSystem

@RunWith(classOf[JUnitRunner])
class ApiGwRestEndToEndTests extends ApiGwEndToEndTests with WskActorSystem {

  override lazy val wsk = new WskRestOperations
  override val createCode = OK.intValue

  override def verifyAPICreated(rr: RunResult): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    apiResultRest.statusCode shouldBe OK
    val apiurl = apiResultRest.getField("gwApiUrl") + "/path"
    println(s"apiurl: '$apiurl'")
  }

  override def verifyAPIList(rr: RunResult,
                             actionName: String,
                             testurlop: String,
                             testapiname: String,
                             testbasepath: String,
                             testrelpath: String): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    val basepath = RestResult.getField(apidoc, "basePath")
    basepath shouldBe testbasepath

    val paths = RestResult.getFieldJsObject(apidoc, "paths")
    paths.fields.contains(testrelpath) shouldBe true

    val info = RestResult.getFieldJsObject(apidoc, "info")
    val title = RestResult.getField(info, "title")
    title shouldBe testapiname

    val relpath = RestResult.getFieldJsObject(paths, testrelpath)
    val urlop = RestResult.getFieldJsObject(relpath, testurlop)
    val openwhisk = RestResult.getFieldJsObject(urlop, "x-openwhisk")
    val actionN = RestResult.getField(openwhisk, "action")
    actionN shouldBe actionName
  }

  override def verifyAPISwaggerCreated(rr: RunResult): Unit = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    apiResultRest.statusCode shouldBe OK
  }

  override def writeSwaggerFile(rr: RunResult): File = {
    val swaggerfile = File.createTempFile("api", ".json")
    swaggerfile.deleteOnExit()
    val bw = new BufferedWriter(new FileWriter(swaggerfile))
    val apiResultRest = rr.asInstanceOf[RestResult]
    val apiValue = RestResult.getFieldJsObject(apiResultRest.getFieldListJsObject("apis")(0), "value")
    val apidoc = RestResult.getFieldJsObject(apiValue, "apidoc")
    bw.write(apidoc.toString())
    bw.close()
    swaggerfile
  }

  override def getSwaggerApiUrl(rr: RunResult): String = {
    val apiResultRest = rr.asInstanceOf[RestResult]
    apiResultRest.getField("gwApiUrl") + "/path"
  }
}
