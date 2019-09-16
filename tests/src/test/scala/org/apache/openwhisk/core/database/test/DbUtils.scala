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

import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

import akka.http.scaladsl.model.ContentType
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.Assertions
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.database.memory.MemoryArtifactStore
import org.apache.openwhisk.core.entity.Attachments.Attached
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.types.{AuthStore, EntityStore}

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success, Try}

/**
 * WARNING: the put/get/del operations in this trait operate directly on the datastore,
 * and in the presence of a cache, there will be inconsistencies if one mixes these
 * operations with those that flow through the cache. To mitigate this, use unique asset
 * names in tests, and defer all cleanup to the end of a test suite.
 */
trait DbUtils extends Assertions {
  implicit val dbOpTimeout = 15 seconds
  val instance = ControllerInstanceId("0")
  val docsToDelete = ListBuffer[(ArtifactStore[_], DocInfo)]()
  case class RetryOp() extends Throwable

  val cnt = new AtomicInteger(0)
  def transid() = TransactionId(cnt.incrementAndGet().toString)

  // Call each few successfully 5 before the test continues to increase probability, that each node of the
  // CouchDB/Cloudant cluster is updated.
  val successfulViewCalls = 5

  /**
   * Retry an operation 'step()' awaiting its result up to 'timeout'.
   * Attempt the operation up to 'count' times. The future from the
   * step is not aborted --- TODO fix this.
   */
  def retry[T](step: () => Future[T], timeout: Duration, count: Int = 100): Try[T] = {
    val graceBeforeRetry = 50.milliseconds
    val future = step()
    if (count > 0) try {
      val result = Await.result(future, timeout)
      Success(result)
    } catch {
      case n: NoDocumentException =>
        println("no document exception, retrying")
        Thread.sleep(graceBeforeRetry.toMillis)
        retry(step, timeout, count - 1)
      case RetryOp() =>
        println("condition not met, retrying")
        Thread.sleep(graceBeforeRetry.toMillis)
        retry(step, timeout, count - 1)
      case t: TimeoutException =>
        println("timed out, retrying")
        Thread.sleep(graceBeforeRetry.toMillis)
        retry(step, timeout, count - 1)
      case t: Throwable =>
        println(s"unexpected failure $t")
        Failure(t)
    } else Failure(new NoDocumentException("timed out"))
  }

  /**
   * Wait on a view to update with documents added to namespace. This uses retry above,
   * where the step performs a direct db query to retrieve the view and check the count
   * matches the given value.
   */
  def waitOnView[Au](db: ArtifactStore[Au], namespace: EntityName, count: Int, view: View)(
    implicit context: ExecutionContext,
    transid: TransactionId,
    timeout: Duration): Unit =
    waitOnViewImpl(db, List(namespace.asString), List(namespace.asString, WhiskQueries.TOP), count, view)

  /**
   * Wait on a view to update with documents added to namespace. This uses retry above,
   * where the step performs a direct db query to retrieve the view and check the count
   * matches the given value.
   */
  def waitOnView[Au](db: ArtifactStore[Au], path: EntityPath, count: Int, view: View)(
    implicit context: ExecutionContext,
    transid: TransactionId,
    timeout: Duration): Unit =
    waitOnViewImpl(db, List(path.asString), List(path.asString, WhiskQueries.TOP), count, view)

  /**
   * Wait on a view to update with documents added(don't specify the namespace). This uses retry above,
   * where the step performs a direct db query to retrieve the view and check the count
   * matches the given value.
   */
  def waitOnView[Au](db: ArtifactStore[Au], count: Int, view: View)(implicit context: ExecutionContext,
                                                                    transid: TransactionId,
                                                                    timeout: Duration): Unit =
    waitOnViewImpl(db, List.empty, List.empty, count, view)

  /**
   * Wait on a view to update with documents added to namespace. This uses retry above,
   * where the step performs a direct db query to retrieve the view and check the count
   * matches the given value.
   */
  private def waitOnViewImpl[Au](
    db: ArtifactStore[Au],
    startKey: List[String],
    endKey: List[String],
    count: Int,
    view: View)(implicit context: ExecutionContext, transid: TransactionId, timeout: Duration): Unit = {
    // Query the view at least `successfulViewCalls` times successfully, to handle inconsistency between several CouchDB-nodes.
    (0 until successfulViewCalls).map { _ =>
      val success = retry(() => {
        db.query(view.name, startKey, endKey, 0, 0, false, true, false, StaleParameter.No) map { l =>
          if (l.length != count) {
            throw RetryOp()
          } else true
        }
      }, timeout)
      assert(success.isSuccess, "wait aborted")
    }
  }

