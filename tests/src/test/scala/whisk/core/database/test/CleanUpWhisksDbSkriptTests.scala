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

package whisk.core.database.test

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

import common.{StreamLogging, TestUtils, WhiskProperties, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.database.CouchDbConfig
//import whisk.core.{ConfigKeys, WhiskConfig}
import whisk.core.ConfigKeys
import whisk.core.entity._

@RunWith(classOf[JUnitRunner])
class CleanUpWhisksDbSkriptTests
    extends FlatSpec
    with Matchers
    with DatabaseScriptTestUtils
    with WskActorSystem
    with StreamLogging {

  val cleanupScript = WhiskProperties.getFileRelativeToWhiskHome("tools/db/cleanUpWhisks.py").getAbsolutePath
  val dbConfig = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
  val authDBName = dbConfig.databaseFor[WhiskAuth]

  def runScript(dbUrl: String, whisksDbName: String, subjectsDbName: String) = {
    println(s"Running script: $dbUrl, $whisksDbName, $subjectsDbName")

    val cmd =
      Seq(
        python,
        cleanupScript,
        "--dbUrl",
        dbUrl,
        "--dbNameWhisks",
        whisksDbName,
        "--dbNameSubjects",
        subjectsDbName,
        "--days",
        "1",
        "--docsPerRequest",
        "1")

    val rr = TestUtils.runCmd(0, new File("."), cmd: _*)

    val Seq(marked, deleted, skipped, kept) =
      Seq("marking: ", "deleting: ", "skipping: ", "keeping: ").map { linePrefix =>
        rr.stdout.lines.collect {
          case line if line.startsWith(linePrefix) => line.replace(linePrefix, "")
        }.toList
      }

    println(s"marked:  $marked")
    println(s"deleted: $deleted")
    println(s"skipped: $skipped")
    println(s"kept:    $kept")

    (marked, deleted, skipped, kept)
  }

  behavior of "Cleanup whisksDb script"

  it should "mark documents for deletion if namespace does not exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_whisks_test_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with random namespace
    val documents = Map(
      "whisksCleanTests/utils/actionName1" -> JsObject("namespace" -> JsString("whisksCleanTests/utils")),
      "whisksCleanTests/utils/actionName2" -> JsObject("namespace" -> JsString("whisksCleanTests")),
      "whisksCleanTests/actionName3" -> JsObject("namespace" -> JsString("whisksCleanTests")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (marked, _, _, _) = runScript(dbUrl, dbName, authDBName)
    println(s"marked: $marked")

    // Check, that script marked document to be deleted: output + document from DB
    val ids = documents.keys
    println(s"ids: $ids")
    marked should contain allElementsOf ids

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)
    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]

    databaseDocuments.foreach { doc =>
      doc.fields("doc").asJsObject.fields.keys should contain("markedForDeletion")
    }

    // Delete database
    client.deleteDb().futureValue
  }

  it should "delete marked for deletion documents if namespace does not exists" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_whisks_test_delete_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with random namespace and markedForDeletion field
    val documents = Map(
      "whisksCleanTests/utils/actionName1" -> JsObject(
        "namespace" -> JsString("whisksCleanTests/utils"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)),
      "whisksCleanTests/utils/actionName2" -> JsObject(
        "namespace" -> JsString("whisksCleanTests"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)),
      "whisksCleanTests/actionName3" -> JsObject(
        "namespace" -> JsString("whisksCleanTests"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (marked, deleted, _, _) = runScript(dbUrl, dbName, authDBName)
    println(s"marked: $marked")
    println(s"deleted: $deleted")

    // Check, that script deleted already marked documents from DB
    val ids = documents.keys
    println(s"ids: $ids")
    marked shouldBe empty

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]
    databaseDocuments shouldBe empty

    // Delete database
    client.deleteDb().futureValue
  }

  it should "not mark documents for deletion if namespace does exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_whisks_test_not_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with whisk-system namespace
    val documents = Map(
      "whisk.system/utils" -> JsObject("namespace" -> JsString("whisk.system")),
      "whisk.system/samples/helloWorld" -> JsObject("namespace" -> JsString("whisk.system/samples")),
      "whisk.system/utils/namespace" -> JsObject("namespace" -> JsString("whisk.system/utils")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName)
    println(s"kept: $kept")

    // Check, that script did not mark documents for deletion
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain allElementsOf ids

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]
    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }

  it should "skip design documents" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_whisks_test_skip_design_documents"
    val client = createDatabase(dbName, None)

    // Create design documents
    val documents = Map(
      "_design/all-whisks.v2.1.0" -> JsObject("language" -> JsString("javascript")),
      "_design/snapshotFilters" -> JsObject("language" -> JsString("javascript")),
      "_design/whisks.v2.1.0" -> JsObject("language" -> JsString("javascript")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, skipped, _) = runScript(dbUrl, dbName, authDBName)
    println(s"skipped: $skipped")

    // Check, that script skipped design documents
    val ids = documents.keys
    println(s"ids: $ids")
    skipped should contain allElementsOf ids

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]

    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }

  it should "not delete marked for deletion documents if namespace does exists" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_whisks_test_not_delete_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with whisk-system namespace and markedForDeletion field
    val documents = Map(
      "whisk.system/utils" -> JsObject(
        "namespace" -> JsString("whisk.system"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)),
      "whisk.system/samples/helloWorld" -> JsObject(
        "namespace" -> JsString("whisk.system/samples"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)),
      "whisk.system/utils/namespace" -> JsObject(
        "namespace" -> JsString("whisk.system/utils"),
        "markedForDeletion" -> JsNumber(Instant.now().minus(8, ChronoUnit.DAYS).toEpochMilli)))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName)
    println(s"kept: $kept")

    // Check, that script kept documents in DB
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain allElementsOf ids

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]

    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }
}
