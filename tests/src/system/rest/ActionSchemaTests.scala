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
import com.jayway.restassured.config.RestAssuredConfig
import com.jayway.restassured.config.SSLConfig
import common.WhiskProperties
import spray.json.pimpAny
import spray.json.pimpString
import spray.json.DefaultJsonProtocol._
import spray.json.JsNull
import scala.util.{ Try, Success, Failure }
import spray.json.JsArray
import spray.json.JsObject

/**
 * Basic tests of API calls for actions
 */
@RunWith(classOf[JUnitRunner])
class ActionSchemaTests extends FlatSpec with Matchers with RestUtil with JsonSchema {

    it should "respond to GET /actions as documented in swagger" in {

        val auth = WhiskProperties.getBasicAuth;
        val response = RestAssured.
            given().
            auth().basic(auth.fst, auth.snd).
            get(getBaseURL() + "/namespaces/whisk.system/actions/samples/");
        assert(response.statusCode() == 200);

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

    it should "respond to GET /actions/samples/wordCount as documented in swagger" in {

        val auth = WhiskProperties.getBasicAuth;
        val response = RestAssured.
            given().
            auth().basic(auth.fst, auth.snd).
            get(getBaseURL() + "/namespaces/whisk.system/actions/samples/wordCount");
        assert(response.statusCode() == 200);

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
