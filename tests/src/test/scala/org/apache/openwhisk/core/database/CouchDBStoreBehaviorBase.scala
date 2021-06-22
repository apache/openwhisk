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

import org.scalatest.FlatSpec
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

trait CouchDBStoreBehaviorBase extends FlatSpec with ArtifactStoreBehaviorBase {
  override def storeType = "CouchDB"

  override val authStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    CouchDbStoreProvider.makeArtifactStore[WhiskAuth](useBatching = false, getAttachmentStore[WhiskAuth]())
  }

  override val entityStore =
    CouchDbStoreProvider.makeArtifactStore[WhiskEntity](useBatching = false, getAttachmentStore[WhiskEntity]())(
      classTag[WhiskEntity],
      WhiskEntityJsonFormat,
      WhiskDocumentReader,
      actorSystem,
      logging)

  override val activationStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    CouchDbStoreProvider.makeArtifactStore[WhiskActivation](useBatching = true, getAttachmentStore[WhiskActivation]())
  }

  override protected def getAttachmentStore(store: ArtifactStore[_]) =
    store.asInstanceOf[CouchDbRestStore[_]].attachmentStore

  protected def getAttachmentStore[D <: DocumentSerializer: ClassTag](): Option[AttachmentStore] = None
}
