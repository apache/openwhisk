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
import _root_.rx.lang.scala._
import _root_.rx.lang.scala.JavaConverters._

import scala.collection.JavaConverters._
import scala.collection.immutable

trait CosmosDBSupport {
  protected def config: CosmosDBConfig
  protected def collName: String
  protected def client: AsyncDocumentClient

  def initialize(): (Database, DocumentCollection) = {
    val db = getOrCreateDatabase()
    (db, getOrCreateCollection(db))
  }

  private def getOrCreateDatabase(): Database = {
    blockingResult[Database](client.queryDatabases(querySpec(config.db), null).asScala).getOrElse {
      val databaseDefinition = new Database
      databaseDefinition.setId(config.db)
      blockingResult(client.createDatabase(databaseDefinition, null).asScala)
    }
  }

  private def getOrCreateCollection(database: Database) = {
    blockingResult[DocumentCollection](
      client
        .queryCollections(database.getSelfLink, querySpec(collName), null)
        .asScala).getOrElse {
      val collectionDefinition = new DocumentCollection
      collectionDefinition.setId(collName)
      blockingResult(client.createCollection(database.getSelfLink, collectionDefinition, null).asScala)
    }
  }

  private def blockingResult[T <: Resource](response: Observable[FeedResponse[T]]) = {
    val value = response.toList.toBlocking.single
    value.head.getResults.asScala.headOption
  }

  private def blockingResult[T <: Resource](response: Observable[ResourceResponse[T]]) = {
    response.toBlocking.single.getResource
  }

  protected def querySpec(id: String) =
    new SqlQuerySpec("SELECT * FROM root r WHERE r.id=@id", new SqlParameterCollection(new SqlParameter("@id", id)))

  /**
   * CosmosDB id considers '/', '\' , '?' and '#' as invalid. EntityNames can include '/' so
   * that need to be escaped. For that we use '|' as the replacement char
   */
  protected def escapeId(id: String): String = id.replace("/", "|")

  protected def unescapeId(id: String): String = id.replace("|", "/")

  protected def asSeq[T <: Resource](r: FeedResponse[T]): immutable.Seq[T] = r.getResults.asScala.to[immutable.Seq]
}
