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

import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import whisk.common.TransactionId
import whisk.core.controller.test.WhiskAuthHelpers
import whisk.core.database.CacheChangeNotification
import whisk.core.entity.Attachments.{Attached, Attachment}
import whisk.core.entity._
import whisk.core.entity.test.ExecHelpers

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
    val action2 = datastore.get[WhiskAction](i1).futureValue

    //Change attachment to inline one otherwise WhiskAction would not go for putAndAttach
    val action2Updated = action2.copy(exec = exec).revision[WhiskAction](i1.rev)
    val i2 = WhiskAction.put(datastore, action2Updated, old = Some(action2)).futureValue
    val action3 = datastore.get[WhiskAction](i2).futureValue

    docsToDelete += ((datastore, i2))

    attached(action2).attachmentName should not be attached(action3).attachmentName

    //Check that attachment name is actually a uri
    val attachmentUri = Uri(attached(action2).attachmentName)
    attachmentUri.isAbsolute shouldBe true
  }

  private def attached(a: WhiskAction): Attached =
    a.exec.asInstanceOf[CodeExec[Attachment[Nothing]]].code.asInstanceOf[Attached]
}
