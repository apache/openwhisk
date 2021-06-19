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

import com.microsoft.azure.cosmosdb.IndexKind.Range
import com.microsoft.azure.cosmosdb.DataType.String
import com.microsoft.azure.cosmosdb.DocumentCollection
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import com.typesafe.config.ConfigFactory
import common.{StreamLogging, WskActorSystem}
import org.apache.openwhisk.core.entity.{
  DocumentReader,
  WhiskActivation,
  WhiskDocumentReader,
  WhiskEntity,
  WhiskEntityJsonFormat
}
import org.junit.runner.RunWith
import org.scalamock.scalatest.MockFactory
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class CosmosDBSupportTests
    extends FlatSpec
    with CosmosDBTestSupport
    with MockFactory
    with Matchers
    with StreamLogging
    with WskActorSystem {

  behavior of "CosmosDB init"

  it should "create and update index" in {
    val testDb = createTestDB()
    val config: CosmosDBConfig = storeConfig.copy(db = testDb.getId)

    val indexedPaths1 = Set("/foo/?", "/bar/?")
    val (_, coll) = new CosmosTest(config, client, newMapper(indexedPaths1)).initialize()
    coll.getDefaultTimeToLive shouldBe -1
    indexedPaths(coll) should contain theSameElementsAs indexedPaths1
  }

  it should "set ttl" in {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  collections {
      |     WhiskActivation = {
      |        time-to-live = 60 s
      |     }
      |  }
      | }
         """.stripMargin).withFallback(ConfigFactory.load())

    val cosmosDBConfig = CosmosDBConfig(config, "WhiskActivation")
    cosmosDBConfig.timeToLive shouldBe Some(60.seconds)

    val testDb = createTestDB()
    val testConfig = cosmosDBConfig.copy(db = testDb.getId)
    val coll = CosmosDBArtifactStoreProvider.makeArtifactStore[WhiskActivation](testConfig, None).collection
    coll.getDefaultTimeToLive shouldBe 60.seconds.toSeconds
  }

  it should "not set ttl for WhiskEntity" in {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    implicit val format = WhiskEntityJsonFormat
    val config = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  collections {
      |     WhiskEntity = {
      |        time-to-live = 60 s
      |     }
      |  }
      | }
         """.stripMargin).withFallback(ConfigFactory.load())

    val cosmosDBConfig = CosmosDBConfig(config, "WhiskEntity")
    cosmosDBConfig.timeToLive shouldBe Some(60.seconds)

    val testConfig = cosmosDBConfig.copy(db = "foo")

    an[IllegalArgumentException] shouldBe thrownBy {
      CosmosDBArtifactStoreProvider.makeArtifactStore[WhiskEntity](testConfig, None).collection
    }
  }

  private def newMapper(paths: Set[String]) = {
    val mapper = stub[CosmosDBViewMapper]
    (mapper.indexingPolicy _).when().returns(newTestIndexingPolicy(paths))
    mapper
  }

  private def indexedPaths(coll: DocumentCollection) =
    coll.getIndexingPolicy.getIncludedPaths.asScala.map(_.getPath).toList

  protected def newTestIndexingPolicy(paths: Set[String]): IndexingPolicy =
    IndexingPolicy(includedPaths = paths.map(p => IncludedPath(p, Index(Range, String, -1))))

  private class CosmosTest(override val config: CosmosDBConfig,
                           override val client: AsyncDocumentClient,
                           mapper: CosmosDBViewMapper)
      extends CosmosDBSupport {
    override protected def collName = "test"
    override protected def viewMapper = mapper
  }
}
