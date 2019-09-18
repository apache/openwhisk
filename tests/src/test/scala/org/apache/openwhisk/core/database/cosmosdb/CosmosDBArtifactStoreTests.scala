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

import com.typesafe.config.ConfigFactory
import io.netty.util.ResourceLeakDetector
import io.netty.util.ResourceLeakDetector.Level
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.DocumentSerializer
import org.apache.openwhisk.core.database.memory.MemoryAttachmentStoreProvider
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreBehavior
import org.apache.openwhisk.core.entity.WhiskQueries.TOP
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{
  DocumentReader,
  WhiskActivation,
  WhiskDocumentReader,
  WhiskEntity,
  WhiskEntityJsonFormat,
  WhiskPackage
}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.FlatSpec
import spray.json.JsString
import org.apache.openwhisk.core.entity.size._
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.reflect.ClassTag

@RunWith(classOf[JUnitRunner])
class CosmosDBArtifactStoreTests extends FlatSpec with CosmosDBStoreBehaviorBase with ArtifactStoreBehavior {
  override protected def maxAttachmentSizeWithoutAttachmentStore = 1.MB

  private var initialLevel: Level = _

  override protected def beforeAll(): Unit = {
    RecordingLeakDetectorFactory.register()
    initialLevel = ResourceLeakDetector.getLevel
    ResourceLeakDetector.setLevel(Level.PARANOID)
    super.beforeAll()
  }

  override protected def getAttachmentStore[D <: DocumentSerializer: ClassTag]() =
    Some(MemoryAttachmentStoreProvider.makeStore[D]())

  override def afterAll(): Unit = {
    super.afterAll()
    ResourceLeakDetector.setLevel(initialLevel)

    //Try triggering GC which may trigger leak detection logic
    System.gc()

    withClue("Recorded leak count should be zero") {
      RecordingLeakDetectorFactory.counter.cur shouldBe 0
    }
  }

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

  it should "have clusterId set" in {
    implicit val tid: TransactionId = TransactionId.testing
    implicit val docReader: DocumentReader = WhiskDocumentReader
    implicit val format = WhiskEntityJsonFormat
    val conf = ConfigFactory.parseString(s"""
      | whisk.cosmosdb {
      |  collections {
      |     WhiskEntity = {
      |        cluster-id = "foo"
      |     }
      |  }
      | }
         """.stripMargin).withFallback(ConfigFactory.load())

    val cosmosDBConfig = CosmosDBConfig(conf, "WhiskEntity")
    cosmosDBConfig.clusterId shouldBe Some("foo")

    val testConfig = cosmosDBConfig.copy(db = config.db)
    val store = CosmosDBArtifactStoreProvider.makeArtifactStore[WhiskEntity](testConfig, None)

    val pkg = WhiskPackage(newNS(), aname())
    val info = put(store, pkg)

    val js = store.getRaw(info.id).futureValue
    js.get.fields(CosmosDBConstants.clusterId) shouldBe JsString("foo")
  }

  it should "fetch collection usage info" in {
    val uopt = activationStore.getResourceUsage().futureValue
    uopt shouldBe defined
    val u = uopt.get
    println(u.asString)
    u.documentsCount shouldBe defined
    u.documentsSize shouldBe defined
  }

  behavior of "CosmosDB query debug"

  it should "log query metrics in debug flow" in {
    val debugTid = TransactionId("42", extraLogging = true)
    val tid = TransactionId("42")
    val ns = newNS()
    val activations = (1000 until 1100 by 10).map(newActivation(ns.asString, "testact", _))
    activations foreach (put(activationStore, _)(tid))

    val entityPath = s"${ns.asString}/testact"
    stream.reset()
    query[WhiskActivation](
      activationStore,
      WhiskActivation.filtersView.name,
      List(entityPath, 1050),
      List(entityPath, TOP, TOP))(tid)

    stream.toString should not include ("[QueryMetricsEnabled]")
    stream.reset()

    query[WhiskActivation](
      activationStore,
      WhiskActivation.filtersView.name,
      List(entityPath, 1050),
      List(entityPath, TOP, TOP))(debugTid)
    stream.toString should include("[QueryMetricsEnabled]")

  }
}
