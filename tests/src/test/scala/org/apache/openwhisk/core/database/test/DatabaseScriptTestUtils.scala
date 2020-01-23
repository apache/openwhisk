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

import scala.concurrent.duration.DurationInt
import scala.io.Source
import org.scalatest.Matchers
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures
import akka.actor.ActorSystem
import common.WaitFor
import common.WhiskProperties
import pureconfig._
import pureconfig.generic.auto._
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.database.CouchDbRestClient
import org.apache.openwhisk.core.database.CouchDbConfig

trait DatabaseScriptTestUtils extends ScalaFutures with Matchers with WaitFor with IntegrationPatience {

  case class DatabaseUrl(dbProtocol: String, dbUsername: String, dbPassword: String, dbHost: String, dbPort: String) {
    def url = s"$dbProtocol://$dbUsername:$dbPassword@$dbHost:$dbPort"

    def safeUrl = s"$dbProtocol://$dbHost:$dbPort"
  }

  val python = WhiskProperties.python
  val config = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
  val dbProtocol = config.protocol
  val dbHost = config.host
  val dbPort = config.port
  val dbUsername = config.username
  val dbPassword = config.password
  val dbPrefix = WhiskProperties.getProperty(WhiskConfig.dbPrefix)
  val dbUrl = DatabaseUrl(dbProtocol, dbUsername, dbPassword, dbHost, dbPort.toString)

  def retry[T](task: => T) = org.apache.openwhisk.utils.retry(task, 10, Some(500.milliseconds))

  /** Creates a new database with the given name */
  def createDatabase(name: String, designDocPath: Option[String])(implicit as: ActorSystem, logging: Logging) = {
    // Implicitly remove database for sanitization purposes
    removeDatabase(name, ignoreFailure = true)

    println(s"Creating database: $name")
    val db = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort, dbUsername, dbPassword, name)
    retry(db.createDb().futureValue shouldBe 'right)

    retry {
      val list = db.dbs().futureValue.right.get
      list should contain(name)
    }

    designDocPath.map { path =>
      val designDoc = Source.fromFile(path).mkString.parseJson.asJsObject
      db.putDoc(designDoc.fields("_id").convertTo[String], designDoc).futureValue
    }

    db
  }

  /** Wait for database to appear */
  def waitForDatabase(dbName: String)(implicit as: ActorSystem, logging: Logging) = {
    val client = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort, dbUsername, dbPassword, dbName)
    waitfor(() => {
      client.getAllDocs(includeDocs = Some(true)).futureValue.isRight
    })
    client
  }

  /** Removes the database with the given name */
  def removeDatabase(name: String, ignoreFailure: Boolean = false)(implicit as: ActorSystem, logging: Logging) = {
    println(s"Removing database: $name")
    val db = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort, dbUsername, dbPassword, name)
    retry {
      val delete = db.deleteDb().futureValue
      if (!ignoreFailure) delete shouldBe 'right
    }
    db
  }

  /** Wait for a document to appear */
  def waitForDocument(client: ExtendedCouchDbRestClient, id: String) =
    waitfor(() => client.getDoc(id).futureValue.isRight)

  /** Get all docs within one database */
  def getAllDocs(dbName: String)(implicit as: ActorSystem, logging: Logging) = {
    val client = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort, dbUsername, dbPassword, dbName)
    val documents = client.getAllDocs(includeDocs = Some(true)).futureValue
    documents shouldBe 'right
    documents.right.get
  }

  /** wait until all documents are processed by the view */
  def waitForView(db: CouchDbRestClient, designDoc: String, viewName: String, numDocuments: Int) = {
    waitfor(() => {
      val view = db.executeView(designDoc, viewName)().futureValue
      view shouldBe 'right
      view.right.get.fields("rows").convertTo[List[JsObject]].length == numDocuments
    }, totalWait = 2.minutes)
  }
}
