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
 *
 */

package whisk.core.database.test

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.ContentTypes
import akka.stream.scaladsl.{Sink, Source, StreamConverters}
import akka.util.{ByteString, ByteStringBuilder}
import common.StreamLogging
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}
import whisk.core.database.{AttachmentStore, NoDocumentException}
import whisk.core.entity.DocInfo

import scala.util.Random

trait AttachmentStoreBehaviors extends ScalaFutures with DbUtils with Matchers with StreamLogging {
  this: FlatSpec =>

  def store: AttachmentStore

  behavior of "AttachmentStore"

  it should "add and read attachment" in {
    implicit val tid = transid()
    val bytes = randomBytes(4000)

    val info = DocInfo ! (newDocId, "1")
    val writeResult = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes))

    writeResult.futureValue shouldBe info

    val readResult = store.readAttachment(info, "code", byteStringSink)

    readResult.futureValue._1 shouldBe ContentTypes.`application/octet-stream`
    readResult.futureValue._2.result() shouldBe ByteString(bytes)
  }

  it should "add and then update attachment" in {
    implicit val tid = transid()
    val bytes = randomBytes(4000)

    val info = DocInfo ! (newDocId, "1")
    val writeResult = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes))

    writeResult.futureValue shouldBe info

    val updatedBytes = randomBytes(7000)
    val writeResult2 = store.attach(info, "code", ContentTypes.`application/json`, chunkedSource(updatedBytes))

    writeResult2.futureValue shouldBe info

    val readResult = store.readAttachment(info, "code", byteStringSink)

    readResult.futureValue._1 shouldBe ContentTypes.`application/json`
    readResult.futureValue._2.result() shouldBe ByteString(updatedBytes)
  }

  it should "add and delete attachment" in {
    implicit val tid = transid()
    val bytes = randomBytes(4000)

    val info = DocInfo ! (newDocId, "1")
    val wr1 = store.attach(info, "code", ContentTypes.`application/octet-stream`, chunkedSource(bytes))
    val wr2 = store.attach(info, "code2", ContentTypes.`application/json`, chunkedSource(bytes))

    val info2 = DocInfo ! (newDocId, "1")
    val wr3 = store.attach(info2, "code2", ContentTypes.`application/json`, chunkedSource(bytes))

    wr1.futureValue shouldBe info
    wr2.futureValue shouldBe info
    wr3.futureValue shouldBe info2

    def getAttachmentType(info: DocInfo, name: String) = {
      store.readAttachment(info, name, byteStringSink)
    }

    getAttachmentType(info, "code").futureValue._1 shouldBe ContentTypes.`application/octet-stream`
    getAttachmentType(info, "code2").futureValue._1 shouldBe ContentTypes.`application/json`

    val deleteResult = store.deleteAttachments(info)

    deleteResult.futureValue shouldBe true

    getAttachmentType(info, "code").failed.futureValue shouldBe a[NoDocumentException]
    getAttachmentType(info, "code2").failed.futureValue shouldBe a[NoDocumentException]

    //Delete should not have deleted other attachments
    getAttachmentType(info2, "code2").futureValue._1 shouldBe ContentTypes.`application/json`
  }

  it should "throw NoDocumentException on reading non existing attachment" in {
    implicit val tid = transid()

    val info = DocInfo ! ("nonExistingAction", "1")
    val f = store.readAttachment(info, "code", byteStringSink)

    f.failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "not write an attachment when there is error in Source" is pending
  it should "throw exception when doc is null" is pending

  private val prefix = Random.alphanumeric.take(10).mkString
  @volatile var counter = 0

  protected def newDocId: String = {
    counter = counter + 1
    s"${prefix}_$counter"
  }

  private def randomBytes(size: Int): Array[Byte] = {
    val arr = new Array[Byte](size)
    Random.nextBytes(arr)
    arr
  }

  private def chunkedSource(bytes: Array[Byte]): Source[ByteString, _] = {
    StreamConverters.fromInputStream(() => new ByteArrayInputStream(bytes), 42)
  }

  private def byteStringSink = {
    Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)
  }
}
