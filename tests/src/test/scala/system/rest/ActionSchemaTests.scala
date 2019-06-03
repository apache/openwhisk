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

import scala.util.Success
import scala.util.Try
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import io.restassured.RestAssured
import common._
import common.rest.WskRestOperations
import spray.json._

/**
 * Basic tests of API calls for actions
 */
@RunWith(classOf[JUnitRunner])
class ActionSchemaTests
    extends FlatSpec
    with Matchers
    with RestUtil
    with JsonSchema
    with WskTestHelpers
    with WskActorSystem {

  implicit val wskprops = WskProps()
  val wsk = new WskRestOperations
  val guestNamespace = wskprops.namespace

  it should "respond to GET /actions as documented in swagger" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
    val packageName = "samples"
    assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
      pkg.create(packageName, shared = Some(true))
    }

    val auth = WhiskProperties.getBasicAuth
    val response = RestAssured
      .given()
      .config(sslconfig)
      .auth()
      .basic(auth.fst, auth.snd)
      .get(getBaseURL() + s"/namespaces/$guestNamespace/actions/$packageName/")
    assert(response.statusCode() == 200)

    val body = Try { response.body().asString().parseJson }
    val schema = getJsonSchema("EntityBrief").compactPrint

    body match {
      case Success(JsArray(actions)) =>
        // check that each collection result obeys the schema
        actions.foreach { a =>
          val aString = a.compactPrint
          assert(check(aString, schema))
        }

      case Success(_) =>
        assert(false, "response is not an array of actions")

      case _ =>
        assert(false, "response failed to parse: " + body)
    }
  }

  it should "respond to GET /actions/samples/wordCount as documented in swagger" in withAssetCleaner(wskprops) {
    (wp, assetHelper) =>
      val packageName = "samples"
      val actionName = "wordCount"
      val fullActionName = s"/$guestNamespace/$packageName/$actionName"
      assetHelper.withCleaner(wsk.pkg, packageName) { (pkg, _) =>
        pkg.create(packageName, shared = Some(true))
      }

      assetHelper.withCleaner(wsk.action, fullActionName) { (action, _) =>
        action.create(fullActionName, Some(TestUtils.getTestActionFilename("wc.js")))
      }
      val auth = WhiskProperties.getBasicAuth
      val response = RestAssured
        .given()
        .config(sslconfig)
        .auth()
        .basic(auth.fst, auth.snd)
        .get(getBaseURL() + s"/namespaces/$guestNamespace/actions/$packageName/$actionName")
      assert(response.statusCode() == 200)

      val body = Try { response.body().asString().parseJson }
      val schema = getJsonSchema("Action").compactPrint

      body match {
        case Success(action: JsObject) =>
          // check that the action obeys the Action model schema
          val aString = action.compactPrint
          assert(check(aString, schema))

        case Success(_) =>
          assert(false, "response is not a json object")

        case _ =>
          assert(false, "response failed to parse as JSON: " + body)
      }
  }
}
