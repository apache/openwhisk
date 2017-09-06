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

import scala.concurrent.duration.DurationInt
import scala.io.Source

import org.scalatest.Matchers
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.concurrent.ScalaFutures

import akka.actor.ActorSystem
import common.WaitFor
import common.WhiskProperties
import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.core.WhiskConfig
import whisk.core.database.CouchDbRestClient

trait DatabaseScriptTestUtils extends ScalaFutures with Matchers with WaitFor with IntegrationPatience {

  val python = WhiskProperties.python

  val dbProtocol = WhiskProperties.getProperty(WhiskConfig.dbProtocol)
  val dbHost = WhiskProperties.getProperty(WhiskConfig.dbHost)
  val dbPort = WhiskProperties.getProperty(WhiskConfig.dbPort)
  val dbUsername = WhiskProperties.getProperty(WhiskConfig.dbUsername)
  val dbPassword = WhiskProperties.getProperty(WhiskConfig.dbPassword)
  val dbPrefix = WhiskProperties.getProperty(WhiskConfig.dbPrefix)
  val dbUrl = s"${dbProtocol}://${dbUsername}:${dbPassword}@${dbHost}:${dbPort}"

  def retry[T](task: => T) = whisk.utils.retry(task, 10, Some(500.milliseconds))

  /** Creates a new database with the given name */
  def createDatabase(name: String, designDocPath: Option[String])(implicit as: ActorSystem, logging: Logging) = {
    // Implicitly remove database for sanitization purposes
    removeDatabase(name, true)

    println(s"Creating database: $name")
    val db = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, name)
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
    val client = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, dbName)
    waitfor(() => {
      client.getAllDocs(includeDocs = Some(true)).futureValue.isRight
    })
    client
  }

  /** Removes the database with the given name */
  def removeDatabase(name: String, ignoreFailure: Boolean = false)(implicit as: ActorSystem, logging: Logging) = {
    println(s"Removing database: $name")
    val db = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, name)
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
    val client = new ExtendedCouchDbRestClient(dbProtocol, dbHost, dbPort.toInt, dbUsername, dbPassword, dbName)
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
