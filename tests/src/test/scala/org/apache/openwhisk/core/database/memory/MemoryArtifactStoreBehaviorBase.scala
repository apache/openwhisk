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

package org.apache.openwhisk.core.database.memory

import org.scalatest.FlatSpec
import org.apache.openwhisk.core.database.{ArtifactStore, AttachmentStore, DocumentSerializer}
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreBehaviorBase
import org.apache.openwhisk.core.entity.{
  DocumentReader,
  WhiskActivation,
  WhiskAuth,
  WhiskDocumentReader,
  WhiskEntity,
  WhiskEntityJsonFormat
}

import scala.reflect.{classTag, ClassTag}

trait MemoryArtifactStoreBehaviorBase extends FlatSpec with ArtifactStoreBehaviorBase {
  override def storeType = "Memory"

  override lazy val authStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MemoryArtifactStoreProvider.makeArtifactStore[WhiskAuth](getAttachmentStore[WhiskAuth]())
  }

  override protected def beforeAll(): Unit = {
    MemoryArtifactStoreProvider.purgeAll()
    super.beforeAll()
  }

  override lazy val entityStore =
    MemoryArtifactStoreProvider.makeArtifactStore[WhiskEntity](getAttachmentStore[WhiskEntity]())(
      classTag[WhiskEntity],
      WhiskEntityJsonFormat,
      WhiskDocumentReader,
      actorSystem,
      logging,
      materializer)

  override lazy val activationStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MemoryArtifactStoreProvider.makeArtifactStore[WhiskActivation](getAttachmentStore[WhiskActivation]())
  }

  override protected def getAttachmentStore(store: ArtifactStore[_]) =
    Some(store.asInstanceOf[MemoryArtifactStore[_]].attachmentStore)

  protected def getAttachmentStore[D <: DocumentSerializer: ClassTag](): AttachmentStore =
    MemoryAttachmentStoreProvider.makeStore()
}
