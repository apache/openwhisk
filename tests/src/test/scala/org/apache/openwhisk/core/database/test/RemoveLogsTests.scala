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
import java.time.temporal.ChronoUnit

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.TestUtils
import common.WhiskProperties
import common.WskActorSystem
import org.apache.openwhisk.core.entity.ActivationId
import org.apache.openwhisk.core.entity.EntityName
import org.apache.openwhisk.core.entity.EntityPath
import org.apache.openwhisk.core.entity.Subject
import org.apache.openwhisk.core.entity.WhiskActivation
import org.apache.openwhisk.core.entity.ActivationLogs

@RunWith(classOf[JUnitRunner])
class RemoveLogsTests extends FlatSpec with DatabaseScriptTestUtils with StreamLogging with WskActorSystem {

  val designDocPath = WhiskProperties
    .getFileRelativeToWhiskHome("ansible/files/logCleanup_design_document_for_activations_db.json")
    .getAbsolutePath
  val removeLogsToolPath =
    WhiskProperties.getFileRelativeToWhiskHome("tools/db/deleteLogsFromActivations.py").getAbsolutePath

  /** Runs the clean up script to delete old activations */
  def removeLogsTool(dbUrl: DatabaseUrl, dbName: String, days: Int, docsPerRequest: Int = 20) = {
    println(s"Running removeLogs tool: ${dbUrl.safeUrl}, $dbName, $days, $docsPerRequest")

    val cmd = Seq(
      python,
      removeLogsToolPath,
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

  behavior of "Activation Log Cleanup Script"

  it should "delete logs in old activation and keep log in new activation" in {
    val dbName = dbPrefix + "test_log_cleanup_1"
    val db = createDatabase(dbName, Some(designDocPath))

    try {
      // Create an old and a new activation
      val oldActivation = WhiskActivation(
        namespace = EntityPath("testns1"),
        name = EntityName("testname1"),
        subject = Subject("test-sub1"),
        activationId = ActivationId.generate(),
        start = Instant.now.minus(2, ChronoUnit.DAYS),
        end = Instant.now,
        logs = ActivationLogs(Vector("first line1", "second line1")))

      val newActivation = WhiskActivation(
        namespace = EntityPath("testns2"),
        name = EntityName("testname2"),
        subject = Subject("test-sub2"),
        activationId = ActivationId.generate(),
        start = Instant.now,
        end = Instant.now,
        logs = ActivationLogs(Vector("first line2", "second line2")))

      db.putDoc(oldActivation.docid.asString, oldActivation.toJson).futureValue shouldBe 'right
      db.putDoc(newActivation.docid.asString, newActivation.toJson).futureValue shouldBe 'right

      // Run the tool to delete logs from activations that are older than 1 day
      removeLogsTool(dbUrl, dbName, 1)

      // Check, that the new activation is untouched and the old activation is without logs now
      val newActivationRequest = db.getDoc(newActivation.docid.asString).futureValue
      newActivationRequest shouldBe 'right
      val newActivationFromDb = newActivationRequest.right.get.convertTo[WhiskActivation]
      // Compare the Json of the Whiskactivations in case the test fails -> better log output
      newActivationFromDb.toJson shouldBe newActivation.toJson

      val oldActivationRequest = db.getDoc(oldActivation.docid.asString).futureValue
      oldActivationRequest shouldBe 'right
      val oldActivationFromDb = oldActivationRequest.right.get.convertTo[WhiskActivation]
      // Compare the Json of the Whiskactivations in case the test fails -> better log output
      oldActivationFromDb.toJson shouldBe oldActivation.withoutLogs.toJson
    } finally {
      removeDatabase(dbName)
    }
  }
}
