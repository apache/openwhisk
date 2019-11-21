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

package org.apache.openwhisk.core.database

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json.RootJsonFormat
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.DocumentReader
import org.apache.openwhisk.core.entity.size._
import pureconfig._
import pureconfig.generic.auto._

import scala.reflect.ClassTag

case class CouchDbConfig(provider: String,
                         protocol: String,
                         host: String,
                         port: Int,
                         username: String,
                         password: String,
                         databases: Map[String, String]) {
  assume(Set(protocol, host, username, password).forall(_.nonEmpty), "At least one expected property is missing")

  def databaseFor[D](implicit tag: ClassTag[D]): String = {
    val entityType = tag.runtimeClass.getSimpleName
    databases.get(entityType) match {
      case Some(name) => name
      case None       => throw new IllegalArgumentException(s"Database name mapping not found for $entityType")
    }
  }
}

object CouchDbStoreProvider extends ArtifactStoreProvider {

  def makeStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean)(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = makeArtifactStore(useBatching, getAttachmentStore())

  def makeArtifactStore[D <: DocumentSerializer: ClassTag](useBatching: Boolean,
                                                           attachmentStore: Option[AttachmentStore])(
    implicit jsonFormat: RootJsonFormat[D],
    docReader: DocumentReader,
    actorSystem: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): ArtifactStore[D] = {
    val dbConfig = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
    require(
      dbConfig.provider == "Cloudant" || dbConfig.provider == "CouchDB",
      s"Unsupported db.provider: ${dbConfig.provider}")

    val inliningConfig = loadConfigOrThrow[InliningConfig](ConfigKeys.db)

    new CouchDbRestStore[D](
      dbConfig.protocol,
      dbConfig.host,
      dbConfig.port,
      dbConfig.username,
      dbConfig.password,
      dbConfig.databaseFor[D],
      useBatching,
      inliningConfig,
      attachmentStore)
  }
}
