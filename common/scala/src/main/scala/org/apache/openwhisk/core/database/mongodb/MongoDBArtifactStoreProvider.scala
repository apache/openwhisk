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

package org.apache.openwhisk.core.database.mongodb

import akka.actor.ActorSystem
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.entity.{DocumentReader, WhiskActivation, WhiskAuth, WhiskEntity}
import org.mongodb.scala.MongoClient
import pureconfig._
import pureconfig.generic.auto._
import spray.json.RootJsonFormat

import scala.reflect.ClassTag

case class MongoDBConfig(uri: String, database: String) {
  assume(Set(database, uri).forall(_.trim.nonEmpty), "At least one expected property is missing")

  def collectionFor[D](implicit tag: ClassTag[D]) = tag.runtimeClass.getSimpleName.toLowerCase
}

object MongoDBClient {
  private var _client: Option[MongoClient] = None

  def client(config: MongoDBConfig): MongoClient = {
    _client.getOrElse {
      val client = MongoClient(config.uri)
      _client = Some(client)
      client
    }
  }
}

object MongoDBArtifactStoreProvider extends ArtifactStoreProvider {

  def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean)(implicit jsonFormat: RootJsonFormat[D],
                                                                         docReader: DocumentReader,
                                                                         actorSystem: ActorSystem,
                                                                         logging: Logging): ArtifactStore[D] = {
    val dbConfig = loadConfigOrThrow[MongoDBConfig](ConfigKeys.mongodb)
    makeArtifactStore(dbConfig, getAttachmentStore())
  }

  def makeArtifactStore[D <: DocumentSerializer: ClassTag](dbConfig: MongoDBConfig,
                                                           attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging): ArtifactStore[D] = {

    val inliningConfig = loadConfigOrThrow[InliningConfig](ConfigKeys.db)

    val (handler, mapper) = handlerAndMapper(implicitly[ClassTag[D]])

    new MongoDBArtifactStore[D](
      MongoDBClient.client(dbConfig),
      dbConfig.database,
      dbConfig.collectionFor[D],
      handler,
      mapper,
      inliningConfig,
      attachmentStore)
  }

  private def handlerAndMapper[D](entityType: ClassTag[D])(implicit actorSystem: ActorSystem,
                                                           logging: Logging): (DocumentHandler, MongoDBViewMapper) = {
    entityType.runtimeClass match {
      case x if x == classOf[WhiskEntity] =>
        (WhisksHandler, WhisksViewMapper)
      case x if x == classOf[WhiskActivation] =>
        (ActivationHandler, ActivationViewMapper)
      case x if x == classOf[WhiskAuth] =>
        (SubjectHandler, SubjectViewMapper)
    }
  }
}
