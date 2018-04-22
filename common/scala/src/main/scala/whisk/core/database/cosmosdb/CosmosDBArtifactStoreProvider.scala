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

import java.io.Closeable

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.microsoft.azure.cosmosdb.{ConnectionPolicy, ConsistencyLevel}
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.database._
import pureconfig._
import whisk.core.ConfigKeys
import whisk.core.entity.{DocumentReader, WhiskActivation, WhiskAuth, WhiskEntity}

import scala.reflect.ClassTag

case class CosmosDBConfig(endpoint: String, key: String, db: String)

case class ClientHolder(client: AsyncDocumentClient) extends Closeable {
  override def close(): Unit = client.close()
}

object CosmosDBArtifactStoreProvider extends ArtifactStoreProvider {
  type DocumentClientRef = ReferenceCounted[ClientHolder]#CountedReference
  private lazy val config = loadConfigOrThrow[CosmosDBConfig](ConfigKeys.cosmosdb)
  private var clientRef: ReferenceCounted[ClientHolder] = _

  override def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {
    makeStoreForClient(config, useBatching, getOrCreateReference(config))
  }

  def makeStore[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig, useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {

    makeStoreForClient(config, useBatching, createReference(config).reference())
  }

  private def makeStoreForClient[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig,
                                                                    useBatching: Boolean,
                                                                    clientRef: DocumentClientRef)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {

    val classTag = implicitly[ClassTag[D]]
    val (dbName, handler, viewMapper) = handlerAndMapper(classTag)

    new CosmosDBArtifactStore(dbName, config, clientRef, handler, viewMapper)
  }

  private def handlerAndMapper[D](entityType: ClassTag[D])(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): (String, DocumentHandler, CosmosDBViewMapper) = {
    entityType.runtimeClass match {
      case x if x == classOf[WhiskEntity] =>
        ("whisks", WhisksHandler, WhisksViewMapper)
      case x if x == classOf[WhiskActivation] =>
        ("activations", ActivationHandler, ActivationViewMapper)
      case x if x == classOf[WhiskAuth] =>
        ("subjects", SubjectHandler, SubjectViewMapper)
    }
  }

  private def getOrCreateReference(config: CosmosDBConfig) = synchronized {
    if (clientRef == null || clientRef.isClosed) {
      clientRef = createReference(config)
    }
    clientRef.reference()
  }

  private def createReference(config: CosmosDBConfig) =
    new ReferenceCounted[ClientHolder](ClientHolder(createClient(config)))

  private def createClient(config: CosmosDBConfig): AsyncDocumentClient =
    new AsyncDocumentClient.Builder()
      .withServiceEndpoint(config.endpoint)
      .withMasterKey(config.key)
      .withConnectionPolicy(ConnectionPolicy.GetDefault)
      .withConsistencyLevel(ConsistencyLevel.Session)
      .build
}
