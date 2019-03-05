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
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class WhisksCacheEventProducerTests extends FlatSpec with Matchers {
  behavior of "CosmosDB extract LSN from Session token"

  it should "parse old session token" in {
    WhisksCacheEventProducer.getSessionLsn("0:12345") shouldBe 12345
  }

  it should "parse new session token" in {
    WhisksCacheEventProducer.getSessionLsn("0:-1#12345") shouldBe 12345
  }

  it should "parse new session token with multiple regional lsn" in {
    WhisksCacheEventProducer.getSessionLsn("0:-1#12345#Region1=1#Region2=2") shouldBe 12345
  }
}
