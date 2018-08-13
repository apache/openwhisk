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

package whisk.core.database

import java.io.File
import java.util.Base64

import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.{ByteString, ByteStringBuilder}
import common.TestFolder
import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalactic.Uniformity
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, OptionValues}
import spray.json.{DefaultJsonProtocol, JsObject}
import whisk.common.TransactionId
import whisk.core.cli.CommandMessages
import whisk.core.database.DbCommand._
import whisk.core.database.test.behavior.ArtifactStoreTestUtil._
import whisk.core.entity.Attachments.{Attached, Attachment, Inline}
import whisk.core.entity.test.ExecHelpers
import whisk.core.entity._

import scala.concurrent.Future
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class DbCommandTests
    extends FlatSpec
    with WhiskAdminCliTestBase
    with TestFolder
    with ArtifactNamingHelper
    with DefaultJsonProtocol
    with OptionValues
    with ExecHelpers {
  behavior of "db get"

  private implicit val cacheUpdateNotifier: Option[CacheChangeNotification] = None

  //Its possible that test db has some existing data with improper attachments
  //To avoid failing due to that we ignore those errors and instead explicitly assert
  //on attachments created in tests
  DbCommand.ignoreAttachmentErrors = true

  it should "get all artifacts" in {
    implicit val tid: TransactionId = transid()
    val actions = List.tabulate(10)(_ => newAction(newNS()))
    actions foreach (put(entityStore, _))
    val actionIds = actions.map(_.docid.id).toSet
    val actionJsons = actions.map(_.toDocumentRecord)

    val outFile = newFile()

    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)

    (collectedEntities(outFile, idFilter(actionIds)) should contain theSameElementsAs actionJsons)(
      after being strippedOfRevision)

    collectedEntities(outFile, js => idOf(js).startsWith("_design/")) shouldBe empty
  }

  it should "download attachments also" in {
    implicit val tid: TransactionId = transid()
    val simpleNS = newNS()
    val simpleActions = List.tabulate(3)(_ => newAction(simpleNS))

    //Create few java actions which use attachments
    val javaNS = newNS()
    val actionsWithAttachments = List.tabulate(7)(_ => newJavaAction(javaNS))
    val actions = simpleActions ++ actionsWithAttachments

    actions foreach { a =>
      val info = WhiskAction.put(entityStore, a, None).futureValue
      docsToDelete += ((entityStore, info))
    }
    val actionIds = actions.map(_.docid.id).toSet

    val actionJsons = Future
      .sequence(actionIds.map(id => entityStore.get[WhiskAction](DocInfo(id))).toSeq)
      .futureValue
      .map(_.toDocumentRecord)

    val outFile = newFile()
    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)

    //Compare the jsons ignoring _rev and updated and other private fields starting with '_' except '_id'
    (collectedEntities(outFile, idFilter(actionIds)) should contain theSameElementsAs actionJsons)(
      after being strippedOfUnstableProps)

    //Check if attachment content matches
    val attachmentDir = getOrCreateAttachmentDir(outFile)
    actionsWithAttachments.foreach { a =>
      val file = getAttachmentFile(attachmentDir, a)
      getAttachmentBytes(a) shouldBe FileUtils.readFileToByteArray(file)
    }
  }

  behavior of "db put"

  it should "put all entities" in {
    implicit val tid: TransactionId = transid()
    val actions = List.tabulate(10)(_ => newAction(newNS()))
    actions foreach (put(entityStore, _))

    val actionIds = actions.map(_.docid.id).toSet
    val actionJsons = actions.map(_.toDocumentRecord)

    val outFile = newFile()

    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)
    cleanup()

    val inFile = copyEntities(outFile, idFilter(actionIds))
    resultOk("db", "put", "--in", inFile.getAbsolutePath, "whisks") shouldBe CommandMessages.putDocs(10)

    cleanup[WhiskEntity](actionIds, entityStore)
  }

  it should "put action with attachments" in {
    implicit val tid: TransactionId = transid()
    val simpleNS = newNS()
    val simpleActions = List.tabulate(3)(_ => newAction(simpleNS))

    //Create few java actions which use attachments
    val javaNS = newNS()
    val actionsWithAttachments = List.tabulate(7)(_ => newJavaAction(javaNS))
    val actions = simpleActions ++ actionsWithAttachments

    actions foreach { a =>
      val info = WhiskAction.put(entityStore, a, None).futureValue
      docsToDelete += ((entityStore, info))
    }
    val actionIds = actions.map(_.docid.id).toSet

    val outFile = newFile()
    resultOk("db", "get", "--out", outFile.getAbsolutePath, "--attachments", "whisks") should include(
      outFile.getAbsolutePath)

    cleanup()

    val inFile = copyEntities(outFile, idFilter(actionIds))
    resultOk("db", "put", "--in", inFile.getAbsolutePath, "whisks") shouldBe CommandMessages.putDocs(10)

    actionsWithAttachments.foreach { a =>
      val newAction = entityStore.get[WhiskAction](DocInfo(a.docid)).futureValue
      val oldAction = actionsWithAttachments.find(_.docid == newAction.docid).get
      newAction.exec match {
        case _ @CodeExecAsAttachment(_, attached: Attached, _) =>
          val newBytes = getAttachmentBytes(newAction.docinfo, attached).futureValue.result().toArray
          val oldBytes = getAttachmentBytes(oldAction)
          newBytes shouldBe oldBytes
        case _ => fail()
      }
    }
    cleanup[WhiskEntity](actionIds, entityStore)
  }

  it should "determine entityType if missing" in {
    val action = newAction(newNS())
    getEntityType(stripType(action)).value shouldBe "action"
    withEntityType(stripType(action)) shouldBe action.toDocumentRecord

    val pkg = WhiskPackage(newNS(), aname())
    getEntityType(stripType(pkg)).value shouldBe "package"
    withEntityType(stripType(pkg)) shouldBe pkg.toDocumentRecord

    val trigger = WhiskTrigger(newNS(), aname())
    getEntityType(stripType(trigger)).value shouldBe "trigger"
    withEntityType(stripType(trigger)) shouldBe trigger.toDocumentRecord

    val rule = WhiskRule(newNS(), aname(), trigger.fullyQualifiedName(false), action.fullyQualifiedName(false))
    getEntityType(stripType(rule)).value shouldBe "rule"
    withEntityType(stripType(rule)) shouldBe rule.toDocumentRecord
  }

  it should "set entityType if missing" in {
    implicit val tid: TransactionId = transid()

    val action = newAction(newNS())
    put(entityStore, AnyEntity(stripType(action)))
    val actionJsons = List(action.toDocumentRecord)

    val outFile = newFile()

    resultOk("db", "get", "--out", outFile.getAbsolutePath, "whisks") should include(outFile.getAbsolutePath)
    cleanup()

    (collectedEntities(outFile, idFilter(Set(action.docid.id))) should contain theSameElementsAs actionJsons)(
      after being strippedOfRevision)
  }

  private def newJavaAction(ns: EntityPath): WhiskAction = {
    WhiskAction(ns, aname(), javaDefault(nonInlinedCode(entityStore), Some("hello")))
  }

  private def getAttachmentBytes(a: WhiskAction) = {
    val inline = a.exec.asInstanceOf[CodeExec[Attachment[String]]].code.asInstanceOf[Inline[String]]
    Base64.getDecoder.decode(inline.value)
  }

  private def stripType(e: WhiskEntity) = JsObject(e.toDocumentRecord.fields - "entityType")

  private def collectedEntities(file: File, filter: JsObject => Boolean) =
    DbCommand
      .createJSStream(file)
      .filter(filter)
      .runWith(Sink.seq)
      .futureValue

  private def copyEntities(file: File, filter: JsObject => Boolean): File = {
    val out = newFile()
    DbCommand
      .createJSStream(file)
      .filter(filter)
      .map(jsToStringLine)
      .runWith(FileIO.toPath(out.toPath))
      .futureValue
    out
  }

  private def cleanup[A <: WhiskDocument: Manifest](ids: TraversableOnce[String], store: ArtifactStore[A]): Unit = {
    implicit val tid = TransactionId.testing
    ids.map { u =>
      Try {
        val doc = store.get[A](DocInfo(u)).futureValue
        delete(store, doc.docinfo)
      }
    }
  }

  private def getAttachmentBytes(docInfo: DocInfo, attached: Attached) = {
    implicit val tid: TransactionId = transid()
    entityStore.readAttachment(docInfo, attached, byteStringSink())
  }

  private def byteStringSink() = {
    Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)
  }

  object strippedOfUnstableProps extends Uniformity[JsObject] {
    private def ignoredFields = Set("updated")
    override def normalizedOrSame(b: Any) = b match {
      case s: JsObject => normalized(s)
      case _           => b
    }
    override def normalizedCanHandle(b: Any) = b.isInstanceOf[JsObject]
    override def normalized(js: JsObject) = stripInternalFields(js)

    private def stripInternalFields(js: JsObject) = {
      //Strip out all field name starting with '_' which are considered as db specific internal fields
      JsObject(js.fields.filter { case (k, _) => !ignoredFields.contains(k) && (!k.startsWith("_") || k == "_id") })
    }
  }
}
