package org.apache.openwhisk.core.database.mongodb

import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.test.behavior.ArtifactStoreBehaviorBase
import org.apache.openwhisk.core.database.{ArtifactStore, AttachmentStore, DocumentSerializer}
import org.apache.openwhisk.core.entity._
import org.scalatest.FlatSpec
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._

import scala.reflect.{classTag, ClassTag}
import scala.util.Try

trait MongoDBStoreBehaviorBase extends FlatSpec with ArtifactStoreBehaviorBase {
  override def storeType = "MongoDB"

  override lazy val storeAvailableCheck: Try[Any] = storeConfigTry

  val storeConfigTry = Try { loadConfigOrThrow[MongoDBConfig](ConfigKeys.mongodb) }

  override lazy val authStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MongoDBArtifactStoreProvider.makeArtifactStore[WhiskAuth](storeConfigTry.get, getAttachmentStore[WhiskAuth]())
  }

  override lazy val entityStore =
    MongoDBArtifactStoreProvider.makeArtifactStore[WhiskEntity](storeConfigTry.get, getAttachmentStore[WhiskEntity]())(
      classTag[WhiskEntity],
      WhiskEntityJsonFormat,
      WhiskDocumentReader,
      actorSystem,
      logging,
      materializer)

  override lazy val activationStore = {
    implicit val docReader: DocumentReader = WhiskDocumentReader
    MongoDBArtifactStoreProvider
      .makeArtifactStore[WhiskActivation](storeConfigTry.get, getAttachmentStore[WhiskActivation]())
  }

  override protected def getAttachmentStore(store: ArtifactStore[_]) =
    store.asInstanceOf[MongoDBArtifactStore[_]].attachmentStore

  protected def getAttachmentStore[D <: DocumentSerializer: ClassTag](): Option[AttachmentStore] = None
}
