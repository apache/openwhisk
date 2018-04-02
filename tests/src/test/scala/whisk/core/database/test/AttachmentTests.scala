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

import java.util.Base64

import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec}
import whisk.common.TransactionId
import whisk.core.controller.test.WhiskAuthHelpers
import whisk.core.database.CacheChangeNotification
import whisk.core.entity.Attachments.{Attached, Attachment, Inline}
import whisk.core.entity._
import whisk.core.entity.test.ExecHelpers

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class AttachmentTests
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with WskActorSystem
    with DbUtils
    with ExecHelpers
    with ScalaFutures
    with StreamLogging {

  implicit val materializer = ActorMaterializer()
  private val namespace = EntityPath(WhiskAuthHelpers.newIdentity().subject.asString)
  private val datastore = WhiskEntityStore.datastore()
  private val attachmentHandler = Some(WhiskAction.attachmentHandler _)

  implicit val cacheUpdateNotifier: Option[CacheChangeNotification] = None
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)

  override def afterEach() = {
    cleanup()
  }

  override def afterAll() = {
    datastore.shutdown()
    super.afterAll()
  }

  behavior of "Datastore"

  it should "generate different attachment name on update" in {
    implicit val tid: TransactionId = transid()
    val exec = javaDefault("ZHViZWU=", Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    val i1 = WhiskAction.put(datastore, javaAction, old = None).futureValue
    val action2 = datastore.get[WhiskAction](i1, attachmentHandler).futureValue

    //Change attachment to inline one otherwise WhiskAction would not go for putAndAttach
    val action2Updated = action2.copy(exec = exec).revision[WhiskAction](i1.rev)
    val i2 = WhiskAction.put(datastore, action2Updated, old = Some(action2)).futureValue
    val action3 = datastore.get[WhiskAction](i2, attachmentHandler).futureValue

    docsToDelete += ((datastore, i2))

    attached(action2).attachmentName should not be attached(action3).attachmentName

    //Check that attachment name is actually a uri
    val attachmentUri = Uri(attached(action2).attachmentName)
    attachmentUri.isAbsolute shouldBe true
  }

  it should "put and read same attachment" in {
    implicit val tid: TransactionId = transid()
    val size = 4000
    val bytes = randomBytes(size)
    val base64 = Base64.getEncoder.encodeToString(bytes)

    val exec = javaDefault(base64, Some("hello"))
    val javaAction =
      WhiskAction(namespace, EntityName("attachment_unique"), exec)

    val i1 = WhiskAction.put(datastore, javaAction, old = None).futureValue
    val action2 = datastore.get[WhiskAction](i1, attachmentHandler).futureValue
    val action3 = WhiskAction.get(datastore, i1.id, i1.rev).futureValue

    docsToDelete += ((datastore, i1))

    attached(action2).attachmentType shouldBe ExecManifest.runtimesManifest
      .resolveDefaultRuntime(JAVA_DEFAULT)
      .get
      .attached
      .get
      .attachmentType
    attached(action2).length shouldBe Some(size)
    attached(action2).digest should not be empty

    action3.exec shouldBe exec
    inlined(action3).value shouldBe base64
  }

  private def attached(a: WhiskAction): Attached =
    a.exec.asInstanceOf[CodeExec[Attachment[Nothing]]].code.asInstanceOf[Attached]

  private def inlined(a: WhiskAction): Inline[String] =
    a.exec.asInstanceOf[CodeExec[Attachment[String]]].code.asInstanceOf[Inline[String]]

  private def randomBytes(size: Int): Array[Byte] = {
    val arr = new Array[Byte](size)
    Random.nextBytes(arr)
    arr
  }
}
