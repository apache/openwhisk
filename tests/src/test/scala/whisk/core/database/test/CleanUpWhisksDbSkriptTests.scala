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

  def runScript(dbUrl: String, whisksDbName: String, subjectsDbName: String, whiskDbType: String) = {
    println(s"Running script: $dbUrl, $whisksDbName, $subjectsDbName, $whiskDbType")

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
        "--whiskDBType",
        whiskDbType,
        "--days",
        "7",
        "--docsPerRequest",
        "1")

    val rr = TestUtils.runCmd(0, new File("."), cmd: _*)

    rr.stdout.lines.foreach {
      case (line) =>
        println(s"line: $line")
    }

    val Seq(marking, marked, deleting, skipping, keeping, statistic) =
      Seq("marking: ", "marked: ", "deleting: ", "skipping: ", "keeping: ", "statistic: ").map { linePrefix =>
        rr.stdout.lines.collect {
          case line if line.startsWith(linePrefix) => line.replace(linePrefix, "")
        }.toList
      }

    println(s"marked for deletion: $marking")
    println(s"already marked:      $marked")
    println(s"deleted:             $deleting")
    println(s"skipped:             $skipping")
    println(s"kept:                $keeping")
    println(s"statistic:           $statistic")

    (marking, deleting, skipping, keeping)
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
    val (marked, _, _, _) = runScript(dbUrl, dbName, authDBName, "whisks")
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
    val (marked, deleted, _, _) = runScript(dbUrl, dbName, authDBName, "whisks")
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
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName, "whisks")
    println(s"kept: $kept")

    // Check, that script did not mark documents for deletion
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain("3 doc(s) for namespace whisk.system")

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
    val (_, _, skipped, _) = runScript(dbUrl, dbName, authDBName, "whisks")
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
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName, "whisks")
    println(s"kept: $kept")

    // Check, that script kept documents in DB
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain("3 doc(s) for namespace whisk.system")

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]

    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }

  it should "mark documents for deletion in cloudanttrigger if namespace does not exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_cloudanttrigger_test_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with random namespace
    val documents = Map(
      ":AHA04676_dev:vision-cloudant-trigger" -> JsObject("dbname" -> JsString("openwhisk-darkvision-dev")),
      ":AHA04676_stage:vision-cloudant-trigger" -> JsObject("dbname" -> JsString("openwhisk-darkvision-stage")),
      ":AHA04676_prod:vision-cloudant-trigger" -> JsObject("dbname" -> JsString("openwhisk-darkvision-prod")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (marked, _, _, _) = runScript(dbUrl, dbName, authDBName, "cloudanttrigger")
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

  it should "not mark documents for deletion in cloudanttrigger if namespace does exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_cloudanttrigger_test_not_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with whisk-system namespace
    val documents = Map(
      ":whisk.system:vision-cloudant-trigger" -> JsObject("dbname" -> JsString("openwhisk-darkvision-dev")),
      ":whisk.system:vision-cloudant-trigger2" -> JsObject("dbname" -> JsString("openwhisk-darkvision-dev")),
      ":whisk.system:vision-cloudant-trigger3" -> JsObject("dbname" -> JsString("openwhisk-darkvision-dev")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName, "cloudanttrigger")
    println(s"kept: $kept")

    // Check, that script did not mark documents for deletion
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain("3 doc(s) for namespace whisk.system")

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]
    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }

  it should "mark documents for deletion in kafkatrigger if namespace does not exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_kafkatrigger_test_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with random namespace
    val documents = Map(
      "/AJackson@uk.ibm.com_dev/badgers" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "/AJackson@uk.ibm.com_dev/badgers2" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers2")),
      "/AJackson@uk.ibm.com_dev/badgers3" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers3")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (marked, _, _, _) = runScript(dbUrl, dbName, authDBName, "kafkatrigger")
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

  it should "not mark documents for deletion in kafkatrigger if namespace does exist" in {
    // Create whisks db
    val dbName = dbPrefix + "cleanup_kafkatrigger_test_not_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with whisk-system namespace
    val documents = Map(
      "/whisk.system/badgers" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "/whisk.system/badgers2" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "/whisk.system/badgers3" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName, "kafkatrigger")
    println(s"kept: $kept")

    // Check, that script did not mark documents for deletion
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain("3 doc(s) for namespace whisk.system")

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]
    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }

  it should "mark documents for deletion in alarmservice if namespace does not exist" in {
    // Create alarmservice db
    val dbName = dbPrefix + "cleanup_alarmservice_test_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with random namespace
    val documents = Map(
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/swapna_gen4_org_dev/trigger_2bd1d70424a7b627ef18827fac212dae" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/swapna_gen4_org_stage/trigger_2bd1d70424a7b627ef18827fac212dae" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers2")),
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/swapna_gen4_org_prod/trigger_2bd1d70424a7b627ef18827fac212dae" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers3")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (marked, _, _, _) = runScript(dbUrl, dbName, authDBName, "alarmservice")
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

  it should "not mark documents for deletion in alarmservice if namespace does exist" in {
    // Create alarmservice db
    val dbName = dbPrefix + "cleanup_alarmservice_test_not_mark_for_deletion"
    val client = createDatabase(dbName, None)

    // Create document/action with whisk-system namespace
    val documents = Map(
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/whisk.system/trigger" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/whisk.system/trigger2" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")),
      "02e116e9-b66f-4ed3-8159-a40048ae829b:XXX/whisk.system/trigger3" -> JsObject(
        "triggerURL" -> JsString(
          "https://xxx:xxx@openwhisk.ng.bluemix.net/api/v1/namespaces/AJackson%40uk.ibm.com_dev/triggers/badgers")))

    documents.foreach {
      case (id, document) =>
        client.putDoc(id, document).futureValue
    }

    // execute script
    val (_, _, _, kept) = runScript(dbUrl, dbName, authDBName, "alarmservice")
    println(s"kept: $kept")

    // Check, that script did not mark documents for deletion
    val ids = documents.keys
    println(s"ids: $ids")
    kept should contain("3 doc(s) for namespace whisk.system")

    val databaseResponse = client.getAllDocs(includeDocs = Some(true)).futureValue
    databaseResponse should be('right)

    val databaseDocuments = databaseResponse.right.get.fields("rows").convertTo[List[JsObject]]
    val databaseDocumentIDs = databaseDocuments.map(_.fields("id").convertTo[String])
    databaseDocumentIDs should contain allElementsOf ids

    // Delete database
    client.deleteDb().futureValue
  }
}
