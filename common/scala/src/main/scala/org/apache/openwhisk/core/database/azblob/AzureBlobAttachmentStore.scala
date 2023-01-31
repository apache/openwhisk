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

import java.time.OffsetDateTime

import akka.actor.ActorSystem
import akka.event.Logging
import akka.event.Logging.InfoLevel
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.{ContentType, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, ByteStringBuilder}
import com.azure.storage.blob.sas.{BlobContainerSasPermission, BlobServiceSasSignatureValues}
import com.azure.storage.blob.{BlobContainerAsyncClient, BlobContainerClientBuilder, BlobUrlParts}
import com.azure.storage.common.StorageSharedKeyCredential
import com.azure.storage.common.policy.{RequestRetryOptions, RetryPolicyType}
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
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.DocId
import pureconfig._
import pureconfig.generic.auto._
import reactor.core.publisher.Flux

import scala.compat.java8.FutureConverters._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

case class AzureCDNConfig(domainName: String)
case class AzBlobConfig(endpoint: String,
                        accountKey: String,
                        containerName: String,
                        accountName: String,
                        connectionString: Option[String],
                        prefix: Option[String],
                        retryConfig: AzBlobRetryConfig,
                        azureCdnConfig: Option[AzureCDNConfig] = None) {
  def prefixFor[D](implicit tag: ClassTag[D]): String = {
    val className = tag.runtimeClass.getSimpleName.toLowerCase
    prefix.map(p => s"$p/$className").getOrElse(className)
  }
}
case class AzBlobRetryConfig(retryPolicyType: RetryPolicyType,
                             maxTries: Int,
                             tryTimeout: FiniteDuration,
                             retryDelay: FiniteDuration,
                             secondaryHost: Option[String])
object AzureBlobAttachmentStoreProvider extends AttachmentStoreProvider {
  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging): AttachmentStore = {
    makeStore[D](actorSystem.settings.config)
  }

  def makeStore[D <: DocumentSerializer: ClassTag](config: Config)(implicit actorSystem: ActorSystem,
                                                                   logging: Logging): AttachmentStore = {
    val azConfig = loadConfigOrThrow[AzBlobConfig](config, ConfigKeys.azBlob)
    new AzureBlobAttachmentStore(createClient(azConfig), azConfig.prefixFor[D], azConfig)
  }

  def createClient(config: AzBlobConfig): BlobContainerAsyncClient = {
    val builder = new BlobContainerClientBuilder()

    //If connection string is specified then it would have all needed info
    //Mostly used for testing using Azurite
    config.connectionString match {
      case Some(s) => builder.connectionString(s)
      case _ =>
        builder
          .endpoint(config.endpoint)
          .credential(new StorageSharedKeyCredential(config.accountName, config.accountKey))
    }

    builder
      .containerName(config.containerName)
      .retryOptions(new RequestRetryOptions(
        config.retryConfig.retryPolicyType,
        config.retryConfig.maxTries,
        config.retryConfig.tryTimeout.toSeconds.toInt,
        config.retryConfig.retryDelay.toMillis,
        config.retryConfig.retryDelay.toMillis,
        config.retryConfig.secondaryHost.orNull))
      .buildAsyncClient()
  }
}

