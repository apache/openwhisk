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

package system.basic

import java.io.File
import io.restassured.RestAssured
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration.DurationInt
import common._
import common.rest.WskRestOperations
import spray.json._
import system.rest.RestUtil

@RunWith(classOf[JUnitRunner])
class WskUnicodeTests extends TestHelpers with WskTestHelpers with JsHelpers with WskActorSystem with RestUtil {

  implicit val wskprops: common.WskProps = WskProps()
  val wsk: WskOperations = new WskRestOperations

  val activationMaxDuration = 2.minutes
  val activationPollDuration = 3.minutes

  import WskUnicodeTests._

  val actionKinds: Iterable[Kind] = {
    val response = RestAssured.given.config(sslconfig).get(getServiceURL)
    response.statusCode should be(200)

    val mf = response.body.asString.parseJson.asJsObject.fields("runtimes").asJsObject
    mf.fields.values.map(_.convertTo[Vector[Kind]]).flatten.filter(!_.deprecated)
  }

  println(s"Kinds to test: ${actionKinds.map(_.kind).mkString(", ")}")

  def main(kind: String): Option[String] = {
    if (kind.startsWith("java")) {
      Some("Unicode")
    } else if (kind.contains("dotnet")) {
      Some("Apache.OpenWhisk.UnicodeTests.Dotnet::Apache.OpenWhisk.UnicodeTests.Dotnet.Unicode::Main")
    } else None
  }

  def getFileLocation(kind: String): Option[String] = {
    // the test file is either named kind.txt or kind.bin
    // one of the two must exist otherwise, fail the test.
    val prefix = "unicode.tests" + File.separator + kind.replace(":", "-")
    val txt = new File(TestUtils.getTestActionFilename(s"$prefix.txt"))
    val bin = new File(TestUtils.getTestActionFilename(s"$prefix.bin"))
    if (txt.exists) Some(txt.toString)
    else if (bin.exists) Some(bin.toString)
    else {
      println(s"WARNING: did not find text or binary action for kind $kind, skipping it")
      None
    }
  }

  // tolerate missing files rather than throw an exception
  actionKinds.map(k => (k.kind, getFileLocation(k.kind))).collect {
    case (actionKind, file @ Some(_)) =>
      s"$actionKind action" should "Ensure that UTF-8 in supported in source files, input params, logs, and output results" in withAssetCleaner(
        wskprops) { (wp, assetHelper) =>
        val name = s"unicodeGalore.${actionKind.replace(":", "")}"

        assetHelper.withCleaner(wsk.action, name) { (action, _) =>
          action
            .create(name, file, main = main(actionKind), kind = Some(actionKind), timeout = Some(activationMaxDuration))
        }

        withActivation(
          wsk.activation,
          wsk.action.invoke(name, parameters = Map("delimiter" -> JsString("❄"))),
          totalWait = activationPollDuration) { activation =>
          val response = activation.response
          response.result.get.fields.get("error") shouldBe empty
          response.result.get.fields.get("winter") should be(Some(JsString("❄ ☃ ❄")))

          activation.logs.toList.flatten.mkString(" ") should include("❄ ☃ ❄")
        }
      }
  }
}

protected[basic] object WskUnicodeTests extends DefaultJsonProtocol {
  case class Kind(kind: String, deprecated: Boolean)
  implicit val serdes: RootJsonFormat[Kind] = jsonFormat2(Kind)
}
