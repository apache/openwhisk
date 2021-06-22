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

package org.apache.openwhisk.core.database.test

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.ContentTypes
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.{ByteString, ByteStringBuilder}
import common.{StreamLogging, WskActorSystem}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{AttachmentStore, NoDocumentException}
import org.apache.openwhisk.core.entity.DocId

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Random

trait AttachmentStoreBehaviors
    extends ScalaFutures
    with DbUtils
    with Matchers
    with StreamLogging
    with WskActorSystem
    with BeforeAndAfterAll {
  this: FlatSpec =>

  //Bring in sync the timeout used by ScalaFutures and DBUtils
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)

  protected val prefix = s"attachmentTCK_${Random.alphanumeric.take(4).mkString}"

  private val attachmentsToDelete = ListBuffer[String]()

  def store: AttachmentStore

  def storeType: String

  def garbageCollectAttachments: Boolean = true

  /**
   * In some cases like when CloudFront CDN is used then deletes are not immediately reflected in reads
   * as the objects are still present in cache. For such cases we would relax some of the test assertions
   */
  protected def lazyDeletes: Boolean = false

  behavior of s"$storeType AttachmentStore"

  it should "add and read attachment" in {
    implicit val tid: TransactionId = transid()
    val bytes = randomBytes(16023)

    val docId = newDocId()
    val result = store.attach(docId, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes)).futureValue

    result.length shouldBe 16023

    val byteBuilder = store.readAttachment(docId, "code", byteStringSink()).futureValue

    byteBuilder.result() shouldBe ByteString(bytes)
    garbageCollect(docId)
  }

  it should "add and delete attachments" in {
    implicit val tid: TransactionId = transid()
    val b1 = randomBytes(1000)
    val b2 = randomBytes(2000)
    val b3 = randomBytes(3000)

    val docId = newDocId()
    //create another doc with similar name to verify it is unaffected by deletes of the first docs attachments
    val docId2 = DocId(docId.id + "2")
    val r1 = store.attach(docId, "c1", ContentTypes.`application/octet-stream`, chunkedSource(b1)).futureValue
    val r2 = store.attach(docId, "c2", ContentTypes.`application/json`, chunkedSource(b2)).futureValue
    val r3 = store.attach(docId, "c3", ContentTypes.`application/json`, chunkedSource(b3)).futureValue
    //create attachments for the other doc
    val r21 = store.attach(docId2, "c21", ContentTypes.`application/octet-stream`, chunkedSource(b1)).futureValue
    val r22 = store.attach(docId2, "c22", ContentTypes.`application/json`, chunkedSource(b2)).futureValue

    r1.length shouldBe 1000
    r2.length shouldBe 2000
    r3.length shouldBe 3000
    r21.length shouldBe 1000
    r22.length shouldBe 2000

    println(s"creating doc ${docId}")
    attachmentBytes(docId, "c1").futureValue.result() shouldBe ByteString(b1)
    attachmentBytes(docId, "c2").futureValue.result() shouldBe ByteString(b2)
    attachmentBytes(docId, "c3").futureValue.result() shouldBe ByteString(b3)
    attachmentBytes(docId2, "c21").futureValue.result() shouldBe ByteString(b1)
    attachmentBytes(docId2, "c22").futureValue.result() shouldBe ByteString(b2)

    //Delete single attachment
    store.deleteAttachment(docId, "c1").futureValue shouldBe true

    //Non deleted attachments related to same docId must still be accessible
    println(s"read missing doc ${docId}")
    if (!lazyDeletes) attachmentBytes(docId, "c1").failed.futureValue shouldBe a[NoDocumentException]
    attachmentBytes(docId, "c2").futureValue.result() shouldBe ByteString(b2)
    attachmentBytes(docId, "c3").futureValue.result() shouldBe ByteString(b3)

    //Delete all attachments
    store.deleteAttachments(docId).futureValue shouldBe true

    if (!lazyDeletes) attachmentBytes(docId, "c2").failed.futureValue shouldBe a[NoDocumentException]
    if (!lazyDeletes) attachmentBytes(docId, "c3").failed.futureValue shouldBe a[NoDocumentException]

    //Make sure doc2 attachments are left untouched
    if (!lazyDeletes) attachmentBytes(docId2, "c21").futureValue.result() shouldBe ByteString(b1)
    if (!lazyDeletes) attachmentBytes(docId2, "c22").futureValue.result() shouldBe ByteString(b2)

    attachmentsToDelete += docId2.asString
  }

  it should "throw NoDocumentException on reading non existing attachment" in {
    implicit val tid: TransactionId = transid()

    val docId = DocId("no-existing-id")
    val f = store.readAttachment(docId, "code", byteStringSink())

    f.failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "not write an attachment when there is error in Source" in {
    implicit val tid: TransactionId = transid()

    val docId = newDocId()
    val error = new Error("boom!")
    val faultySource = Source(1 to 10)
      .map { n =>
        if (n == 7) throw error
        n
      }
      .map(ByteString(_))
    val writeResult = store.attach(docId, "code", ContentTypes.`application/octet-stream`, faultySource)
    writeResult.failed.futureValue.getCause should be theSameInstanceAs error

    val readResult = store.readAttachment(docId, "code", byteStringSink())
    readResult.failed.futureValue shouldBe a[NoDocumentException]
  }

  override def afterAll(): Unit = {
    if (garbageCollectAttachments) {
      implicit val tid: TransactionId = transid()
      val f =
        Source(attachmentsToDelete.toList)
          .mapAsync(2)(id => store.deleteAttachments(DocId(id)))
          .runWith(Sink.ignore)
      Await.result(f, 1.minute)
    }
    super.afterAll()
  }

  protected def garbageCollect(docId: DocId): Unit = {}

  protected def newDocId(): DocId = {
    //By default create an info with dummy revision
    //as apart from CouchDB other stores do not support the revision property
    //for blobs
    counter = counter + 1
    val docId = s"${prefix}_$counter"
    attachmentsToDelete += docId
    DocId(docId)
  }

  @volatile var counter = 0

  private def attachmentBytes(id: DocId, name: String) = {
    implicit val tid: TransactionId = transid()
    store.readAttachment(id, name, byteStringSink())
  }

  private def chunkedSource(bytes: Array[Byte]): Source[ByteString, _] = {
    StreamConverters.fromInputStream(() => new ByteArrayInputStream(bytes), 42)
  }

  private def byteStringSink() = {
    Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)
  }
}
