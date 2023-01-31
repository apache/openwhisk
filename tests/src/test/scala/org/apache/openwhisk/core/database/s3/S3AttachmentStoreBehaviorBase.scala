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

package org.apache.openwhisk.core.database.s3

import akka.actor.ActorSystem
import org.scalatest.FlatSpec
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.database.{AttachmentStore, DocumentSerializer}
import org.apache.openwhisk.core.database.memory.{MemoryArtifactStoreBehaviorBase, MemoryArtifactStoreProvider}
import org.apache.openwhisk.core.database.test.AttachmentStoreBehaviors
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreAttachmentBehaviors
import org.apache.openwhisk.core.entity.WhiskEntity

import scala.reflect.ClassTag
import scala.util.Random

trait S3AttachmentStoreBehaviorBase
    extends FlatSpec
    with MemoryArtifactStoreBehaviorBase
    with ArtifactStoreAttachmentBehaviors
    with AttachmentStoreBehaviors {
  override lazy val store = makeS3Store[WhiskEntity]

  override val prefix = s"attachmentTCK_${Random.alphanumeric.take(4).mkString}"

  override protected def beforeAll(): Unit = {
    MemoryArtifactStoreProvider.purgeAll()
    super.beforeAll()
  }

  override def getAttachmentStore[D <: DocumentSerializer: ClassTag](): AttachmentStore =
    makeS3Store[D]()

  def makeS3Store[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                       logging: Logging): AttachmentStore
}
