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

package whisk.core.database.test.behavior

import java.time.Instant

import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import spray.json.{JsObject, JsValue}
import whisk.common.TransactionId
import whisk.core.database.test.DbUtils
import whisk.core.database.{ArtifactStore, StaleParameter}
import whisk.core.entity._
import whisk.utils.JsHelpers

import scala.util.Random

trait ArtifactStoreBehaviorBase
    extends FlatSpec
    with ScalaFutures
    with Matchers
    with StreamLogging
    with DbUtils
    with WskActorSystem
    with IntegrationPatience
    with BeforeAndAfterEach
    with BeforeAndAfterAll {

  //Bring in sync the timeout used by ScalaFutures and DBUtils
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)

  protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  protected val prefix = s"artifactTCK_${Random.alphanumeric.take(4).mkString}"

  def authStore: ArtifactStore[WhiskAuth]
  def entityStore: ArtifactStore[WhiskEntity]
  def activationStore: ArtifactStore[WhiskActivation]

  def storeType: String

  override def afterEach(): Unit = {
    cleanup()
  }

  override def afterAll(): Unit = {
    println("Shutting down store connections")
    authStore.shutdown()
    entityStore.shutdown()
    activationStore.shutdown()
    super.afterAll()
  }

  //~----------------------------------------< utility methods >

  protected def query[A <: WhiskEntity](
    db: ArtifactStore[A],
    table: String,
    startKey: List[Any],
    endKey: List[Any],
    skip: Int = 0,
    limit: Int = 0,
    includeDocs: Boolean = false,
    descending: Boolean = true,
    reduce: Boolean = false,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId): List[JsObject] = {
    db.query(table, startKey, endKey, skip, limit, includeDocs, descending, reduce, stale).futureValue
  }

  protected def count[A <: WhiskEntity](
    db: ArtifactStore[A],
    table: String,
    startKey: List[Any],
    endKey: List[Any],
    skip: Int = 0,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId): Long = {
    db.count(table, startKey, endKey, skip, stale).futureValue
  }

  protected def getWhiskAuth(doc: DocInfo)(implicit transid: TransactionId) = {
    authStore.get[WhiskAuth](doc).futureValue
  }

  protected def newAuth() = {
    val subject = Subject()
    val namespaces = Set(wskNS("foo"))
    WhiskAuth(subject, namespaces)
  }

  protected def wskNS(name: String) = {
    WhiskNamespace(EntityName(name), AuthKey())
  }

  private val exec = BlackBoxExec(ExecManifest.ImageName("image"), None, None, native = false)

  protected def newAction(ns: EntityPath): WhiskAction = {
    WhiskAction(ns, aname(), exec)
  }

  protected def newActivation(ns: String, actionName: String, start: Long): WhiskActivation = {
    WhiskActivation(
      EntityPath(ns),
      EntityName(actionName),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
  }

  protected def aname() = EntityName(s"${prefix}_name_${randomString()}")

  protected def newNS() = EntityPath(s"${prefix}_ns_${randomString()}")

  private def randomString() = Random.alphanumeric.take(5).mkString

  protected def getJsObject(js: JsObject, fields: String*): JsObject = {
    JsHelpers.getFieldPath(js, fields: _*).get.asJsObject
  }

  protected def getJsField(js: JsObject, subObject: String, fieldName: String): JsValue = {
    js.fields(subObject).asJsObject().fields(fieldName)
  }
}
