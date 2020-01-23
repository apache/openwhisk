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

package org.apache.openwhisk.core.database.cosmosdb.cache
import akka.Done
import akka.actor.CoordinatedShutdown
import akka.kafka.testkit.scaladsl.{EmbeddedKafkaLike, ScalatestKafkaSpec}
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import common.StreamLogging
import net.manub.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{CacheInvalidationMessage, RemoteCacheInvalidation}
import org.apache.openwhisk.core.database.cosmosdb.{CosmosDBArtifactStoreProvider, CosmosDBTestSupport}
import org.apache.openwhisk.core.entity.{
  DocumentReader,
  EntityName,
  EntityPath,
  WhiskDocumentReader,
  WhiskEntity,
  WhiskEntityJsonFormat,
  WhiskPackage
}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, TryValues}

import scala.concurrent.duration._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class CacheInvalidatorTests
    extends ScalatestKafkaSpec(6061)
    with EmbeddedKafkaLike
    with EmbeddedKafka
    with CosmosDBTestSupport
    with Matchers
    with ScalaFutures
    with TryValues
    with StreamLogging {

  private implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 300.seconds)

  override def createKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(kafkaPort, zooKeeperPort)

  behavior of "CosmosDB CacheInvalidation"

  private val server = s"localhost:$kafkaPort"
  private var dbName: String = _

  override def afterAll(): Unit = {
    super.afterAll()
    CoordinatedShutdown(system).run(CoordinatedShutdown.ClusterDowningReason)
    shutdown()
  }

  it should "send event upon entity change" in {
    implicit val tid = TransactionId.testing
    implicit val docReader: DocumentReader = WhiskDocumentReader
    implicit val format = WhiskEntityJsonFormat
    dbName = createTestDB().getId
    val dbConfig = storeConfig.copy(db = dbName)
    val store = CosmosDBArtifactStoreProvider.makeArtifactStore[WhiskEntity](dbConfig, None)
    val pkg = WhiskPackage(EntityPath("cacheInvalidationTest"), EntityName(randomString()))

    //Start cache invalidator after the db for whisks is created
    startCacheInvalidator()
    log.info("Cache Invalidator service started")

    //Store stuff in db
    val info = store.put(pkg).futureValue
    log.info(s"Added document ${info.id}")

    //This should result in change feed trigger and event to kafka topic
    val topic = RemoteCacheInvalidation.cacheInvalidationTopic
    val msgs =
      consumeNumberMessagesFromTopics(Set(topic), 1, timeout = 60.seconds)(createKafkaConfig, new StringDeserializer())(
        topic)

    CacheInvalidationMessage.parse(msgs.head).get.key.mainId shouldBe pkg.docid.asString

    store.del(info).futureValue
  }

  private def randomString() = Random.alphanumeric.take(5).mkString

  private def startCacheInvalidator() = {
    val tsconfig = ConfigFactory.parseString(s"""
      |akka.kafka.producer {
      |  kafka-clients {
      |    bootstrap.servers = "$server"
      |  }
      |}
      |whisk {
      |  cache-invalidator {
      |    cosmosdb {
      |      db = "$dbName"
      |      start-from-beginning  = true
      |    }
      |  }
      |}
      """.stripMargin).withFallback(ConfigFactory.load())
    CacheInvalidator.start(tsconfig).futureValue shouldBe Done
  }

}
