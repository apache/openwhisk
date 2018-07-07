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
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.core.database._
import pureconfig._
import whisk.core.entity.size._
import whisk.core.ConfigKeys
import whisk.core.database.cosmosdb.CosmosDBUtil.createClient
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
    makeStoreForClient(config, getOrCreateReference(config), getAttachmentStore())
  }

  def makeArtifactStore[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig,
                                                           attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {

    makeStoreForClient(config, createReference(config).reference(), attachmentStore)
  }

  private def makeStoreForClient[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig,
                                                                    clientRef: DocumentClientRef,
                                                                    attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {

    val classTag = implicitly[ClassTag[D]]
    val (dbName, handler, viewMapper) = handlerAndMapper(classTag)

    new CosmosDBArtifactStore(
      dbName,
      config,
      clientRef,
      handler,
      viewMapper,
      loadConfigOrThrow[InliningConfig](ConfigKeys.db),
      attachmentStore)
  }

  private def handlerAndMapper[D](entityType: ClassTag[D])(
    implicit actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): (String, DocumentHandler, CosmosDBViewMapper) = {
    val entityClass = entityType.runtimeClass
    if (entityClass == classOf[WhiskEntity]) ("whisks", WhisksHandler, WhisksViewMapper)
    else if (entityClass == classOf[WhiskActivation]) ("activations", ActivationHandler, ActivationViewMapper)
    else if (entityClass == classOf[WhiskAuth]) ("subjects", SubjectHandler, SubjectViewMapper)
    else throw new IllegalArgumentException(s"Unsupported entity type $entityType")
  }

  /*
   * This method ensures that all store instances share same client instance and thus the underlying connection pool.
   * Synchronization is required to ensure concurrent init of various store instances share same ref instance
   */
  private def getOrCreateReference(config: CosmosDBConfig) = synchronized {
    if (clientRef == null || clientRef.isClosed) {
      clientRef = createReference(config)
    }
    clientRef.reference()
  }

  private def createReference(config: CosmosDBConfig) =
    new ReferenceCounted[ClientHolder](ClientHolder(createClient(config)))

}
