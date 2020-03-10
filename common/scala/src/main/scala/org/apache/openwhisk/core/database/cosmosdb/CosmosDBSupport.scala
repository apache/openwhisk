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

import com.microsoft.azure.cosmosdb.{
  Database,
  DocumentCollection,
  FeedResponse,
  RequestOptions,
  Resource,
  SqlParameter,
  SqlParameterCollection,
  SqlQuerySpec
}
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import org.apache.openwhisk.common.Logging

import scala.collection.JavaConverters._

private[cosmosdb] trait CosmosDBSupport extends RxObservableImplicits with CosmosDBUtil {
  protected def config: CosmosDBConfig
  protected def collName: String
  protected def client: AsyncDocumentClient
  protected def viewMapper: CosmosDBViewMapper

  def initialize()(implicit logging: Logging): (Database, DocumentCollection) = {
    val db = getOrCreateDatabase()
    (db, getOrCreateCollection(db))
  }

  private def getOrCreateDatabase()(implicit logging: Logging): Database = {
    client
      .queryDatabases(querySpec(config.db), null)
      .blockingOnlyResult()
      .getOrElse {
        client.createDatabase(newDatabase, null).blockingResult()
      }
  }

  private def getOrCreateCollection(database: Database)(implicit logging: Logging) = {
    client
      .queryCollections(database.getSelfLink, querySpec(collName), null)
      .blockingOnlyResult()
      .map { coll =>
        val expectedIndexingPolicy = viewMapper.indexingPolicy
        val existingIndexingPolicy = IndexingPolicy(coll.getIndexingPolicy)
        if (!IndexingPolicy.isSame(expectedIndexingPolicy, existingIndexingPolicy)) {
          logging.warn(
            this,
            s"Indexing policy for collection [$collName] found to be different." +
              s"\nExpected - ${expectedIndexingPolicy.asJava().toJson}" +
              s"\nExisting - ${existingIndexingPolicy.asJava().toJson}")
        }
        coll
      }
      .getOrElse {
        client.createCollection(database.getSelfLink, newDatabaseCollection, dbOptions).blockingResult()
      }
  }

  private def newDatabaseCollection = {
    val defn = new DocumentCollection
    defn.setId(collName)
    defn.setIndexingPolicy(viewMapper.indexingPolicy.asJava())
    defn.setPartitionKey(viewMapper.partitionKeyDefn)
    val ttl = config.timeToLive.map(_.toSeconds.toInt).getOrElse(-1)
    defn.setDefaultTimeToLive(ttl)
    defn
  }

  private def dbOptions = {
    val opts = new RequestOptions
    opts.setOfferThroughput(config.throughput)
    opts
  }

  private def newDatabase = {
    val databaseDefinition = new Database
    databaseDefinition.setId(config.db)
    databaseDefinition
  }

  /**
   * Prepares a query for fetching any resource by id
   */
  protected def querySpec(id: String) =
    new SqlQuerySpec("SELECT * FROM root r WHERE r.id=@id", new SqlParameterCollection(new SqlParameter("@id", id)))

  protected def asVector[T <: Resource](r: FeedResponse[T]): Vector[T] = r.getResults.asScala.toVector
}
