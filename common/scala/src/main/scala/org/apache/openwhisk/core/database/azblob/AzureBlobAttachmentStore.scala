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

package org.apache.openwhisk.core.database.azblob

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model.ContentType
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import com.azure.storage.blob.{BlobContainerAsyncClient, BlobContainerClientBuilder}
import com.azure.storage.common.StorageSharedKeyCredential
import com.typesafe.config.Config
import org.apache.openwhisk.common.LoggingMarkers.{
  DATABASE_ATTS_DELETE,
  DATABASE_ATT_DELETE,
  DATABASE_ATT_GET,
  DATABASE_ATT_SAVE
}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.StoreUtils.{combinedSink, reportFailure}
import org.apache.openwhisk.core.database.{
  AttachResult,
  AttachmentStore,
  AttachmentStoreProvider,
  DocumentSerializer,
  NoDocumentException
}
import org.apache.openwhisk.core.entity.DocId
import pureconfig.loadConfigOrThrow
import reactor.core.publisher.Flux

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Success

case class AzBlobConfig(endpoint: String,
                        accountKey: String,
                        containerName: String,
                        accountName: String,
                        prefix: Option[String]) {
  def prefixFor[D](implicit tag: ClassTag[D]): String = {
    val className = tag.runtimeClass.getSimpleName.toLowerCase
    prefix.map(p => s"$p/$className").getOrElse(className)
  }
}

object AzureBlobAttachmentStoreProvider extends AttachmentStoreProvider {
  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging,
                                                              materializer: ActorMaterializer): AttachmentStore = {
    makeStore[D](actorSystem.settings.config)
  }

  def makeStore[D <: DocumentSerializer: ClassTag](config: Config)(implicit actorSystem: ActorSystem,
                                                                   logging: Logging,
                                                                   materializer: ActorMaterializer): AttachmentStore = {
    val azConfig = loadConfigOrThrow[AzBlobConfig](config, ConfigKeys.azBlob)
    new AzureBlobAttachmentStore(createClient(azConfig), azConfig.prefixFor[D])
  }

  def createClient(config: AzBlobConfig): BlobContainerAsyncClient = {
    new BlobContainerClientBuilder()
      .endpoint(config.endpoint)
      .credential(new StorageSharedKeyCredential(config.accountName, config.accountKey))
      .containerName(config.containerName)
      .buildAsyncClient()
  }
}

class AzureBlobAttachmentStore(client: BlobContainerAsyncClient, prefix: String)(implicit system: ActorSystem,
                                                                                 logging: Logging,
                                                                                 materializer: ActorMaterializer)
    extends AttachmentStore {
  override protected[core] def scheme: String = "az"

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  override protected[core] def attach(
    docId: DocId,
    name: String,
    contentType: ContentType,
    docStream: Source[ByteString, _])(implicit transid: TransactionId): Future[AttachResult] = {
    require(name != null, "name undefined")
    val start =
      transid.started(this, DATABASE_ATT_SAVE, s"[ATT_PUT] uploading attachment '$name' of document 'id: $docId'")
    val blobClient = getBlobClient(docId, name)

    //TODO Use BlobAsyncClient#upload(Flux<ByteBuffer>, com.azure.storage.blob.models.ParallelTransferOptions, boolean)
    val uploadSink = Sink.fold[ByteStringBuilder, ByteString](new ByteStringBuilder)((builder, b) => builder ++= b)

    val f = docStream.runWith(combinedSink(uploadSink))
    val g = f.flatMap { r =>
      val buff = r.uploadResult.result().compact
      val uf = blobClient.upload(Flux.fromArray(Array(buff.asByteBuffer)), buff.size).toFuture.toScala
      uf.map(_ => AttachResult(r.digest, r.length))
    }

    g.foreach(_ =>
      transid
        .finished(this, start, s"[ATT_PUT] '$prefix' completed uploading attachment '$name' of document 'id: $docId'"))

    reportFailure(
      g,
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
    val blobClient = getBlobClient(docId, name)
    val f = blobClient.exists().toFuture.toScala.flatMap { exists =>
      if (exists) {
        val bbFlux = blobClient.download()
        val rf = Source.fromPublisher(bbFlux).map(ByteString(_)).runWith(sink)
        rf.andThen {
          case Success(_) =>
            transid
              .finished(
                this,
                start,
                s"[ATT_GET] '$prefix' completed: found attachment '$name' of document 'id: $docId'")
        }
      } else {
        transid
          .finished(
            this,
            start,
            s"[ATT_GET] '$prefix', retrieving attachment '$name' of document 'id: $docId'; not found.",
            logLevel = Logging.ErrorLevel)
        Future.failed(NoDocumentException("Not found on 'readAttachment'."))
      }
    }

    reportFailure(
      f,
      start,
      failure =>
        s"[ATT_GET] '$prefix' internal error, name: '$name', doc: 'id: $docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachments(docId: DocId)(implicit transid: TransactionId): Future[Boolean] = {
    val start =
      transid.started(
        this,
        DATABASE_ATTS_DELETE,
        s"[ATT_DELETE] deleting attachments of document 'id: $docId' with prefix ${objectKeyPrefix(docId)}")

    val f = Source
      .fromPublisher(client.listBlobsByHierarchy(objectKeyPrefix(docId)))
      .mapAsync(1)(b => client.getBlobAsyncClient(b.getName).delete().toFuture.toScala)
      .runWith(Sink.seq)
      .map(_ => true)

    f.foreach(_ =>
      transid.finished(this, start, s"[ATTS_DELETE] completed: deleting attachments of document 'id: $docId'"))

    reportFailure(
      f,
      start,
      failure => s"[ATTS_DELETE] '$prefix' internal error, doc: '$docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachment(docId: DocId, name: String)(
    implicit transid: TransactionId): Future[Boolean] = {
    val start =
      transid.started(this, DATABASE_ATT_DELETE, s"[ATT_DELETE] deleting attachment '$name' of document 'id: $docId'")

    val f = getBlobClient(docId, name).delete().toFuture.toScala.map(_ => true)

    f.foreach(_ =>
      transid.finished(this, start, s"[ATT_DELETE] completed: deleting attachment '$name' of document 'id: $docId'"))

    reportFailure(
      f,
      start,
      failure => s"[ATT_DELETE] '$prefix' internal error, doc: '$docId', failure: '${failure.getMessage}'")
  }

  override def shutdown(): Unit = {}

  private def objectKey(id: DocId, name: String): String = s"$prefix/${id.id}/$name"

  private def objectKeyPrefix(id: DocId): String =
    s"$prefix/${id.id}/" //must end with a slash so that ".../<package>/<action>other" does not match for "<package>/<action>"

  private def getBlobClient(docId: DocId, name: String) =
    client.getBlobAsyncClient(objectKey(docId, name)).getBlockBlobAsyncClient
}