  /**
   * Wait on a view specific to a collection to update with documents added to that collection in namespace.
   * This uses retry above, where the step performs a collection-specific view query using the collection
   * factory. The result count from the view is checked against the given value.
   */
  def waitOnView(
    db: EntityStore,
    factory: WhiskEntityQueries[_],
    namespace: EntityPath,
    count: Int,
    includeDocs: Boolean = false)(implicit context: ExecutionContext, transid: TransactionId, timeout: Duration) = {
    // Query the view at least `successfulViewCalls` times successfully, to handle inconsistency between several CouchDB-nodes.
    (0 until successfulViewCalls).map { _ =>
      val success = retry(() => {
        factory.listCollectionInNamespace(db, namespace, 0, 0, includeDocs) map { l =>
          if (l.fold(_.length, _.length) < count) {
            throw RetryOp()
          } else true
        }
      }, timeout)
      assert(success.isSuccess, "wait aborted")
    }
  }

  /**
   * Wait on view for the authentication table. This is like the other waitOnViews but
   * specific to the WhiskAuth records.
   */
  def waitOnView(db: AuthStore, authkey: BasicAuthenticationAuthKey, count: Int)(implicit context: ExecutionContext,
                                                                                 transid: TransactionId,
                                                                                 timeout: Duration) = {
    // Query the view at least `successfulViewCalls` times successfully, to handle inconsistency between several CouchDB-nodes.
    (0 until successfulViewCalls).map { _ =>
      val success = retry(() => {
        Identity.list(db, List(authkey.uuid.asString, authkey.key.asString)) map { l =>
          if (l.length != count) {
            throw RetryOp()
          } else true
        }
      }, timeout)
      assert(success.isSuccess, "wait aborted after: " + timeout + ": " + success)
    }
  }

  /**
   * Wait on view using the CouchDbRestClient. This is like the other waitOnViews.
   */
  def waitOnView(db: CouchDbRestClient, designDocName: String, viewName: String, count: Int)(
    implicit context: ExecutionContext,
    timeout: Duration) = {
    // Query the view at least `successfulViewCalls` times successfully, to handle inconsistency between several CouchDB-nodes.
    (0 until successfulViewCalls).map { _ =>
      val success = retry(
        () => {
          db.executeView(designDocName, viewName)().map {
            case Right(doc) =>
              val length = doc.fields("rows").convertTo[List[JsObject]].length
              if (length != count) {
                throw RetryOp()
              } else true
            case Left(_) =>
              throw RetryOp()
          }
        },
        timeout)
      assert(success.isSuccess, "wait aborted after: " + timeout + ": " + success)
    }
  }

  /**
   * Puts document 'w' in datastore, and add it to gc queue to delete after the test completes.
   */
  def put[A, Au >: A](db: ArtifactStore[Au], w: A, garbageCollect: Boolean = true)(
    implicit transid: TransactionId,
    timeout: Duration = 10 seconds): DocInfo = {
    val docFuture = db.put(w)
    val doc = Await.result(docFuture, timeout)
    assert(doc != null)
    if (garbageCollect) docsToDelete += ((db, doc))
    doc
  }

  def putAndAttach[A <: DocumentRevisionProvider, Au >: A](
    db: ArtifactStore[Au],
    doc: A,
    update: (A, Attached) => A,
    contentType: ContentType,
    docStream: Source[ByteString, _],
    oldAttachment: Option[Attached],
    garbageCollect: Boolean = true)(implicit transid: TransactionId, timeout: Duration = 10 seconds): DocInfo = {
    val docFuture = db.putAndAttach[A](doc, update, contentType, docStream, oldAttachment)
    val newDoc = Await.result(docFuture, timeout)._1
    assert(newDoc != null)
    if (garbageCollect) docsToDelete += ((db, newDoc))
    newDoc
  }

