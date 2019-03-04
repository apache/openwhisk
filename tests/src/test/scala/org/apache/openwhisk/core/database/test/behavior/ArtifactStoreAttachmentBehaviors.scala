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

package org.apache.openwhisk.core.database.test.behavior

import java.io.ByteArrayOutputStream
import java.util.Base64

import akka.http.scaladsl.model.{ContentTypes, Uri}
import akka.stream.IOResult
import akka.stream.scaladsl.{Sink, StreamConverters}
import akka.util.{ByteString, ByteStringBuilder}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{AttachmentSupport, CacheChangeNotification, NoDocumentException}
import org.apache.openwhisk.core.entity.Attachments.{Attached, Attachment, Inline}
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.entity.{CodeExec, DocInfo, EntityName, WhiskAction}

import scala.concurrent.duration.DurationInt

trait ArtifactStoreAttachmentBehaviors extends ArtifactStoreBehaviorBase with ExecHelpers {
  behavior of s"${storeType}ArtifactStore attachments"

  private val namespace = newNS()
  private val attachmentHandler = Some(WhiskAction.attachmentHandler _)
  private implicit val cacheUpdateNotifier: Option[CacheChangeNotification] = None

  it should "generate different attachment name on update" in {
    implicit val tid: TransactionId = transid()
    val exec = javaDefault(nonInlinedCode(entityStore), Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue
    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue

    //Change attachment to inline one otherwise WhiskAction would not go for putAndAttach
    val action2Updated = action2.copy(exec = exec).revision[WhiskAction](i1.rev)
    val i2 = WhiskAction.put(entityStore, action2Updated, old = Some(action2)).futureValue
    val action3 = entityStore.get[WhiskAction](i2, attachmentHandler).futureValue

    docsToDelete += ((entityStore, i2))

    attached(action2).attachmentName should not be attached(action3).attachmentName

    //Check that attachment name is actually a uri
    val attachmentUri = Uri(attached(action2).attachmentName)
    attachmentUri.isAbsolute shouldBe true
  }

  /**
   * This test asserts that old attachments are deleted and cannot be read again
   */
  it should "fail on reading with old non inlined attachment" in {
    implicit val tid: TransactionId = transid()
    val code1 = nonInlinedCode(entityStore)
    val exec = javaDefault(code1, Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_update_2"), exec)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue

    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue
    val code2 = nonInlinedCode(entityStore)
    val exec2 = javaDefault(code2, Some("hello"))
    val action2Updated = action2.copy(exec = exec2).revision[WhiskAction](i1.rev)

    val i2 = WhiskAction.put(entityStore, action2Updated, old = Some(action2)).futureValue
    val action3 = entityStore.get[WhiskAction](i2, attachmentHandler).futureValue

    docsToDelete += ((entityStore, i2))
    getAttachmentBytes(i2, attached(action3)).futureValue.result() shouldBe decode(code2)
    getAttachmentBytes(i1, attached(action2)).failed.futureValue shouldBe a[NoDocumentException]
  }

  /**
   * Variant of previous test where read with old attachment should still work
   * if attachment is inlined
   */
  it should "work on reading with old inlined attachment" in {
    assumeAttachmentInliningEnabled(entityStore)
    implicit val tid: TransactionId = transid()
    val code1 = encodedRandomBytes(inlinedAttachmentSize(entityStore))
    val exec = javaDefault(code1, Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_update_2"), exec)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue

    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue
    val code2 = nonInlinedCode(entityStore)
    val exec2 = javaDefault(code2, Some("hello"))
    val action2Updated = action2.copy(exec = exec2).revision[WhiskAction](i1.rev)

    val i2 = WhiskAction.put(entityStore, action2Updated, old = Some(action2)).futureValue
    val action3 = entityStore.get[WhiskAction](i2, attachmentHandler).futureValue

    docsToDelete += ((entityStore, i2))
    getAttachmentBytes(i2, attached(action3)).futureValue.result() shouldBe decode(code2)
    getAttachmentBytes(i2, attached(action2)).futureValue.result() shouldBe decode(code1)
  }

  it should "put and read large attachment" in {
    implicit val tid: TransactionId = transid()
    val size = Math.max(nonInlinedAttachmentSize(entityStore), getAttachmentSizeForTest(entityStore))
    val base64 = encodedRandomBytes(size)

    val exec = javaDefault(base64, Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_large"), exec)

    //Have more patience as reading large attachments take time specially for remote
    //storage like Cosmos
    implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 1.minute)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue
    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue

    val action3 = WhiskAction.get(entityStore, i1.id, i1.rev).futureValue

    docsToDelete += ((entityStore, i1))

    attached(action2).attachmentType shouldBe ContentTypes.`application/octet-stream`
    attached(action2).length shouldBe Some(size)
    attached(action2).digest should not be empty

    action3.exec shouldBe exec
    inlined(action3).value shouldBe base64
  }

  it should "inline small attachments" in {
    assumeAttachmentInliningEnabled(entityStore)
    implicit val tid: TransactionId = transid()
    val attachmentSize = inlinedAttachmentSize(entityStore) - 1
    val base64 = encodedRandomBytes(attachmentSize)

    val exec = javaDefault(base64, Some("hello"))
    val javaAction = WhiskAction(namespace, EntityName("attachment_inline"), exec)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue
    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue
    val action3 = WhiskAction.get(entityStore, i1.id, i1.rev).futureValue

    docsToDelete += ((entityStore, i1))

    action3.exec shouldBe exec
    inlined(action3).value shouldBe base64

    val a = attached(action2)

    val attachmentUri = Uri(a.attachmentName)
    attachmentUri.scheme shouldBe AttachmentSupport.MemScheme
    a.length shouldBe Some(attachmentSize)
    a.digest should not be empty
  }

  it should "throw NoDocumentException for non existing attachment" in {
    implicit val tid: TransactionId = transid()
    val attachmentName = "foo"
    val attachmentId =
      getAttachmentStore(entityStore).map(s => s"${s.scheme}:$attachmentName").getOrElse(attachmentName)

    val sink = StreamConverters.fromOutputStream(() => new ByteArrayOutputStream())
    entityStore
      .readAttachment[IOResult](
        DocInfo ! ("non-existing-doc", "42"),
        Attached(attachmentId, ContentTypes.`application/octet-stream`),
        sink)
      .failed
      .futureValue shouldBe a[NoDocumentException]
  }

  it should "delete attachment on document delete" in {
    val attachmentStore = getAttachmentStore(entityStore)
    assume(attachmentStore.isDefined, "ArtifactStore does not have attachmentStore configured")

    implicit val tid: TransactionId = transid()
    val size = nonInlinedAttachmentSize(entityStore)
    val base64 = encodedRandomBytes(size)

    val exec = javaDefault(base64, Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    val i1 = WhiskAction.put(entityStore, javaAction, old = None).futureValue
    val action2 = entityStore.get[WhiskAction](i1, attachmentHandler).futureValue

    WhiskAction.del(entityStore, i1).futureValue shouldBe true

    val attachmentName = Uri(attached(action2).attachmentName).path.toString
    attachmentStore.get
      .readAttachment(i1.id, attachmentName, byteStringSink())
      .failed
      .futureValue shouldBe a[NoDocumentException]
  }

  private def attached(a: WhiskAction): Attached =
    a.exec.asInstanceOf[CodeExec[Attachment[Nothing]]].code.asInstanceOf[Attached]

  private def inlined(a: WhiskAction): Inline[String] =
    a.exec.asInstanceOf[CodeExec[Attachment[String]]].code.asInstanceOf[Inline[String]]

  private def getAttachmentBytes(docInfo: DocInfo, attached: Attached) = {
    implicit val tid: TransactionId = transid()
    entityStore.readAttachment(docInfo, attached, byteStringSink())
  }

  private def byteStringSink() = {
    Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)
  }

  private def decode(s: String): ByteString = ByteString(Base64.getDecoder.decode(s))
}
