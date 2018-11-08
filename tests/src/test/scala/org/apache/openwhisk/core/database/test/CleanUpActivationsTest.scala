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

package org.apache.openwhisk.core.database.test

import java.io.File
import java.time.Instant

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.StatusCodes
import common.StreamLogging
import common.TestUtils
import common.WaitFor
import common.WhiskProperties
import common.WskActorSystem
import spray.json.DefaultJsonProtocol._
import spray.json._

@RunWith(classOf[JUnitRunner])
class CleanUpActivationsTest
    extends FlatSpec
    with Matchers
    with ScalaFutures
    with WskActorSystem
    with WaitFor
    with StreamLogging
    with DatabaseScriptTestUtils {

  val testDbPrefix = s"cleanuptest_$dbPrefix"
  val cleanUpTool = WhiskProperties.getFileRelativeToWhiskHome("tools/db/cleanUpActivations.py").getAbsolutePath
  val designDocPath = WhiskProperties
    .getFileRelativeToWhiskHome("ansible/files/activations_design_document_for_activations_db.json")
    .getAbsolutePath

  implicit def toDuration(dur: FiniteDuration) = java.time.Duration.ofMillis(dur.toMillis)

  /** Runs the clean up script to delete old activations */
  def runCleanUpTool(dbUrl: DatabaseUrl, dbName: String, days: Int, docsPerRequest: Int = 200) = {
    println(s"Running clean up tool: ${dbUrl.safeUrl}, $dbName, $days, $docsPerRequest")

    val cmd = Seq(
      python,
      cleanUpTool,
      "--dbUrl",
      dbUrl.url,
      "--dbName",
      dbName,
      "--days",
      days.toString,
      "--docsPerRequest",
      docsPerRequest.toString)
    val rr = TestUtils.runCmd(0, new File("."), cmd: _*)
  }

  behavior of "Database clean up script"

  it should "delete old activations and keep new ones" in {
    // Create a database
    val dbName = testDbPrefix + "database_to_clean_old_and_keep_new"
    val client = createDatabase(dbName, Some(designDocPath))

    println(s"Creating testdocuments")
    val oldDocument =
      JsObject("start" -> Instant.now.minus(6.days).toEpochMilli.toJson, "activationId" -> "abcde0123".toJson)
    val newDocument = JsObject("start" -> Instant.now.toEpochMilli.toJson, "activationId" -> "0123abcde".toJson)
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
    resultNew.right.get.fields
      .filterNot(current => current._1 == "_id" || current._1 == "_rev")
      .toJson shouldBe newDocument

    // Remove created database
    removeDatabase(dbName)
  }

  it should "delete old activations in several iterations" in {
    // Create a database
    val dbName = testDbPrefix + "database_to_clean_in_iterations"
    val client = createDatabase(dbName, Some(designDocPath))
    println(s"Creating testdocuments")
    val ids = (1 to 5).map { current =>
      client
        .putDoc(
          s"testId_$current",
          JsObject(
            "start" -> Instant.now.minus(2.days).toEpochMilli.toJson,
            "activationId" -> s"abcde0123$current".toJson))
        .futureValue
      s"testId_$current"
    }

    val newDocument = JsObject("start" -> Instant.now.toEpochMilli.toJson, "activationId" -> "0123abcde".toJson)
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
    resultNew.right.get.fields
      .filterNot(current => current._1 == "_id" || current._1 == "_rev")
      .toJson shouldBe newDocument

    // Remove created database
    removeDatabase(dbName)
  }
}
