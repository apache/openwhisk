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

import java.time.Instant

import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.json.{JsArray, JsNumber, JsObject, JsString, JsValue}
import whisk.common.{TransactionCounter, TransactionId}
import whisk.core.database.{ArtifactStore, DocumentConflictException, NoDocumentException, StaleParameter}
import whisk.core.entity.WhiskEntityQueries.TOP
import whisk.core.entity._
import whisk.utils.JsHelpers

import scala.util.Random

trait ArtifactStoreBehaviors
    extends ScalaFutures
    with TransactionCounter
    with Matchers
    with StreamLogging
    with DbUtils
    with WskActorSystem
    with IntegrationPatience
    with BeforeAndAfterAll {
  this: FlatSpec =>

  override val instanceOrdinal = 0

  protected implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)

  def authStore: ArtifactStore[WhiskAuth]
  def entityStore: ArtifactStore[WhiskEntity]
  def activationStore: ArtifactStore[WhiskActivation]

  def storeType: String

  override def afterAll(): Unit = {
    println("Shutting down store connections")
    authStore.shutdown()
    entityStore.shutdown()
    activationStore.shutdown()
    super.afterAll()
  }

  protected val prefix = s"artifactTests_${Random.alphanumeric.take(10).mkString}"

  private val ns = EntityPath(prefix)
  private val exec = BlackBoxExec(ExecManifest.ImageName("image"), None, None, native = false)

  behavior of s"${storeType}ArtifactStore put"

  it should "put document and get a revision 1" in {
    implicit val tid: TransactionId = transid()
    val doc = put(authStore, newAuth())
    doc.rev.empty shouldBe false
  }

  it should "put and update document" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    val auth2 =
      getWhiskAuth(doc)
        .copy(namespaces = Set(wskNS("foo1")))
        .revision[WhiskAuth](doc.rev)
    val doc2 = put(authStore, auth2)

    doc2.rev should not be doc.rev
    doc2.rev.empty shouldBe false
  }

  it should "throw DocumentConflictException when updated with old revision" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    val auth2 = getWhiskAuth(doc).copy(namespaces = Set(wskNS("foo1"))).revision[WhiskAuth](doc.rev)
    val doc2 = put(authStore, auth2)

    //Updated with _rev set to older one
    val auth3 = getWhiskAuth(doc2).copy(namespaces = Set(wskNS("foo2"))).revision[WhiskAuth](doc.rev)
    intercept[DocumentConflictException] {
      put(authStore, auth3)
    }
  }

  it should "throw DocumentConflictException if document with same id is inserted twice" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    intercept[DocumentConflictException] {
      put(authStore, auth)
    }
  }

  behavior of s"${storeType}ArtifactStore delete"

  it should "deletes existing document" in {
    implicit val tid: TransactionId = transid()
    val doc = put(authStore, newAuth())
    delete(authStore, doc) shouldBe true
  }

  it should "throws IllegalArgumentException when deleting without revision" in {
    intercept[IllegalArgumentException] {
      implicit val tid: TransactionId = transid()
      delete(authStore, DocInfo("doc-with-empty-revision"))
    }
  }

  it should "throws NoDocumentException when document does not exist" in {
    intercept[NoDocumentException] {
      implicit val tid: TransactionId = transid()
      delete(authStore, DocInfo ! ("non-existing-doc", "42"))
    }
  }

  behavior of s"${storeType}ArtifactStore get"

  it should "get existing entity matching id and rev" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)
    val authFromGet = getWhiskAuth(doc)
    authFromGet shouldBe auth
    authFromGet.docinfo.rev shouldBe doc.rev
  }

  it should "get existing entity matching id only" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)
    val authFromGet = getWhiskAuth(doc)
    authFromGet shouldBe auth
  }

  it should "get entity with timestamp" in {
    implicit val tid: TransactionId = transid()
    val activation = WhiskActivation(
      EntityPath("testnamespace"),
      EntityName("activation1"),
      Subject(),
      ActivationId.generate(),
      start = Instant.now,
      end = Instant.now)
    val activationDoc = put(activationStore, activation)
    val activationFromDb = activationStore.get[WhiskActivation](activationDoc).futureValue
    activationFromDb shouldBe activation
  }

  it should "throws NoDocumentException when document revision does not match" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    val auth2 = getWhiskAuth(doc).copy(namespaces = Set(wskNS("foo1"))).revision[WhiskAuth](doc.rev)
    val doc2 = put(authStore, auth2)

    authStore.get[WhiskAuth](doc).failed.futureValue shouldBe a[NoDocumentException]

    val authFromGet = getWhiskAuth(doc2)
    authFromGet shouldBe auth2
  }

  it should "throws NoDocumentException when document does not exist" in {
    implicit val tid: TransactionId = transid()
    authStore.get[WhiskAuth](DocInfo("non-existing-doc")).failed.futureValue shouldBe a[NoDocumentException]
  }

  behavior of s"${storeType}ArtifactStore query"

  it should "find single entity" in {
    implicit val tid: TransactionId = transid()

    val ns = newNS()
    val action = newAction(ns)
    val docInfo = put(entityStore, action)

    val result = query[WhiskEntity](
      entityStore,
      "whisks/actions",
      List(ns.asString, 0),
      List(ns.asString, TOP, TOP),
      includeDocs = true)

    result should have length 1

    def js = result.head
    js.fields("id") shouldBe JsString(docInfo.id.id)
    js.fields("key") shouldBe JsArray(JsString(ns.asString), JsNumber(action.updated.toEpochMilli))
    js.fields.get("value") shouldBe defined
    js.fields.get("doc") shouldBe defined
    js.fields("value") shouldBe action.summaryAsJson
    dropRev(js.fields("doc").asJsObject) shouldBe action.toDocumentRecord
  }

  private def query[A <: WhiskEntity](
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

  private def getWhiskAuth(doc: DocInfo)(implicit transid: TransactionId) = {
    authStore.get[WhiskAuth](doc).futureValue
  }

  private def newAuth() = {
    val subject = Subject()
    val namespaces = Set(wskNS("foo"))
    WhiskAuth(subject, namespaces)
  }

  private def wskNS(name: String) = {
    WhiskNamespace(EntityName(name), AuthKey())
  }

  private def newAction(ns: EntityPath): WhiskAction = {
    WhiskAction(ns, aname(), exec)
  }

  private def newActivation(ns: String, actionName: String, start: Long): WhiskActivation = {
    WhiskActivation(
      EntityPath(ns),
      EntityName(actionName),
      Subject(),
      ActivationId.generate(),
      Instant.ofEpochMilli(start),
      Instant.ofEpochMilli(start + 1000))
  }

  private def aname() = MakeName.next("querytests")

  private def newNS() = EntityPath(s"artifactTests_${Random.alphanumeric.take(10).mkString}")

  private object MakeName {
    @volatile var counter = 1
    def next(prefix: String = "test")(): EntityName = {
      counter = counter + 1
      EntityName(s"${prefix}_name$counter")
    }
  }

  private def dropRev(js: JsObject): JsObject = {
    JsObject(js.fields - "_rev")
  }

  private def getJsObject(js: JsObject, fields: String*): JsObject = {
    JsHelpers.getFieldPath(js, fields: _*).get.asJsObject
  }

  private def getJsField(js: JsObject, subObject: String, fieldName: String): JsValue = {
    js.fields(subObject).asJsObject().fields(fieldName)
  }
}
