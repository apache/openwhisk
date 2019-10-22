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
import com.azure.data.cosmos.CosmosItemProperties
import common.StreamLogging
import org.apache.openwhisk.core.database.CacheInvalidationMessage
import org.apache.openwhisk.core.entity.CacheKey
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol

import scala.collection.immutable.Seq

@RunWith(classOf[JUnitRunner])
class WhiskChangeEventObserverTests extends FlatSpec with Matchers with StreamLogging {
  import WhiskChangeEventObserver.instanceId

  behavior of "CosmosDB extract LSN from Session token"

  it should "parse old session token" in {
    WhiskChangeEventObserver.getSessionLsn("0:12345") shouldBe 12345
  }

  it should "parse new session token" in {
    WhiskChangeEventObserver.getSessionLsn("0:-1#12345") shouldBe 12345
  }

  it should "parse new session token with multiple regional lsn" in {
    WhiskChangeEventObserver.getSessionLsn("0:-1#12345#Region1=1#Region2=2") shouldBe 12345
  }

  behavior of "CosmosDB feed events"

  it should "generate cache events" in {
    val config = InvalidatorConfig(8080, None)
    val docs = Seq(createDoc("foo"), createDoc("bar"))
    val processedDocs = WhiskChangeEventObserver.processDocs(docs, config)

    processedDocs.map(CacheInvalidationMessage.parse(_).get) shouldBe Seq(
      CacheInvalidationMessage(CacheKey("foo"), instanceId),
      CacheInvalidationMessage(CacheKey("bar"), instanceId))
  }

  it should "filter clusterId" in {
    val config = InvalidatorConfig(8080, Some("cid1"))
    val docs = Seq(createDoc("foo", Some("cid2")), createDoc("bar", Some("cid1")), createDoc("baz"))
    val processedDocs = WhiskChangeEventObserver.processDocs(docs, config)

    //Should not include bar as the clusterId matches
    processedDocs.map(CacheInvalidationMessage.parse(_).get) shouldBe Seq(
      CacheInvalidationMessage(CacheKey("foo"), instanceId),
      CacheInvalidationMessage(CacheKey("baz"), instanceId))
  }

  private def createDoc(id: String, clusterId: Option[String] = None): CosmosItemProperties = {
    val cdoc = CosmosDBDoc(id, clusterId)
    val json = CosmosDBDoc.seredes.write(cdoc).compactPrint
    new CosmosItemProperties(json)
  }

  case class CosmosDBDoc(id: String, _clusterId: Option[String], _lsn: Int = 42)

  object CosmosDBDoc extends DefaultJsonProtocol {
    implicit val seredes = jsonFormat3(CosmosDBDoc.apply)
  }

}
