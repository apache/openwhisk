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

package whisk.core.database

import java.io.File

import akka.stream.scaladsl.{FileIO, Sink}
import common.TestFolder
import org.junit.runner.RunWith
import org.scalactic.Uniformity
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import spray.json.{DefaultJsonProtocol, JsObject}
import whisk.common.TransactionId
import whisk.core.cli.CommandMessages
import whisk.core.database.DbCommand._
import whisk.core.entity.{DocInfo, WhiskDocument, WhiskEntity}

import scala.util.Try

@RunWith(classOf[JUnitRunner])
class DbCommandTests
    extends FlatSpec
    with WhiskAdminCliTestBase
    with TestFolder
    with ArtifactNamingHelper
    with DefaultJsonProtocol {
  behavior of "db get"

  it should "get all artifacts" in {
    implicit val tid: TransactionId = transid()
    val actions = List.tabulate(10)(_ => newAction(newNS()))
    actions foreach (put(entityStore, _))
    val actionIds = actions.map(_.docid.id).toSet
    val actionJsons = actions.map(_.toDocumentRecord)

    val outFile = newFile()

    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)

    (collectedEntities(outFile, idFilter(actionIds)) should contain theSameElementsAs actionJsons)(
      after being strippedOfRevision)

    collectedEntities(outFile, js => idOf(js).startsWith("_design/")) shouldBe empty
  }

  behavior of "db put"

  it should "put all entities" in {
    implicit val tid: TransactionId = transid()
    val actions = List.tabulate(10)(_ => newAction(newNS()))
    actions foreach (put(entityStore, _))

    val actionIds = actions.map(_.docid.id).toSet
    val actionJsons = actions.map(_.toDocumentRecord)

    val outFile = newFile()

    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)
    cleanup()

    val inFile = copyEntities(outFile, idFilter(actionIds))
    resultOk("db", "put", "--in", inFile.getAbsolutePath, "whisks") shouldBe CommandMessages.putDocs(10)

    cleanup[WhiskEntity](actionIds, entityStore)
  }

  private def idFilter(ids: Set[String]): JsObject => Boolean = js => ids.contains(idOf(js))

  private def idOf(js: JsObject) = js.fields("_id").convertTo[String]

  private def collectedEntities(file: File, filter: JsObject => Boolean) =
    DbCommand
      .createJSStream(file)
      .filter(filter)
      .runWith(Sink.seq)
      .futureValue

  private def copyEntities(file: File, filter: JsObject => Boolean): File = {
    val out = newFile()
    DbCommand
      .createJSStream(file)
      .filter(filter)
      .map(jsToStringLine)
      .runWith(FileIO.toPath(out.toPath))
      .futureValue
    out
  }

  private def cleanup[A <: WhiskDocument: Manifest](ids: TraversableOnce[String], store: ArtifactStore[A]): Unit = {
    implicit val tid = TransactionId.testing
    ids.map { u =>
      Try {
        val doc = store.get[A](DocInfo(u)).futureValue
        delete(store, doc.docinfo)
      }
    }
  }

  /**
   * Strips of the '_rev' field to allow comparing jsons where only rev may differ
   */
  private object strippedOfRevision extends Uniformity[JsObject] {
    override def normalizedOrSame(b: Any) = b match {
      case s: JsObject => normalized(s)
      case _           => b
    }
    override def normalizedCanHandle(b: Any) = b.isInstanceOf[JsObject]
    override def normalized(js: JsObject) = JsObject(js.fields - "_rev")
  }
}
