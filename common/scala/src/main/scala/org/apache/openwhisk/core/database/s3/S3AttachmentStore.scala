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

package org.apache.openwhisk.core.database.s3

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.CacheDirectives._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentType, HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.alpakka.s3.headers.CannedAcl
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception, S3Headers, S3Settings}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.typesafe.config.Config
import org.apache.openwhisk.common.LoggingMarkers.{
  DATABASE_ATTS_DELETE,
  DATABASE_ATT_DELETE,
  DATABASE_ATT_GET,
  DATABASE_ATT_SAVE
}
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.StoreUtils._
import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.DocId
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object S3AttachmentStoreProvider extends AttachmentStoreProvider {
  val alpakkaConfigKey = s"${ConfigKeys.s3}.alpakka"
  case class S3Config(bucket: String, prefix: Option[String], cloudFrontConfig: Option[CloudFrontConfig] = None) {
    def prefixFor[D](implicit tag: ClassTag[D]): String = {
      val className = tag.runtimeClass.getSimpleName.toLowerCase
      prefix.map(p => s"$p/$className").getOrElse(className)
    }

    def signer: Option[UrlSigner] = cloudFrontConfig.map(CloudFrontSigner)
  }

  override def makeStore[D <: DocumentSerializer: ClassTag]()(implicit actorSystem: ActorSystem,
                                                              logging: Logging,
                                                              materializer: ActorMaterializer): AttachmentStore = {
    val config = loadConfigOrThrow[S3Config](ConfigKeys.s3)
    new S3AttachmentStore(s3Settings(actorSystem.settings.config), config.bucket, config.prefixFor[D], config.signer)
  }

  def makeStore[D <: DocumentSerializer: ClassTag](config: Config)(implicit actorSystem: ActorSystem,
                                                                   logging: Logging,
                                                                   materializer: ActorMaterializer): AttachmentStore = {
    val s3config = loadConfigOrThrow[S3Config](config, ConfigKeys.s3)
    new S3AttachmentStore(s3Settings(config), s3config.bucket, s3config.prefixFor[D], s3config.signer)
  }

  private def s3Settings(config: Config) = S3Settings(config.getConfig(alpakkaConfigKey))

}

trait UrlSigner {
  def getSignedURL(s3ObjectKey: String): Uri
}

class S3AttachmentStore(s3Settings: S3Settings, bucket: String, prefix: String, urlSigner: Option[UrlSigner])(
  implicit system: ActorSystem,
  logging: Logging,
  materializer: ActorMaterializer)
    extends AttachmentStore {

  private val s3attributes = S3Attributes.settings(s3Settings)
  private val commonS3Headers = {
    val cache = `Cache-Control`(`max-age`(365.days.toSeconds))
    S3Headers()
      .withCannedAcl(CannedAcl.Private) //All contents are private
      .withCustomHeaders(Map(cache.name -> cache.value)) //As objects are immutable cache them for long time
  }
  override val scheme = "s3"

  override protected[core] implicit val executionContext: ExecutionContext = system.dispatcher

  logging.info(this, s"Initializing S3AttachmentStore with bucket=[$bucket], prefix=[$prefix], signer=[$urlSigner]")

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
      .runWith(
        combinedSink(
          S3.multipartUploadWithHeaders(bucket, objectKey(docId, name), contentType, s3Headers = commonS3Headers)
            .withAttributes(s3attributes)))
      .map(r => AttachResult(r.digest, r.length))

    f.foreach(_ =>
      transid
        .finished(this, start, s"[ATT_PUT] '$prefix' completed uploading attachment '$name' of document 'id: $docId'"))

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
    val source = getAttachmentSource(objectKey(docId, name))

    val f = source.flatMap {
      case Some(x) => x.withAttributes(s3attributes).runWith(sink)
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

  private def getAttachmentSource(objectKey: String): Future[Option[Source[ByteString, Any]]] = urlSigner match {
    case Some(signer) => getUrlContent(signer.getSignedURL(objectKey))

    // When reading from S3 we get an optional source of ByteString and Metadata if the object exist
    // For such case drop the metadata
    case None =>
      S3.download(bucket, objectKey)
        .withAttributes(s3attributes)
        .runWith(Sink.head)
        .map(x => x.map(_._1))
  }

  private def getUrlContent(uri: Uri): Future[Option[Source[ByteString, Any]]] = {
    val future = Http().singleRequest(HttpRequest(uri = uri))
    future.flatMap {
      case HttpResponse(status, _, entity, _) if status.isSuccess() && !status.isRedirection() =>
        Future.successful(Some(entity.dataBytes))
      case HttpResponse(_, _, entity, _) =>
        Unmarshal(entity).to[String].map { err =>
          //With CloudFront also the error message confirms to same S3 exception format
          val exp = new S3Exception(err)
          if (isMissingKeyException(exp)) None else throw exp
        }
    }
  }

  override protected[core] def deleteAttachments(docId: DocId)(implicit transid: TransactionId): Future[Boolean] = {
    val start =
      transid.started(
        this,
        DATABASE_ATTS_DELETE,
        s"[ATT_DELETE] deleting attachments of document 'id: $docId' with prefix ${objectKeyPrefix(docId)}")

    val f = S3
      .deleteObjectsByPrefix(bucket, Some(objectKeyPrefix(docId)))
      .withAttributes(s3attributes)
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

    val f = S3
      .deleteObject(bucket, objectKey(docId, name))
      .withAttributes(s3attributes)
      .runWith(Sink.head)
      .map(_ => true)

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

  private def isMissingKeyException(e: Throwable): Boolean = {
    //In some case S3Exception is a sub cause. So need to recurse
    e match {
      case s: S3Exception if s.code == "NoSuchKey" => true
      // In case of CloudFront a missing key would be reflected as access denied
      case s: S3Exception if s.code == "AccessDenied" && urlSigner.isDefined => true
      case t if t != null && isMissingKeyException(t.getCause)               => true
      case _                                                                 => false
    }
  }
}