  /**
   * Gets document by id from datastore, and add it to gc queue to delete after the test completes.
   */
  def get[A <: DocumentRevisionProvider, Au >: A](db: ArtifactStore[Au],
                                                  docid: DocId,
                                                  factory: DocumentFactory[A],
                                                  garbageCollect: Boolean = true)(implicit transid: TransactionId,
                                                                                  timeout: Duration = 10 seconds,
                                                                                  ma: Manifest[A]): A = {
    val docFuture = factory.get(db, docid)
    val doc = Await.result(docFuture, timeout)
    assert(doc != null)
    if (garbageCollect) docsToDelete += ((db, docid.asDocInfo))
    doc
  }

  /**
   * Deletes document by id from datastore.
   */
  def del[A <: WhiskDocument, Au >: A](db: ArtifactStore[Au], docid: DocId, factory: DocumentFactory[A])(
    implicit transid: TransactionId,
    timeout: Duration = 10 seconds,
    ma: Manifest[A]) = {
    val docFuture = factory.get(db, docid)
    val doc = Await.result(docFuture, timeout)
    assert(doc != null)
    Await.result(db.del(doc.docinfo), timeout)
  }

  /**
   * Deletes document by id and revision from datastore.
   */
  def delete(db: ArtifactStore[_], docinfo: DocInfo)(implicit transid: TransactionId,
                                                     timeout: Duration = 10 seconds) = {
    Await.result(db.del(docinfo), timeout)
  }

  /**
   * Puts a document 'entity' into the datastore, then do a get to retrieve it and confirm the identity.
   */
  def putGetCheck[A <: DocumentRevisionProvider, Au >: A](db: ArtifactStore[Au],
                                                          entity: A,
                                                          factory: DocumentFactory[A],
                                                          gc: Boolean = true)(implicit transid: TransactionId,
                                                                              timeout: Duration = 10 seconds,
                                                                              ma: Manifest[A]): (DocInfo, A) = {
    val doc = put(db, entity, gc)
    assert(doc != null && doc.id.asString != null && doc.rev.asString != null)
    val future = factory.get(db, doc.id, doc.rev)
    val dbEntity = Await.result(future, timeout)
    assert(dbEntity != null)
    assert(dbEntity == entity)
    (doc, dbEntity)
  }

  /**
   * Deletes all documents added to gc queue.
   */
  def cleanup()(implicit timeout: Duration = 10 seconds) = {
    docsToDelete.map { e =>
      Try {
        Await.result(e._1.del(e._2)(TransactionId.testing), timeout)
        Await.result(e._1.deleteAttachments(e._2)(TransactionId.testing), timeout)
      }
    }
    docsToDelete.clear()
  }

  /**
   * Generates a Base64 string for code which would not be inlined by the ArtifactStore
   */
  def nonInlinedCode(db: ArtifactStore[_]): String = {
    encodedRandomBytes(nonInlinedAttachmentSize(db))
  }

  /**
   * Size in bytes for attachments which would always be inlined.
   */
  def inlinedAttachmentSize(db: ArtifactStore[_]): Int = {
    db match {
      case inliner: AttachmentSupport[_] =>
        inliner.maxInlineSize.toBytes.toInt - 1
      case _ =>
        throw new IllegalStateException(s"ArtifactStore does not support attachment inlining $db")
    }
  }

  /**
   * Size in bytes for attachments which would never be inlined.
   */
  def nonInlinedAttachmentSize(db: ArtifactStore[_]): Int = {
    db match {
      case inliner: AttachmentSupport[_] =>
        inliner.maxInlineSize.toBytes.toInt * 2
      case _ =>
        42
    }
  }

  def assumeAttachmentInliningEnabled(db: ArtifactStore[_]): Unit = {
    assume(inlinedAttachmentSize(db) > 0, "Attachment inlining is disabled")
  }

  protected def encodedRandomBytes(size: Int): String = Base64.getEncoder.encodeToString(randomBytes(size))

  def isMemoryStore(store: ArtifactStore[_]): Boolean = store.isInstanceOf[MemoryArtifactStore[_]]
  def isCouchStore(store: ArtifactStore[_]): Boolean = store.isInstanceOf[CouchDbRestStore[_]]

  protected def removeFromCache[A <: DocumentRevisionProvider](entity: WhiskEntity, factory: DocumentFactory[A])(
    implicit ec: ExecutionContext): Unit = {
    factory.removeId(CacheKey(entity))
  }

  protected def randomBytes(size: Int): Array[Byte] = {
    val arr = new Array[Byte](size)
    Random.nextBytes(arr)
    arr
  }
}
