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

package whisk.core.database.test

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.ContentTypes
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.{ByteString, ByteStringBuilder}
import common.{StreamLogging, WskActorSystem}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import whisk.common.TransactionId
import whisk.core.database.{AttachmentStore, NoDocumentException}
import whisk.core.entity.DocInfo

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
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

  protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  protected val prefix = s"attachmentTCK_${Random.alphanumeric.take(4).mkString}"

  private val attachmentsToDelete = ListBuffer[String]()

  def store: AttachmentStore

  def storeType: String

  def garbageCollectAttachments: Boolean = true

  behavior of s"$storeType AttachmentStore"

  it should "add and read attachment" in {
    implicit val tid: TransactionId = transid()
    val bytes = randomBytes(4000)

    val info = newDocInfo()
    val info_v2 = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes)).futureValue

    info_v2.id shouldBe info.id

    val byteBuilder = store.readAttachment(info_v2, "code", byteStringSink()).futureValue

    byteBuilder.result() shouldBe ByteString(bytes)
    garbageCollect(info_v2)
  }

  it should "add and then update attachment" in {
    implicit val tid: TransactionId = transid()
    val bytes = randomBytes(4000)

    val info = newDocInfo()
    val info_v2 = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes)).futureValue

    info_v2.id shouldBe info.id

    val updatedBytes = randomBytes(7000)
    val info_v3 =
      store.attach(info_v2, "code", ContentTypes.`application/json`, chunkedSource(updatedBytes)).futureValue

    info_v3.id shouldBe info.id

    val byteBuilder = store.readAttachment(info_v3, "code", byteStringSink()).futureValue

    byteBuilder.result() shouldBe ByteString(updatedBytes)

    garbageCollect(info_v3)
  }

  it should "add and delete attachment" in {
    implicit val tid: TransactionId = transid()
    val bytes = randomBytes(4000)

    val info = newDocInfo()
    val info_v2 = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes)).futureValue
    val info_v3 = store.attach(info_v2, "code2", ContentTypes.`application/json`, chunkedSource(bytes)).futureValue

    val info2 = newDocInfo()
    val info2_v2 = store.attach(info2, "code2", ContentTypes.`application/json`, chunkedSource(bytes)).futureValue

    info_v2.id shouldBe info.id
    info_v3.id shouldBe info.id
    info2_v2.id shouldBe info2.id

    def getAttachmentBytes(info: DocInfo, name: String) = {
      store.readAttachment(info, name, byteStringSink())
    }

    getAttachmentBytes(info_v3, "code").futureValue.result() shouldBe ByteString(bytes)
    getAttachmentBytes(info_v3, "code2").futureValue.result() shouldBe ByteString(bytes)

    val deleteResult = deleteAttachment(info)

    deleteResult.futureValue shouldBe true

    getAttachmentBytes(info_v3, "code").failed.futureValue shouldBe a[NoDocumentException]
    getAttachmentBytes(info_v3, "code2").failed.futureValue shouldBe a[NoDocumentException]

    //Delete should not have deleted other attachments
    getAttachmentBytes(info2_v2, "code2").futureValue.result() shouldBe ByteString(bytes)
    garbageCollect(info2_v2)
  }

  it should "throw NoDocumentException on reading non existing attachment" in {
    implicit val tid: TransactionId = transid()

    val info = DocInfo ! ("nonExistingAction", "1")
    val f = store.readAttachment(info, "code", byteStringSink())

    f.failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "not write an attachment when there is error in Source" in {
    implicit val tid: TransactionId = transid()

    val info = newDocInfo()
    val error = new Error("boom!")
    val faultySource = Source(1 to 10)
      .map { n ⇒
        if (n == 7) throw error
        n
      }
      .map(ByteString(_))
    val writeResult = store.attach(info, "code", ContentTypes.`application/octet-stream`, faultySource)
    writeResult.failed.futureValue.getCause should be theSameInstanceAs error

    val readResult = store.readAttachment(info, "code", byteStringSink())
    readResult.failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "throw exception when doc or doc revision is null" in {
    Seq(null, DocInfo("foo")).foreach { doc =>
      implicit val tid: TransactionId = transid()
      intercept[IllegalArgumentException] {
        store.readAttachment(doc, "bar", byteStringSink())
      }

      intercept[IllegalArgumentException] {
        store.attach(doc, "code", ContentTypes.`application/octet-stream`, chunkedSource(randomBytes(10)))
      }
    }
  }

  override def afterAll(): Unit = {
    if (garbageCollectAttachments) {
      implicit val tid: TransactionId = transid()
      val f =
        Source(attachmentsToDelete.toList).mapAsync(2)(id => deleteAttachment(DocInfo ! (id, "1"))).runWith(Sink.ignore)
      Await.result(f, 1.minute)
    }
    super.afterAll()
  }

  protected def deleteAttachment(info: DocInfo)(implicit transid: TransactionId): Future[Boolean] = {
    store.deleteAttachments(info)
  }

  protected def garbageCollect(doc: DocInfo): Unit = {}

  protected def newDocInfo(): DocInfo = {
    //By default create an info with dummy revision
    //as apart from CouchDB other stores do not support the revision property
    //for blobs
    val docId = newDocId()
    attachmentsToDelete += docId
    DocInfo ! (docId, "1")
  }

  @volatile var counter = 0

  protected def newDocId(): String = {
    counter = counter + 1
    s"${prefix}_$counter"
  }

  private def chunkedSource(bytes: Array[Byte]): Source[ByteString, _] = {
    StreamConverters.fromInputStream(() => new ByteArrayInputStream(bytes), 42)
  }

  private def byteStringSink() = {
    Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)
  }
}
