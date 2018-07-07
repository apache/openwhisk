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

package whisk.core.database.cosmosdb

import com.microsoft.azure.cosmosdb._
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient

import scala.collection.JavaConverters._
import scala.collection.immutable

private[cosmosdb] trait CosmosDBSupport extends RxObservableImplicits with CosmosDBUtil {
  protected def config: CosmosDBConfig
  protected def collName: String
  protected def client: AsyncDocumentClient
  protected def viewMapper: CosmosDBViewMapper

  def initialize(): (Database, DocumentCollection) = {
    val db = getOrCreateDatabase()
    (db, getOrCreateCollection(db))
  }

  private def getOrCreateDatabase(): Database = {
    client
      .queryDatabases(querySpec(config.db), null)
      .blockingOnlyResult()
      .getOrElse {
        client.createDatabase(newDatabase, null).blockingResult()
      }
  }

  private def getOrCreateCollection(database: Database) = {
    client
      .queryCollections(database.getSelfLink, querySpec(collName), null)
      .blockingOnlyResult()
      .map { coll =>
        if (matchingIndexingPolicy(coll)) {
          coll
        } else {
          //Modify the found collection with latest policy as its selfLink is set
          coll.setIndexingPolicy(viewMapper.indexingPolicy.asJava())
          client.replaceCollection(coll, null).blockingResult()
        }
      }
      .getOrElse {
        client.createCollection(database.getSelfLink, newDatabaseCollection, null).blockingResult()
      }
  }

  private def matchingIndexingPolicy(coll: DocumentCollection): Boolean =
    IndexingPolicy.isSame(viewMapper.indexingPolicy, IndexingPolicy(coll.getIndexingPolicy))

  private def newDatabaseCollection = {
    val defn = new DocumentCollection
    defn.setId(collName)
    defn.setIndexingPolicy(viewMapper.indexingPolicy.asJava())
    defn.setPartitionKey(viewMapper.partitionKeyDefn)
    defn
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

  protected def asSeq[T <: Resource](r: FeedResponse[T]): immutable.Seq[T] = r.getResults.asScala.to[immutable.Seq]
}
