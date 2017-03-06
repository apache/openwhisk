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

import scala.concurrent.duration._
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
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.core.WhiskConfig._
import whisk.core.WhiskConfig
import whisk.utils.retry

@RunWith(classOf[JUnitRunner])
class ReplicatorTests extends FlatSpec
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
    val testDbPrefix = s"replicatortest_${config.dbPrefix}"
    val dbUrl = s"${config.dbProtocol}://${config.dbUsername}:${config.dbPassword}@${config.dbHost}:${config.dbPort}"

    val replicatorClient = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, "_replicator")

    val python = WhiskProperties.python
    val replicator = WhiskProperties.getFileRelativeToWhiskHome("tools/db/replicateDbs.py").getAbsolutePath

    implicit def toDuration(dur: FiniteDuration) = java.time.Duration.ofMillis(dur.toMillis)
    def toEpochSeconds(i: Instant) = i.toEpochMilli / 1000

    /** Creates a new database with the given name */
    def createDatabase(name: String) = {
        // Implicitly remove database for sanitization purposes
        removeDatabase(name, true)

        println(s"Creating database: $name")
        val db = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, name)
        db.createDb().futureValue shouldBe 'right

        retry({
            val list = db.dbs().futureValue.right.get
            list should contain(name)
        }, N = 10, waitBeforeRetry = Some(500.milliseconds))

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

    /** Runs the replicator script to replicate databases */
    def runReplicator(sourceDbUrl: String, targetDbUrl: String, dbPrefix: String, expires: FiniteDuration, continuous: Boolean = false) = {
        println(s"Running replicator: $sourceDbUrl, $targetDbUrl, $dbPrefix, $expires, $continuous")

        val continuousFlag = if (continuous) Some("--continuous") else None
        val cmd = Seq(python, replicator, "--sourceDbUrl", sourceDbUrl, "--targetDbUrl", targetDbUrl, "replicate", "--dbPrefix", dbPrefix, "--expires", expires.toSeconds.toString) ++ continuousFlag
        val rr = TestUtils.runCmd(0, new File("."), cmd: _*)

        val Seq(created, deleted) = Seq("create backup: ", "deleting backup: ").map { prefix =>
            rr.stdout.lines.collect {
                case line if line.startsWith(prefix) => line.replace(prefix, "")
            }.toList
        }

        println(s"Created: $created")
        println(s"Deleted: $deleted")

        (created, deleted)
    }

    /** Runs the replicator script to replay databases */
    def runReplay(sourceDbUrl: String, targetDbUrl: String, dbPrefix: String) = {
        println(s"Running replay: $sourceDbUrl, $targetDbUrl, $dbPrefix")
        val rr = TestUtils.runCmd(0, new File("."), WhiskProperties.python, WhiskProperties.getFileRelativeToWhiskHome("tools/db/replicateDbs.py").getAbsolutePath, "--sourceDbUrl", sourceDbUrl, "--targetDbUrl", targetDbUrl, "replay", "--dbPrefix", dbPrefix)

        val line = """([\w-]+) -> ([\w-]+) \(([\w-]+)\)""".r.unanchored
        val replays = rr.stdout.lines.collect {
            case line(backup, target, id) => (backup, target, id)
        }.toList

        println(s"Replays created: $replays")

        replays
    }

    /** Wait for a replication to finish */
    def waitForReplication(dbName: String) = {
        waitfor(() => {
            val replicatorDoc = replicatorClient.getDoc(dbName).futureValue
            replicatorDoc shouldBe 'right

            val state = replicatorDoc.right.get.fields.get("_replication_state")
            println(s"Waiting for replication, state: $state")

            state.contains("completed".toJson)
        }, totalWait = 2.minutes)
    }

    /** Wait for database to appear */
    def waitForDatabase(dbName: String) = {
        val client = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, dbName)
        waitfor(() => {
            client.getAllDocs(includeDocs = Some(true)).futureValue.isRight
        })
        client
    }

    /** Wait for a document to appear */
    def waitForDocument(client: ExtendedCouchDbRestClient, id: String) = waitfor(() => client.getDoc(id).futureValue.isRight)

    /** Compares to databases to full equality */
    def compareDatabases(dbNames: Seq[String]) = {
        val dbDocuments = dbNames.map { dbName =>
            val client = new ExtendedCouchDbRestClient(config.dbProtocol, config.dbHost, config.dbPort.toInt, config.dbUsername, config.dbPassword, dbName)
            val documents = client.getAllDocs(includeDocs = Some(true)).futureValue
            documents shouldBe 'right
            documents.right.get
        }

        all(dbDocuments.tail) shouldBe dbDocuments.head
    }

    behavior of "Database replication script"

    it should "replicate a database" in {
        // Create a database to backup
        val dbName = testDbPrefix + "database_for_single_replication"
        val client = createDatabase(dbName)

        println(s"Creating testdocument")
        val testDocument = JsObject("testKey" -> "testValue".toJson)
        client.putDoc("testId", testDocument).futureValue

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName should fullyMatch regex s"backup_\\d+_$dbName"

        // Wait for the replication to finish
        waitForReplication(backupDbName)

        // Verify the replicated database is equal to the original database
        compareDatabases(Seq(backupDbName, dbName))

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        removeDatabase(dbName)
    }

    it should "continuously update a database" in {
        // Create a database to backup
        val dbName = testDbPrefix + "database_for_continous_replication"
        val client = createDatabase(dbName)

        // Trigger replication and verify the created databases have the correct format
        val (createdBackupDbs, _) = runReplicator(dbUrl, dbUrl, testDbPrefix, 10.minutes, true)
        createdBackupDbs should have size 1
        val backupDbName = createdBackupDbs.head
        backupDbName shouldBe s"continuous_$dbName"

        // Wait for the replicated database to appear
        val backupClient = waitForDatabase(backupDbName)

        // Create a document in the old database
        println(s"Creating testdocument")
        val docId = "testId"
        val testDocument = JsObject("testKey" -> "testValue".toJson)
        client.putDoc(docId, testDocument).futureValue

        // Wait for the document to appear
        waitForDocument(backupClient, docId)

        // Verify the replicated database is equal to the original database
        compareDatabases(Seq(backupDbName, dbName))

        // Stop the replication
        val replication = replicatorClient.getDoc(backupDbName).futureValue
        replication shouldBe 'right
        val replicationDoc = replication.right.get
        replicatorClient.deleteDoc(replicationDoc.fields("_id").convertTo[String], replicationDoc.fields("_rev").convertTo[String])

        // Remove all created databases
        createdBackupDbs.foreach(removeDatabase(_))
        removeDatabase(dbName)
    }

    it should "remove outdated databases" in {
        val now = Instant.now()
        val expires = 10.minutes

        println(s"Now is: ${toEpochSeconds(now)}")

        // Create a database that is already expired
        val expired = now.minus(expires + 5.minutes)
        val expiredName = s"backup_${toEpochSeconds(expired)}_${testDbPrefix}expired_backup"
        val expiredClient = createDatabase(expiredName)

        // Create a database that is not yet expired
        val notExpired = now.plus(expires - 5.minutes)
        val notExpiredName = s"backup_${toEpochSeconds(notExpired)}_${testDbPrefix}notexpired_backup"
        val notExpiredClient = createDatabase(notExpiredName)

        // Trigger replication and verify the expired database is deleted while the unexpired one is kept
        val (createdDatabases, deletedDatabases) = runReplicator(dbUrl, dbUrl, testDbPrefix, expires)
        deletedDatabases should (contain(expiredName) and not contain (notExpiredName))

        expiredClient.getAllDocs().futureValue shouldBe Left(StatusCodes.NotFound)
        notExpiredClient.getAllDocs().futureValue shouldBe 'right

        // Cleanup backup database
        createdDatabases.foreach(removeDatabase(_))
        removeDatabase(notExpiredName)
    }

    it should "not remove outdated databases with other prefix" in {
        val now = Instant.now()
        val expires = 10.minutes

        println(s"Now is: ${toEpochSeconds(now)}")

        val expired = now.minus(expires + 5.minutes)

        // Create a database that is expired with correct prefix
        val correctPrefixName = s"backup_${toEpochSeconds(expired)}_${testDbPrefix}expired_backup_correct_prefix"
        val correctPrefixClient = createDatabase(correctPrefixName)

        // Create a database that is expired with wrong prefix
        val wrongPrefix = s"replicatortest_wrongprefix_${config.dbPrefix}"
        val wrongPrefixName = s"backup_${toEpochSeconds(expired)}_${wrongPrefix}expired_backup_wrong_prefix"
        val wrongPrefixClient = createDatabase(wrongPrefixName)

        // Trigger replication and verify the expired database with correct prefix is deleted while the db with the wrong prefix is kept
        val (createdDatabases, deletedDatabases) = runReplicator(dbUrl, dbUrl, testDbPrefix, expires)
        deletedDatabases should (contain(correctPrefixName) and not contain (wrongPrefixName))

        correctPrefixClient.getAllDocs().futureValue shouldBe Left(StatusCodes.NotFound)
        wrongPrefixClient.getAllDocs().futureValue shouldBe 'right

        // Cleanup backup database
        createdDatabases.foreach(removeDatabase(_))
        removeDatabase(wrongPrefixName)
    }

    it should "replay a database" in {
        val now = Instant.now()
        val dbName = testDbPrefix + "database_to_be_restored"
        val backupPrefix = s"backup_${toEpochSeconds(now)}_"
        val backupDbName = backupPrefix + dbName

        // Create a database that looks like a backup
        val backupClient = createDatabase(backupDbName)
        println(s"Creating testdocument")
        backupClient.putDoc("testId", JsObject("testKey" -> "testValue".toJson)).futureValue

        // Run the replay script
        val (_, _, replicationId) = runReplay(dbUrl, dbUrl, backupPrefix).head

        // Wait for the replication to finish
        waitForReplication(replicationId)

        // Verify the replicated database is equal to the original database
        compareDatabases(Seq(backupDbName, dbName))

        // Cleanup databases
        removeDatabase(backupDbName)
        removeDatabase(dbName)
    }
}
