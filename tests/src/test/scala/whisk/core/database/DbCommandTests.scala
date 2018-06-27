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

import akka.stream.scaladsl.Sink
import common.TestFolder
import org.junit.runner.RunWith
import org.scalactic.Uniformity
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import spray.json.{DefaultJsonProtocol, JsObject}
import whisk.common.TransactionId

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

    val idFilter: JsObject => Boolean = js => actionIds.contains(js.fields("_id").convertTo[String])
    (collectedEntities(outFile, idFilter) should contain theSameElementsAs actionJsons)(after being strippedOfRevision)
  }

  private def collectedEntities(file: File, filter: JsObject => Boolean) =
    DbCommand
      .createJSStream(file)
      .filter(filter)
      .runWith(Sink.seq)
      .futureValue

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