class AzureBlobAttachmentStore(client: BlobContainerAsyncClient, prefix: String, config: AzBlobConfig)(
  implicit
  system: ActorSystem,
  logging: Logging)
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
    val source = getAttachmentSource(objectKey(docId, name), config)

    val f = source.flatMap {
      case Some(x) => x.runWith(sink)
      case None    => Future.failed(NoDocumentException("Not found on 'readAttachment'."))
    }

    val g = f.transform(
      { s =>
        transid
          .finished(this, start, s"[ATT_GET] '$prefix' completed: found attachment '$name' of document 'id: $docId'")
        s
      }, {
        case e: NoDocumentException =>
          transid
            .finished(
              this,
              start,
              s"[ATT_GET] '$prefix', retrieving attachment '$name' of document 'id: $docId'; not found.",
              logLevel = Logging.ErrorLevel)
          e
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
      transid.started(
        this,
        DATABASE_ATTS_DELETE,
        s"[ATTS_DELETE] deleting attachments of document 'id: $docId' with prefix ${objectKeyPrefix(docId)}")

    var count = 0
    val f = Source
      .fromPublisher(client.listBlobsByHierarchy(objectKeyPrefix(docId)))
      .mapAsync(1) { b =>
        count += 1
        val startDelete =
          transid.started(
            this,
            DATABASE_ATT_DELETE,
            s"[ATT_DELETE] deleting attachment '${b.getName}' of document 'id: $docId'")
        client
          .getBlobAsyncClient(b.getName)
          .delete()
          .toFuture
          .toScala
          .map(
            _ =>
              transid.finished(
                this,
                startDelete,
                s"[ATT_DELETE] completed: deleting attachment '${b.getName}' of document 'id: $docId'"))
          .recover {
            case t =>
              transid.failed(
                this,
                startDelete,
                s"[ATT_DELETE] failed: deleting attachment '${b.getName}' of document 'id: $docId' error: $t")
          }

      }
      .recover {
        case t =>
          logging.error(this, s"[ATT_DELETE] :error in delete ${t}")
          throw t
      }
      .runWith(Sink.seq)
      .map(_ => true)

    f.foreach(
      _ =>
        transid.finished(
          this,
          start,
          s"[ATTS_DELETE] completed: deleting ${count} attachments of document 'id: $docId'",
          InfoLevel))

    reportFailure(
      f,
      start,
      failure => s"[ATTS_DELETE] '$prefix' internal error, doc: '$docId', failure: '${failure.getMessage}'")
  }

  override protected[core] def deleteAttachment(docId: DocId, name: String)(implicit
                                                                            transid: TransactionId): Future[Boolean] = {
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

  private def getAttachmentSource(objectKey: String, config: AzBlobConfig)(
    implicit
    tid: TransactionId): Future[Option[Source[ByteString, Any]]] = {
    val blobClient = client.getBlobAsyncClient(objectKey).getBlockBlobAsyncClient

    config.azureCdnConfig match {
      case Some(cdnConfig) =>
        //setup sas token
        def expiryTime = OffsetDateTime.now().plusDays(1)
        def permissions =
          new BlobContainerSasPermission()
            .setReadPermission(true)
        val sigValues = new BlobServiceSasSignatureValues(expiryTime, permissions)
        val sas = blobClient.generateSas(sigValues)
        //parse the url, and reset the host
        val parts = BlobUrlParts.parse(blobClient.getBlobUrl)
        val url = parts.setHost(cdnConfig.domainName)
        logging.info(
          this,
          s"[ATT_GET] '$prefix' downloading attachment from azure cdn '$objectKey' with url (sas params not displayed) ${url}")
        //append the sas params to the url before downloading
        val cdnUrlWithSas = s"${url.toUrl.toString}?$sas"
        getUrlContent(cdnUrlWithSas)
      case None =>
        blobClient.exists().toFuture.toScala.map { exists =>
          if (exists) {
            val bbFlux = blobClient.download()
            Some(Source.fromPublisher(bbFlux).map(ByteString.fromByteBuffer))
          } else {
            throw NoDocumentException("Not found on 'readAttachment'.")
          }
        }
    }
  }
  private def getUrlContent(uri: Uri): Future[Option[Source[ByteString, Any]]] = {
    val future = Http().singleRequest(HttpRequest(uri = uri))
    future.flatMap {
      case HttpResponse(status, _, entity, _) if status.isSuccess() && !status.isRedirection() =>
        Future.successful(Some(entity.dataBytes))
      case HttpResponse(status, _, entity, _) =>
        if (status == NotFound) {
          entity.discardBytes()
          throw NoDocumentException("Not found on 'readAttachment'.")
        } else {
          Unmarshal(entity).to[String].map { err =>
            throw new Exception(s"failed to download ${uri} status was ${status} response was ${err}")
          }
        }
    }
  }
}
