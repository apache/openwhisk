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

package whisk.core.database.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentType
import akka.stream.ActorMaterializer
import akka.stream.alpakka.s3.scaladsl.S3Client
import akka.stream.alpakka.s3.{S3Exception, S3Settings}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.config.Config
import pureconfig.loadConfigOrThrow
import whisk.common.LoggingMarkers.{DATABASE_ATTS_DELETE, DATABASE_ATT_DELETE, DATABASE_ATT_GET, DATABASE_ATT_SAVE}
import whisk.common.{Logging, TransactionId}
import whisk.core.ConfigKeys
import whisk.core.database.StoreUtils._
import whisk.core.database._
import whisk.core.entity.DocId

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object S3AttachmentStoreProvider extends AttachmentStoreProvider {
  val alpakkaConfigKey = s"${ConfigKeys.s3}.alpakka"
  case class S3Config(bucket: String) {
    def prefixFor[D](implicit tag: ClassTag[D]): String = {
      tag.runtimeClass.getSimpleName.toLowerCase
    }
  }

  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging,
                                                              materializer: ActorMaterializer): AttachmentStore = {
    val client = new S3Client(S3Settings(alpakkaConfigKey))
    val config = loadConfigOrThrow[S3Config](ConfigKeys.s3)
    new S3AttachmentStore(client, config.bucket, config.prefixFor[D])
  }

  def makeStore[D <: DocumentSerializer: ClassTag](config: Config)(implicit actorSystem: ActorSystem,
                                                                   logging: Logging,
                                                                   materializer: ActorMaterializer): AttachmentStore = {
    val client = new S3Client(S3Settings(config, alpakkaConfigKey))
    val s3config = loadConfigOrThrow[S3Config](config, ConfigKeys.s3)
    new S3AttachmentStore(client, s3config.bucket, s3config.prefixFor[D])
  }

}
class S3AttachmentStore(client: S3Client, bucket: String, prefix: String)(implicit system: ActorSystem,
                                                                          logging: Logging,
                                                                          materializer: ActorMaterializer)
    extends AttachmentStore {
  override val scheme = "s3"

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  override protected[core] def attach(
    docId: DocId,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[AttachResult] = {
    require(name != null, "name undefined")
    val start =
      transid.started(this, DATABASE_ATT_SAVE, s"[ATT_PUT] uploading attachment '$name' of document 'id: $docId'")

    //A possible optimization for small attachments < 5MB can be to use putObject instead of multipartUpload
    //and thus use 1 remote call instead of 3
    val f = docStream
      .runWith(combinedSink(client.multipartUpload(bucket, objectKey(docId, name), contentType)))
      .map(r => AttachResult(r.digest, r.length))

    f.onSuccess({
      case _ =>
        transid
          .finished(this, start, s"[ATT_PUT] '$prefix' completed uploading attachment '$name' of document 'id: $docId'")
    })

    reportFailure(
      f,
      start,
      failure => s"[ATT_PUT] '$prefix' internal error, name: '$name', doc: '$docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def readAttachment[T](docId: DocId, name: String, sink: Sink[ByteString, Future[T]])(
    implicit transid: TransactionId): Future[T] = {
    require(name != null, "name undefined")
    val start =
      transid.started(
        this,
        DATABASE_ATT_GET,
        s"[ATT_GET] '$prefix' finding attachment '$name' of document 'id: $docId'")
    val (source, _) = client.download(bucket, objectKey(docId, name))

    val f = source.runWith(sink)

    val g = f.transform(
      { s =>
        transid
          .finished(this, start, s"[ATT_GET] '$prefix' completed: found attachment '$name' of document 'id: $docId'")
        s
      }, {
        case s: Throwable if isMissingKeyException(s) =>
          transid
            .finished(
              this,
              start,
              s"[ATT_GET] '$prefix', retrieving attachment '$name' of document 'id: $docId'; not found.")
          NoDocumentException("Not found on 'readAttachment'.")
        case e => e
      })

    reportFailure(
      g,
      start,
      failure =>
        s"[ATT_GET] '$prefix' internal error, name: '$name', doc: 'id: $docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachments(docId: DocId)(implicit transid: TransactionId): Future[Boolean] = {
    val start =
      transid.started(this, DATABASE_ATTS_DELETE, s"[ATT_DELETE] deleting attachments of document 'id: $docId'")

    //S3 provides API to delete multiple objects in single call however alpakka client
    //currently does not support that and also in current usage 1 docs has at most 1 attachment
    //so current approach would also involve 2 remote calls
    val f = client
      .listBucket(bucket, Some(objectKeyPrefix(docId)))
      .mapAsync(1)(bc => client.deleteObject(bc.bucketName, bc.key))
      .runWith(Sink.seq)
      .map(_ => true)

    f.onSuccess {
      case _ =>
        transid.finished(this, start, s"[ATTS_DELETE] completed: deleting attachments of document 'id: $docId'")
    }

    reportFailure(
      f,
      start,
      failure => s"[ATTS_DELETE] '$prefix' internal error, doc: '$docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachment(docId: DocId, name: String)(
    implicit transid: TransactionId): Future[Boolean] = {
    val start =
      transid.started(this, DATABASE_ATT_DELETE, s"[ATT_DELETE] deleting attachment '$name' of document 'id: $docId'")

    val f = client
      .deleteObject(bucket, objectKey(docId, name))
      .map(_ => true)

    f.onSuccess {
      case _ =>
        transid.finished(this, start, s"[ATT_DELETE] completed: deleting attachment '$name' of document 'id: $docId'")
    }

    reportFailure(
      f,
      start,
      failure => s"[ATT_DELETE] '$prefix' internal error, doc: '$docId', failure: '${failure.getMessage}'")
  }

  override def shutdown(): Unit = {}

  private def objectKey(id: DocId, name: String): String = s"$prefix/${id.id}/$name"

  private def objectKeyPrefix(id: DocId): String = s"$prefix/${id.id}"

  private def isMissingKeyException(e: Throwable): Boolean = {
    //In some case S3Exception is a sub cause. So need to recurse
    e match {
      case s: S3Exception if s.code == "NoSuchKey"             => true
      case t if t != null && isMissingKeyException(t.getCause) => true
      case _                                                   => false
    }
  }
}
