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
import org.apache.openwhisk.core.database.DocumentSerializer
import org.apache.openwhisk.core.database.memory.MemoryAttachmentStoreProvider
import org.apache.openwhisk.core.database.test.behavior.{ArtifactStoreCRUDBehaviors, ArtifactStoreQueryBehaviors}
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner

import scala.reflect.ClassTag
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class CosmosDBSoftDeleteTests
    extends FlatSpec
    with CosmosDBStoreBehaviorBase
    with ArtifactStoreCRUDBehaviors
    with ArtifactStoreQueryBehaviors {
  override def storeType = "CosmosDB_SoftDelete"

  override protected def getAttachmentStore[D <: DocumentSerializer: ClassTag]() =
    Some(MemoryAttachmentStoreProvider.makeStore[D]())

  override def adaptCosmosDBConfig(config: CosmosDBConfig): CosmosDBConfig =
    config.copy(softDeleteTTL = Some(15.minutes))

}
