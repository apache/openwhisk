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

import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreBehaviorBase
import org.apache.openwhisk.core.database.{ArtifactStore, AttachmentStore, DocumentSerializer}
import org.apache.openwhisk.core.entity._
import org.scalatest.FlatSpec
import org.testcontainers.containers.MongoDBContainer
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._

import scala.reflect.{classTag, ClassTag}

trait MongoDBStoreBehaviorBase extends FlatSpec with ArtifactStoreBehaviorBase {
  val imageName = loadConfigOrThrow[String]("whisk.mongodb.docker-image")
  val container = new MongoDBContainer(imageName)
  container.start()

  override def afterAll = {
    container.close()
    super.afterAll()
  }

  override def storeType = "MongoDB"

  val storeConfig = MongoDBConfig(container.getReplicaSetUrl, "unittest")

  override lazy val authStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MongoDBArtifactStoreProvider.makeArtifactStore[WhiskAuth](storeConfig, getAttachmentStore[WhiskAuth]())
  }

  override lazy val entityStore =
    MongoDBArtifactStoreProvider.makeArtifactStore[WhiskEntity](storeConfig, getAttachmentStore[WhiskEntity]())(
      classTag[WhiskEntity],
      WhiskEntityJsonFormat,
      WhiskDocumentReader,
      actorSystem,
      logging)

  override lazy val activationStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MongoDBArtifactStoreProvider
      .makeArtifactStore[WhiskActivation](storeConfig, getAttachmentStore[WhiskActivation]())
  }

  override protected def getAttachmentStore(store: ArtifactStore[_]) =
    store.asInstanceOf[MongoDBArtifactStore[_]].attachmentStore

  protected def getAttachmentStore[D <: DocumentSerializer: ClassTag](): Option[AttachmentStore] = None
}
