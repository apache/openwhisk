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

import java.io.Closeable

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.microsoft.azure.cosmosdb.rx.AsyncDocumentClient
import com.typesafe.config.ConfigFactory
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{DocumentReader, WhiskActivation, WhiskAuth, WhiskEntity}
import pureconfig._
import pureconfig.generic.auto._
import spray.json.RootJsonFormat

import scala.reflect.ClassTag

case class ClientHolder(client: AsyncDocumentClient) extends Closeable {
  override def close(): Unit = client.close()
}

object CosmosDBArtifactStoreProvider extends ArtifactStoreProvider {
  type DocumentClientRef = ReferenceCounted[ClientHolder]#CountedReference
  private val clients = collection.mutable.Map[CosmosDBConfig, ReferenceCounted[ClientHolder]]()

  RetryMetricsCollector.registerIfEnabled()

  override def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {
    val tag = implicitly[ClassTag[D]]
    val config = CosmosDBConfig(ConfigFactory.load(), tag.runtimeClass.getSimpleName)
    makeStoreForClient(config, getOrCreateReference(config), getAttachmentStore())
  }

  def makeArtifactStore[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig,
                                                           attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): CosmosDBArtifactStore[D] = {

    makeStoreForClient(config, createReference(config).reference(), attachmentStore)
  }

  private def makeStoreForClient[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig,
                                                                    clientRef: DocumentClientRef,
                                                                    attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): CosmosDBArtifactStore[D] = {

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
  private def getOrCreateReference[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig) = synchronized {
    val clientRef = clients.getOrElseUpdate(config, createReference(config))
    if (clientRef.isClosed) {
      val newRef = createReference(config)
      clients.put(config, newRef)
      newRef.reference()
    } else {
      clientRef.reference()
    }
  }

  private def createReference[D <: DocumentSerializer: ClassTag](config: CosmosDBConfig) = {
    val clazz = implicitly[ClassTag[D]].runtimeClass
    if (clazz != classOf[WhiskActivation]) {
      require(config.timeToLive.isEmpty, s"'timeToLive' should not  be specified for ${clazz.getSimpleName}")
    }
    new ReferenceCounted[ClientHolder](ClientHolder(config.createClient()))
  }

}
