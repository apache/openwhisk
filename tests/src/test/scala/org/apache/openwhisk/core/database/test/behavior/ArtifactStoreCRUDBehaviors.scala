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

package org.apache.openwhisk.core.database.test.behavior

import java.time.Instant

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{
  DocumentConflictException,
  DocumentProvider,
  DocumentRevisionMismatchException,
  NoDocumentException
}
import org.apache.openwhisk.core.entity._

trait ArtifactStoreCRUDBehaviors extends ArtifactStoreBehaviorBase {

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

  it should "put delete and then recreate document with same id with different rev" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    delete(authStore, doc) shouldBe true

    val auth2 = auth.copy(namespaces = Set(wskNS("foo1")))
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

  it should "work if same document was deleted earlier" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    //1. Create a document
    val doc = put(authStore, auth)

    //2. Now delete the document
    delete(authStore, doc) shouldBe true

    //3. Now recreate the same document.
    val doc2 = put(authStore, auth)

    //Recreating a deleted document should work
    doc2.rev.empty shouldBe false
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

  it should "throws DocumentConflictException when revision does not match" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    val auth2 = getWhiskAuth(doc).copy(namespaces = Set(wskNS("foo1"))).revision[WhiskAuth](doc.rev)
    val doc2 = put(authStore, auth2)

    intercept[DocumentConflictException] {
      delete(authStore, doc)
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
      start = Instant.now.inMills,
      end = Instant.now.inMills)
    val activationDoc = put(activationStore, activation)
    val activationFromDb = activationStore.get[WhiskActivation](activationDoc).futureValue
    activationFromDb shouldBe activation
  }

  it should "throws DocumentRevisionMismatchException when document revision does not match" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    val doc = put(authStore, auth)

    val auth2 = getWhiskAuth(doc).copy(namespaces = Set(wskNS("foo1"))).revision[WhiskAuth](doc.rev)
    val doc2 = put(authStore, auth2)

    authStore.get[WhiskAuth](doc).failed.futureValue shouldBe a[DocumentRevisionMismatchException]

    val authFromGet = getWhiskAuth(doc2)
    authFromGet shouldBe auth2
  }

  it should "throws NoDocumentException when document does not exist" in {
    implicit val tid: TransactionId = transid()
    authStore.get[WhiskAuth](DocInfo("non-existing-doc")).failed.futureValue shouldBe a[NoDocumentException]
  }

  it should "not get a deleted document" in {
    implicit val tid: TransactionId = transid()
    val auth = newAuth()
    //1. Create a document
    val docInfo = put(authStore, auth)

    //2. Now delete the document
    delete(authStore, docInfo) shouldBe true

    //3. Now getting a deleted document should fail
    authStore.get[WhiskAuth](docInfo).failed.futureValue shouldBe a[NoDocumentException]

    //Check get by id flow also which return none for such "soft" deleted document
    authStore match {
      case provider: DocumentProvider =>
        provider.get(docInfo.id).futureValue shouldBe None
      case _ =>
    }
  }
}
