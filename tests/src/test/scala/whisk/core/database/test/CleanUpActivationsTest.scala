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

package whisk.core.database.test

import java.io.File
import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.language.implicitConversions

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes
import common.StreamLogging
import common.TestUtils
import common.WaitFor
import common.WhiskProperties
import common.WskActorSystem
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json.pimpAny
import spray.json.pimpString
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dbHost
import whisk.core.WhiskConfig.dbPassword
import whisk.core.WhiskConfig.dbPort
import whisk.core.WhiskConfig.dbPrefix
import whisk.core.WhiskConfig.dbProtocol
import whisk.core.WhiskConfig.dbProvider
import whisk.core.WhiskConfig.dbUsername
import whisk.core.database.CouchDbRestClient
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class CleanUpActivationsTest extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskActorSystem
    with IntegrationPatience
    with WaitFor
    with StreamLogging {

    val config = new WhiskConfig(Map(
        dbProvider -> null,
        dbProtocol -> null,
        dbUsername -> null,
        dbPassword -> null,
        dbHost -> null,
        dbPort -> null,
        dbPrefix -> null))
    val testDbPrefix = s"cleanuptest_${config.dbPrefix}"
    val dbUrl = s"${config.dbProtocol}://${config.dbUsername}:${config.dbPassword}@${config.dbHost}:${config.dbPort}"
    val python = WhiskProperties.python
    val cleanUpTool = WhiskProperties.getFileRelativeToWhiskHome("tools/db/cleanUpActivations.py").getAbsolutePath
    val designDocPath = WhiskProperties.getFileRelativeToWhiskHome("ansible/files/activations_design_document_for_activations_db.json").getAbsolutePath

    implicit def toDuration(dur: FiniteDuration) = java.time.Duration.ofMillis(dur.toMillis)
    def toEpochSeconds(i: Instant) = i.toEpochMilli / 1000

    /** Creates a new database with the given name */
    def createDatabase(name: String) = {
        // Implicitly remove database for sanitization purposes
        removeDatabase(name, true)

        println(s"Creating database: $name")
        val db = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, name)
        retry({ db.createDb().futureValue shouldBe 'right }, N = 10, waitBeforeRetry = Some(500.milliseconds))

        retry({
            val list = db.dbs().futureValue.right.get
            list should contain(name)
        }, N = 10, waitBeforeRetry = Some(500.milliseconds))

        val designDoc = Source.fromFile(designDocPath).mkString.parseJson.asJsObject
        db.putDoc(designDoc.fields("_id").convertTo[String], designDoc)

        db
    }

    /** Removes the database with the given name */
    def removeDatabase(name: String, ignoreFailure: Boolean = false) = {
        println(s"Removing database: $name")
        val db = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, name)
        retry({
            val delete = db.deleteDb().futureValue
            if (!ignoreFailure) delete shouldBe 'right
        }, N = 10, waitBeforeRetry = Some(500.milliseconds))
        db
    }

    /** Runs the clean up script to delete old activations */
    def runCleanUpTool(dbUrl: String, dbName: String, days: Int, docsPerRequest: Int = 200) = {
        println(s"Running clean up tool: $dbUrl, $dbName, $days, $docsPerRequest")

        val cmd = Seq(python, cleanUpTool, "--dbUrl", dbUrl, "--dbName", dbName, "--days", days.toString, "--docsPerRequest", docsPerRequest.toString)
        val rr = TestUtils.runCmd(0, new File("."), cmd: _*)
    }

    /** wait until all documents are processed by the view */
    def waitForView(db: CouchDbRestClient, designDoc: String, viewName: String, numDocuments: Int) = {
        waitfor(() => {
            val view = db.executeView(designDoc, viewName)().futureValue
            view shouldBe 'right
            view.right.get.fields("rows").convertTo[List[JsObject]].length == numDocuments
        }, totalWait = 2.minutes)
    }

    /** Wait for a document to appear */
    def waitForDocument(client: ExtendedCouchDbRestClient, id: String) = waitfor(() => client.getDoc(id).futureValue.isRight)

    behavior of "Database clean up script"

    it should "delete old activations and keep new ones" in {
        // Create a database
        val dbName = testDbPrefix + "database_to_clean_old_and_keep_new"
        val client = createDatabase(dbName)

        println(s"Creating testdocuments")
        val oldDocument = JsObject("start" -> Instant.now.minus(6.days).toEpochMilli.toJson,
            "activationId" -> "abcde0123".toJson)
        val newDocument = JsObject("start" -> Instant.now.toEpochMilli.toJson,
            "activationId" -> "0123abcde".toJson)
        client.putDoc("testId1", oldDocument).futureValue
        client.putDoc("testId2", newDocument).futureValue
        waitForView(client, "activations", "byDate", 2)

        // Trigger clean up script and verify that old document is deleted and the new one not
        runCleanUpTool(dbUrl, dbName, 5)
        val resultOld = client.getDoc("testId1").futureValue
        resultOld shouldBe 'left
        resultOld.left.get shouldBe StatusCodes.NotFound

        val resultNew = client.getDoc("testId2").futureValue
        resultNew shouldBe 'right
        resultNew.right.get.fields.filterNot(current => current._1 == "_id" || current._1 == "_rev").toJson shouldBe newDocument

        // Remove created database
        removeDatabase(dbName)
    }

    it should "delete old activations in several iterations" in {
        // Create a database
        val dbName = testDbPrefix + "database_to_clean_in_iterations"
        val client = createDatabase(dbName)
        println(s"Creating testdocuments")
        val ids = (1 to 5).map { current =>
            client.putDoc(s"testId_$current", JsObject("start" -> Instant.now.minus(2.days).toEpochMilli.toJson,
                "activationId" -> s"abcde0123$current".toJson)).futureValue
            s"testId_$current"
        }

        val newDocument = JsObject("start" -> Instant.now.toEpochMilli.toJson,
            "activationId" -> "0123abcde".toJson)
        client.putDoc("testIdNew", newDocument).futureValue
        waitForView(client, "activations", "byDate", 6)

        // Trigger clean up script and verify that old document is deleted and the new one not
        runCleanUpTool(dbUrl, dbName, 1, 2)
        ids.foreach { id =>
            val resultOld = client.getDoc(id).futureValue
            resultOld shouldBe 'left
            resultOld.left.get shouldBe StatusCodes.NotFound
        }

        val resultNew = client.getDoc("testIdNew").futureValue
        resultNew shouldBe 'right
        resultNew.right.get.fields.filterNot(current => current._1 == "_id" || current._1 == "_rev").toJson shouldBe newDocument

        // Remove created database
        removeDatabase(dbName)
    }
}
