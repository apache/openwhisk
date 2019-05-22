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

import org.apache.openwhisk.core.database.cosmosdb.CollectionResourceUsage.{indexHeader, quotaHeader, usageHeader}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import org.apache.openwhisk.core.entity.size._

@RunWith(classOf[JUnitRunner])
class CollectionResourceUsageTests extends FlatSpec with Matchers {
  behavior of "CollectionInfo"

  it should "populate resource usage info" in {
    val headers = Map(
      usageHeader ->
        "storedProcedures=0;triggers=0;functions=0;documentsCount=5058;documentsSize=780;collectionSize=800",
      quotaHeader -> "storedProcedures=100;triggers=25;functions=25;documentsCount=-1;documentsSize=335544320;collectionSize=1000",
      indexHeader -> "42")

    val usage = CollectionResourceUsage(headers).get
    usage shouldBe CollectionResourceUsage(
      documentsSize = Some(780.KB),
      collectionSize = Some(800.KB),
      documentsCount = Some(5058),
      indexingProgress = Some(42),
      documentsSizeQuota = Some(1000.KB))
  }
}
