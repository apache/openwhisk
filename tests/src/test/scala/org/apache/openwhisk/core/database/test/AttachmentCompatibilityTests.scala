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
import java.util.Base64

import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import pureconfig._
import pureconfig.generic.auto._
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.controller.test.WhiskAuthHelpers
import org.apache.openwhisk.core.database.memory.MemoryAttachmentStoreProvider
import org.apache.openwhisk.core.database.{CouchDbConfig, CouchDbRestClient, CouchDbStoreProvider, NoDocumentException}
import org.apache.openwhisk.core.entity.Attachments.Inline
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.entity._

import scala.concurrent.Future
import scala.reflect.classTag

@RunWith(classOf[JUnitRunner])
class AttachmentCompatibilityTests
    extends FlatSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with WskActorSystem
    with ExecHelpers
    with DbUtils
    with DefaultJsonProtocol
    with StreamLogging {

  //Bring in sync the timeout used by ScalaFutures and DBUtils
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)

  val creds = WhiskAuthHelpers.newIdentity()
  val namespace = EntityPath(creds.subject.asString)
  def aname() = MakeName.next("action_tests")
  val config = loadConfigOrThrow[CouchDbConfig](ConfigKeys.couchdb)
  val entityStore = WhiskEntityStore.datastore()
  val client =
    new CouchDbRestClient(
      config.protocol,
      config.host,
      config.port,
      config.username,
      config.password,
      config.databaseFor[WhiskEntity])

  override def afterEach(): Unit = {
    cleanup()
  }

  override protected def withFixture(test: NoArgTest) = {
    assume(isCouchStore(entityStore))
    super.withFixture(test)
  }

  behavior of "Attachments"

  it should "read attachments created using old scheme" in {
    implicit val tid: TransactionId = transid()
    val namespace = EntityPath("attachment-compat-test1")
    val exec = javaDefault("ZHViZWU=", Some("hello"))
    val doc =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    createAction(doc)

    val doc2 = WhiskAction.get(entityStore, doc.docid).futureValue
    doc2.exec shouldBe exec
  }

  it should "read attachments created using old scheme with AttachmentStore" in {
    implicit val tid: TransactionId = transid()
    val namespace = EntityPath("attachment-compat-test2")
    val exec = javaDefault("ZHViZWU=", Some("hello"))
    val doc =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    createAction(doc)

    val entityStore2 = createEntityStore()
    val doc2 = WhiskAction.get(entityStore2, doc.docid).futureValue
    doc2.exec shouldBe exec
  }

  it should "read existing base64 encoded code string" in {
    implicit val tid: TransactionId = transid()
    val exec = """{
               |  "kind": "nodejs:14",
               |  "code": "SGVsbG8gT3BlbldoaXNr"
               |}""".stripMargin.parseJson.asJsObject
    val (id, action) = makeActionJson(namespace, aname(), exec)
    val info = putDoc(id, action)

    val action2 = WhiskAction.get(entityStore, info.id).futureValue
    codeExec(action2).codeAsJson shouldBe JsString("SGVsbG8gT3BlbldoaXNr")
  }

  it should "read existing simple code string" in {
    implicit val tid: TransactionId = transid()
    val exec = """{
                 |  "kind": "nodejs:14",
                 |  "code": "while (true)"
                 |}""".stripMargin.parseJson.asJsObject
    val (id, action) = makeActionJson(namespace, aname(), exec)
    val info = putDoc(id, action)

    val action2 = WhiskAction.get(entityStore, info.id).futureValue
    codeExec(action2).codeAsJson shouldBe JsString("while (true)")
  }

  it should "read existing simple code string for blackbox action" in {
    implicit val tid: TransactionId = transid()
    val exec = """{
                 |  "kind": "blackbox",
                 |  "image": "docker-custom.com/openwhisk-runtime/magic/nodejs:0.0.1",
                 |  "code":  "while (true)",
                 |  "binary": false
                 |}""".stripMargin.parseJson.asJsObject
    val (id, action) = makeActionJson(namespace, aname(), exec)
    val info = putDoc(id, action)

    val action2 = WhiskAction.get(entityStore, info.id).futureValue
    codeExec(action2).codeAsJson shouldBe JsString("while (true)")
  }

  private def codeExec(a: WhiskAction) = a.exec.asInstanceOf[CodeExec[_]]

  private def makeActionJson(namespace: EntityPath, name: EntityName, exec: JsObject): (String, JsObject) = {
    val id = namespace.addPath(name).asString
    val base = s"""{
                 |  "name": "${name.asString}",
                 |  "_id": "$id",
                 |  "publish": false,
                 |  "annotations": [],
                 |  "version": "0.0.1",
                 |  "updated": 1533623651650,
                 |  "entityType": "action",
                 |  "parameters": [
                 |    {
                 |      "key": "x",
                 |      "value": "b"
                 |    }
                 |  ],
                 |  "limits": {
                 |    "timeout": 60000,
                 |    "memory": 256,
                 |    "logs": 10
                 |  },
                 |  "namespace": "${namespace.asString}"
                 |}""".stripMargin.parseJson.asJsObject
    (id, JsObject(base.fields + ("exec" -> exec)))
  }

  private def putDoc(id: String, js: JsObject): DocInfo = {
    val r = client.putDoc(id, js).futureValue
    r match {
      case Right(response) =>
        val info = response.convertTo[DocInfo]
        docsToDelete += ((entityStore, info))
        info
      case _ => fail()
    }
  }

  private def createAction(doc: WhiskAction) = {
    implicit val tid: TransactionId = transid()
    doc.exec match {
      case exec @ CodeExecAsAttachment(_, Inline(code), _, _) =>
        val attached = exec.manifest.attached.get

        val newDoc = doc.copy(exec = exec.copy(code = attached))
        newDoc.revision(doc.rev)

        val codeBytes = Base64.getDecoder().decode(code)
        val stream = new ByteArrayInputStream(codeBytes)
        val src = StreamConverters.fromInputStream(() => stream)
        val info = entityStore.put(newDoc).futureValue
        val info2 = attach(info, attached.attachmentName, attached.attachmentType, src).futureValue
        docsToDelete += ((entityStore, info2))
      case _ =>
        fail("Exec must be code attachment")
    }
  }

  object MakeName {
    @volatile var counter = 1
    def next(prefix: String = "test")(): EntityName = {
      counter = counter + 1
      EntityName(s"${prefix}_name$counter")
    }
  }

  private def attach(doc: DocInfo,
                     name: String,
                     contentType: ContentType,
                     docStream: Source[ByteString, _]): Future[DocInfo] = {
    client.putAttachment(doc.id.id, doc.rev.rev, name, contentType, docStream).map {
      case Right(response) =>
        val id = response.fields("id").convertTo[String]
        val rev = response.fields("rev").convertTo[String]
        DocInfo ! (id, rev)

      case Left(StatusCodes.NotFound) =>
        throw NoDocumentException("Not found on 'readAttachment'.")

      case Left(code) =>
        throw new Exception("Unexpected http response code: " + code)
    }
  }

  private def createEntityStore() =
    CouchDbStoreProvider
      .makeArtifactStore[WhiskEntity](
        useBatching = false,
        Some(MemoryAttachmentStoreProvider.makeStore[WhiskEntity]()))(
        classTag[WhiskEntity],
        WhiskEntityJsonFormat,
        WhiskDocumentReader,
        actorSystem,
        logging)

}
