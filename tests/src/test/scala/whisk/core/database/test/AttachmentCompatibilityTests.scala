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
import java.util.Base64

import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import pureconfig.loadConfigOrThrow
import spray.json.DefaultJsonProtocol
import whisk.common.TransactionId
import whisk.core.ConfigKeys
import whisk.core.database.memory.MemoryAttachmentStoreProvider
import whisk.core.database.{CouchDbConfig, CouchDbRestClient, CouchDbStoreProvider, NoDocumentException}
import whisk.core.entity.Attachments.Inline
import whisk.core.entity.test.ExecHelpers
import whisk.core.entity.{
  CodeExecAsAttachment,
  DocInfo,
  EntityName,
  EntityPath,
  WhiskAction,
  WhiskDocumentReader,
  WhiskEntity,
  WhiskEntityJsonFormat,
  WhiskEntityStore
}

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
  implicit val materializer = ActorMaterializer()
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

  private def createAction(doc: WhiskAction) = {
    implicit val tid: TransactionId = transid()
    doc.exec match {
      case exec @ CodeExecAsAttachment(_, Inline(code), _) =>
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
        logging,
        materializer)

}
