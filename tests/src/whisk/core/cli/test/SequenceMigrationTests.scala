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
package whisk.core.cli.test

import java.util.Date
import scala.concurrent.duration.DurationInt
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import common.JsHelpers
import common.TestHelpers
import common.TestUtils
import common.Wsk
import common.WskAdmin
import common.WskProps
import common.WskTestHelpers
import spray.json._
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.testkit.ScalatestRouteTest
import whisk.core.WhiskConfig
import whisk.core.entity._
import whisk.core.database.test.DbUtils
import org.scalatest.BeforeAndAfter

import scala.language.postfixOps

/**
 * Tests that "old-style" sequences can be invoked
 */

@RunWith(classOf[JUnitRunner])
class SequenceMigrationTests
    extends TestHelpers
    with BeforeAndAfter
    with DbUtils
    with JsHelpers
    with ScalatestRouteTest
    with WskTestHelpers {

    implicit val actorSystem = system

    implicit val wskprops = WskProps()
    val wsk = new Wsk
    val whiskConfig = new WhiskConfig(WhiskEntityStore.requiredProperties)
    // handle on the entity datastore
    val entityStore = WhiskEntityStore.datastore(whiskConfig)
    val (user, namespace) = WskAdmin.getUser(wskprops.authKey)
    val allowedActionDuration = 120 seconds

    behavior of "Sequence Migration"

    it should "invoke an old-style sequence and get the result" in withAssetCleaner(wskprops) {
        (wp, assetHelper) =>
            // create entities to insert in the entity store
            val echo = "echo.json"
            val wc = "word_count.json"
            val seq_echo_wc = "seq_echo_word_count.json" // old-style sequence
            val entities = Seq(echo, wc, seq_echo_wc)
            implicit val tid = transid() // needed for put db below
            for (entity <- entities) {
                // read json file and add the appropriate namespace
                val jsonFile = TestUtils.getTestActionFilename(s"$entity")
                val source = scala.io.Source.fromFile(jsonFile)
                val jsonString = try source.mkString finally source.close()
                val entityJson = jsonString.parseJson.asJsObject
                // add default namespace (i.e., user) to the json object
                val entityJsonWithNamespace = JsObject(entityJson.fields + ("namespace" -> JsString(namespace)))
                val wskEntity = entityJsonWithNamespace.convertTo[WhiskAction]
                put(entityStore, wskEntity)
            }
            // invoke sequence
            val seqName = "seq_echo_word_count"
            val now = "it is now " + new Date()
            val run = wsk.action.invoke(seqName, Map("payload" -> now.mkString("\n").toJson))
            withActivation(wsk.activation, run, totalWait = allowedActionDuration) {
                activation =>
                    val result = activation.response.result.get
                    result.fields.get("count") shouldBe Some(JsNumber(now.split(" ").size))
            }
    }

    after {
        cleanup() // cleanup entities from db
    }
}
