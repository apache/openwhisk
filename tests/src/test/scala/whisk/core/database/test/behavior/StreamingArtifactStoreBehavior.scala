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

package whisk.core.database.test.behavior

import akka.stream.scaladsl.{Flow, Keep, Sink}
import spray.json.JsObject
import whisk.common.TransactionId
import whisk.core.database.test.behavior.ArtifactStoreTestUtil._
import whisk.core.database.StreamingArtifactStore

trait StreamingArtifactStoreBehavior extends ArtifactStoreBehaviorBase {

  def entityStreamingStore: StreamingArtifactStore

  behavior of s"${storeType}StreamingArtifactStore getAll"

  it should "get all documents" in {
    implicit val tid: TransactionId = transid()
    val actions = List.tabulate(10)(_ => newAction(newNS()))
    val actionJsons = actions.map(_.toDocumentRecord)
    actions foreach (put(entityStore, _))

    val actionIds = actions.map(_.docid.id).toSet
    (collectedEntities(idFilter(actionIds)) should contain theSameElementsAs actionJsons)(
      after being strippedOfRevision)

  }

  private def collectedEntities(filter: JsObject => Boolean)(implicit transid: TransactionId) = {
    val sink = Flow[JsObject]
      .filter(filter)
      .toMat(Sink.seq[JsObject])(Keep.right)
    entityStreamingStore.getAll(sink).futureValue._2
  }
}
