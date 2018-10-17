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

package org.apache.openwhisk.core.database.cosmosdb

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreBehavior

@RunWith(classOf[JUnitRunner])
class CosmosDBArtifactStoreTests extends FlatSpec with CosmosDBStoreBehaviorBase with ArtifactStoreBehavior {
  override protected def maxAttachmentSizeWithoutAttachmentStore = 1.MB

  behavior of "CosmosDB Setup"

  it should "be configured with default throughput" in {
    //Trigger loading of the db
    val stores = Seq(entityStore, authStore, activationStore)
    stores.foreach { s =>
      val doc = s.asInstanceOf[CosmosDBArtifactStore[_]].documentCollection()
      val offer = client
        .queryOffers(s"SELECT * from c where c.offerResourceId = '${doc.getResourceId}'", null)
        .blockingOnlyResult()
        .get
      withClue(s"Collection ${doc.getId} : ") {
        offer.getThroughput shouldBe storeConfig.throughput
      }
    }
  }
}
