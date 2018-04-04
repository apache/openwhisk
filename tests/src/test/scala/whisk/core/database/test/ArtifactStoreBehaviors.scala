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
import whisk.common.{TransactionCounter, TransactionId}
import whisk.core.database.{ArtifactStore, DocumentConflictException, NoDocumentException}
import whisk.core.entity._

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
}
