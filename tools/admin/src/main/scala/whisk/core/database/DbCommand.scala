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

package whisk.core.database

import java.io.File
import java.nio.file.Files
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Framing, Keep, Sink, Source, StreamConverters}
import akka.stream.{ActorMaterializer, IOResult}
import akka.util.ByteString
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.CloseShieldOutputStream
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json._
import whisk.common.{Logging, TransactionId}
import whisk.core.cli.ConsoleUtil._
import whisk.core.cli._
import whisk.core.database.DbCommand._
import whisk.core.entity.Attachments.Attached
import whisk.core.entity._
import whisk.core.entity.size._
import whisk.utils.JsHelpers

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.{classTag, ClassTag}
import scala.util.{Failure, Properties, Success, Try}

class DbCommand extends Subcommand("db") with WhiskCommand {
  descr("work with dbs")

  val databases = Set("whisks", "activations", "subjects")

  abstract class DbSubcommand(commandNameAndAliases: String*) extends Subcommand(commandNameAndAliases: _*) {
    val dbTypeMapping: Map[String, ClassTag[_ <: DocumentSerializer]] =
      Map(
        "whisks" -> classTag[WhiskEntity],
        "activations" -> classTag[WhiskActivation],
        "subjects" -> classTag[WhiskAuth])

    val database = trailArg[String](descr = s"database type. One of $databases")

    validate(database) { db =>
      if (databases.contains(db)) {
        Right(Unit)
      } else {
        Left(CommandMessages.invalidDatabase(db, databases))
      }
    }

    def dbType: ClassTag[_ <: DocumentSerializer] = dbTypeMapping(database())
  }

  val get = new DbSubcommand("get") {
    descr("get contents of database")

    val docs = opt[Boolean](descr = "include document contents")

    val view = opt[String](descr = "the view in the database to get", argName = "VIEW")

    val out = opt[File](descr = "file to dump the contents to")

    //TODO Make use of this flag!
    val attachments =
      opt[Boolean](descr = "include attachments. Downloaded attachments would be stored under 'attachments' directory")

    val threads = opt[Int](descr = "Number of parallel attachment downloads", default = Some(5))

    dependsOnAny(attachments, List(out))
  }
  addSubcommand(get)

  val put = new DbSubcommand("put") {
    descr("add content to database")

    //TODO Support relative files also
    val in = opt[File](descr = "file to read contents from", required = true)

    val threads = opt[Int](descr = "Number of parallel puts to perform", default = Some(5))
    validateFileExists(in)
  }
  addSubcommand(put)

  def exec(cmd: ScallopConfBase)(implicit system: ActorSystem,
                                 logging: Logging,
                                 materializer: ActorMaterializer,
                                 transid: TransactionId): Future[Either[CommandError, String]] = {
    implicit val executionContext = system.dispatcher
    val result = cmd match {
      case `get` => getDBContents()
      case `put` => putDBContents()
    }
    result
  }

  def getDBContents()(implicit system: ActorSystem,
                      logging: Logging,
                      materializer: ActorMaterializer,
                      transid: TransactionId,
                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    val artifactStore = getStore(get.dbType)
    val store = createStreamingStore(get.dbType, artifactStore)

    val ticker = if (get.out.isDefined && showProgressBar()) getProgressBar(store, "Exporting") else NoopTicker

    val attachCounter = Sink.fold[Int, JsObject](0)((acc, js) => if (hasAttachment(js)) acc + 1 else acc)
    val combiner = (cf: Future[Int], iof: Future[IOResult]) => for { c <- cf; io <- iof } yield ReadResult(c, io)

    val outputSink = Flow[JsObject]
      .alsoToMat(attachCounter)(Keep.right) //Track attachment count
      .map(jsToStringLine)
      .via(tick(ticker))
      .toMat(createOutputSink())(combiner)

    val f = store
      .getAll(outputSink) //1. Write all js docs to a file
      .map {
        case (count, r) =>
          if (r.io.wasSuccessful) r.copy(count = count)
          else throw r.io.getError
      }
      .flatMap { rr =>
        ticker.close()
        //2. Now download attachments by reading output file
        if (rr.attachmentCount > 0) {
          val dump = get.out() //For attachment case out file is required
          downloadAttachments(dump, getOrCreateAttachmentDir(dump), rr.count, get.threads(), artifactStore)
            .map { sr =>
              rr.copy(downloads = Some(sr))
            }
        } else {
          Future.successful(rr)
        }
      }

    f.onComplete { _ =>
      ticker.close()
      artifactStore.shutdown()
    }
    f.map { r =>
      if (r.ok) Right(get.out.map(CommandMessages.dbContentToFile(r.count, _)).getOrElse(""))
      else Left(IllegalState(r.errorMsg))
    }
  }

  private case class ReadResult(attachmentCount: Int,
                                io: IOResult,
                                count: Long = 0,
                                downloads: Option[StreamResult] = None) {
    def ok: Boolean = ignoreAttachmentErrors || io.wasSuccessful && downloads.forall(_.failed == 0)

    def errorMsg = {
      val r = downloads.get
      CommandMessages.downloadAttachmentFailed(r.success, r.failed)
    }
  }

  def downloadAttachments(file: File, attachmentDir: File, count: Long, parallelCount: Int, store: ArtifactStore[_])(
    implicit transid: TransactionId,
    ec: ExecutionContext,
    materializer: ActorMaterializer): Future[StreamResult] = {
    val source = createJSStream(file)
    val ioOk = IOResult.createSuccessful(0)
    val ticker = if (showProgressBar()) new FiniteProgressBar("Downloading", count) else NoopTicker
    val f = source
      .filter(hasAttachment)
      .mapAsyncUnordered(parallelCount) { js =>
        val id = js.fields("_id").convertTo[String]
        val t = Try {
          //Try reading the attachment. If there is some error before actual read call then try would
          //ensure that only this record is dropped from processing
          val rev = js.fields("_rev").convertTo[String]
          val action = WhiskAction.serdes.read(js)
          action.revision(DocRevision(rev))
          action.exec match {
            case CodeExecAsAttachment(_, attached: Attached, _) =>
              val outFile = createFile(getAttachmentFile(attachmentDir, action))
              val sink = FileIO.toPath(outFile.toPath)
              store.readAttachment(action.docinfo, attached, sink)
            case _ => Future.successful(ioOk)
          }
        }
        val g = t match {
          case Success(ft) => ft
          case Failure(e)  => Future.failed(e)
        }

        //In case of error just record the count and thus recover from the failure
        g.map { r =>
            if (r.wasSuccessful) State.SUCCESS
            else {
              log.warn(s"Error occurred while downloading attachment for $id", r.getError)
              State.ERROR
            }
          }
          .recover {
            case e =>
              log.warn(s"Error occurred while downloading attachment for $id", e)
              State.ERROR
          }
      }
      .via(tick(ticker))
      .runWith(streamResultSink)

    f.onComplete(_ => ticker.close())
    f.map(_.toResult())
  }

  def putDBContents()(implicit system: ActorSystem,
                      logging: Logging,
                      materializer: ActorMaterializer,
                      transid: TransactionId,
                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    val authStore = WhiskAuthStore.datastore()
    val entityStore = WhiskEntityStore.datastore()
    val activationStore = WhiskActivationStore.datastore()

    val ticker = if (showProgressBar()) new FiniteProgressBar("Importing", lineCount(put.in())) else NoopTicker
    val attachmentDir = getOrCreateAttachmentDir(put.in())
    val f = createJSStream(put.in())
      .map(stripRevAndPrivateFields)
      .log("putDb", _.fields("_id"))
      .mapAsyncUnordered(put.threads()) { js =>
        val id = js.fields("_id").convertTo[String]
        val g = put.dbType.runtimeClass match {
          case x if x == classOf[WhiskEntity]     => putEntity(js, attachmentDir, entityStore)
          case x if x == classOf[WhiskActivation] => activationStore.put(new AnyActivation(js))
          case x if x == classOf[WhiskAuth]       => authStore.put(new AnyAuth(js))
        }
        g.map(_ => State.SUCCESS)
          .recover {
            case _: DocumentConflictException =>
              log.warn("Document exists [{}]", id)
              State.EXISTS
            case e =>
              log.warn(s"Error while adding [$id]", e)
              State.ERROR
          }
      }
      .via(tick(ticker))
      .runWith(streamResultSink)

    f.onComplete(_ => ticker.close())
    f.map { acc =>
      val r = acc.toResult()
      if (r.ok) Right(CommandMessages.putDocs(r.success))
      else Left(IllegalState(CommandMessages.putDocsFailed(success = r.success, failed = r.failed, exists = r.exists)))
    }
  }

  private def putEntity(js: JsObject, attachmentDir: File, entityStore: ArtifactStore[WhiskEntity])(
    implicit transid: TransactionId,
    ec: ExecutionContext): Future[DocInfo] = {
    if (isAction(js)) {
      val action = WhiskAction.serdes.read(js)
      action.exec match {
        case _ @CodeExecAsAttachment(_, Attached(_, contentType, _, _), _) =>
          val attachmentSource = getAttachmentSource(action, attachmentDir)
          entityStore
            .putAndAttach(action, WhiskAction.attachmentUpdater, contentType, attachmentSource, None)
            .map(_._1)
        //TODO CodeExecAsString => Attachment
        //TODO BlackBoxAction => Attachment
        case _ => entityStore.put(action)
      }
    } else entityStore.put(AnyEntity(js))
  }

  private def getAttachmentSource(action: WhiskAction, attachmentDir: File) = {
    val file = getAttachmentFile(attachmentDir, action)
    require(file.exists(), s"Attachment file ${file.getAbsolutePath} missing for action $action")
    FileIO.fromPath(file.toPath)
  }

  private def streamResultSink = {
    Sink.fold[ResultAccumulator, State.ResultState](new ResultAccumulator) { (acc, s) =>
      acc.update(s)
    }
  }

  private def createOutputSink() =
    get.out
      .map(f => FileIO.toPath(f.toPath))
      .getOrElse(StreamConverters.fromOutputStream(() => new CloseShieldOutputStream(System.out)))

  private def tick[T](ticker: Ticker) = {
    Flow[T].wireTap(Sink.foreach(_ => ticker.tick()))
  }

  private def getProgressBar(store: StreamingArtifactStore, action: String)(implicit transid: TransactionId): Ticker = {
    val count = Await.result(store.getCount(), 30.seconds)
    count match {
      case Some(n) => new FiniteProgressBar(action, n)
      case None    => new InfiniteProgressBar(action)
    }
  }

  private object State extends Enumeration {
    type ResultState = Value
    val SUCCESS, EXISTS, ERROR = Value
  }

  private class ResultAccumulator(private var success: Long = 0,
                                  private var failed: Long = 0,
                                  private var exists: Long = 0) {
    import State._
    def update(r: ResultState): ResultAccumulator = {
      r match {
        case SUCCESS => success += 1
        case EXISTS  => exists += 1
        case ERROR   => failed += 1
      }
      this
    }

    def toResult(): StreamResult = StreamResult(success, failed, exists)
  }

  case class StreamResult(success: Long, failed: Long, exists: Long) {
    def ok: Boolean = failed == 0 && exists == 0
  }
}

object DbCommand {
  private val log = LoggerFactory.getLogger(getClass.getName)
  private[database] var ignoreAttachmentErrors: Boolean = false

  def createStreamingStore[T <: DocumentSerializer](classTag: ClassTag[T], store: ArtifactStore[_])(
    implicit system: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): StreamingArtifactStore = {
    implicit val tag = classTag
    store match {
      case _: CouchDbRestStore[_]    => CouchDBStreamingStoreProvider.makeStore[T]()
      case s: StreamingArtifactStore => s
      case _                         => throw new IllegalArgumentException(s"Unsupported ArtifactStore $store")
    }
  }

  def getStore[D](classTag: ClassTag[D])(implicit system: ActorSystem,
                                         logging: Logging,
                                         materializer: ActorMaterializer): ArtifactStore[_] = {
    if (classTag.runtimeClass == classOf[WhiskEntity]) WhiskEntityStore.datastore()
    else if (classTag.runtimeClass == classOf[WhiskAuth]) WhiskAuthStore.datastore()
    else if (classTag.runtimeClass == classOf[WhiskActivation]) WhiskActivationStore.datastore()
    else throw new IllegalArgumentException(s"Unsupported ArtifactStore $classTag")
  }

  def createJSStream(file: File, maxLineLength: ByteSize = 10.MB): Source[JsObject, Future[IOResult]] = {
    //Use a large look ahead buffer as actions can be big
    FileIO
      .fromPath(file.toPath)
      .via(Framing.delimiter(ByteString("\n"), maxLineLength.toBytes.toInt))
      .map(_.utf8String)
      .map(stripEndComma)
      .map(JsonParser(_).asJsObject)
  }

  //Some dumps create an array of json objects where each line ends with ','
  //To avoid failure in parsing later the comma would be stripped
  private def stripEndComma(s: String) = if (s.last == ',') s.substring(0, s.length - 1) else s

  def jsToStringLine(js: JsObject): ByteString = ByteString(js.compactPrint + Properties.lineSeparator)

  /**
   * Prior to #3164 the "entityType" property was not present. So populate the "entityType" proper if
   * importing document records from older setups
   *
   * @return js document json with "entityType" property
   */
  def withEntityType(js: JsObject): JsObject = {
    if (js.fields.contains("entityType")) js
    else {
      getEntityType(js) match {
        case Some(entityType) => JsObject(js.fields + ("entityType" -> JsString(entityType)))
        case None =>
          log.warn("entityType cannot be determined for {}", js)
          js
      }
    }
  }

  /**
   * Infers the "entityType" of entities based on convention.
   */
  def getEntityType(js: JsObject): Option[String] = {
    if (js.fields.contains("binding")) Some("package")
    else if (js.fields.contains("exec")) Some("action")
    else if (js.fields.contains("parameters")) Some("trigger")
    else if (js.fields.contains("trigger")) Some("rule")
    else if (js.fields.contains("entityType")) Some(js.fields("entityType").convertTo[String])
    else None
  }

  def isAction(js: JsObject): Boolean = getEntityType(js).contains("action")

  /**
   * Filters out system generated fields which start with '_' except '_id
   */
  def stripRevAndPrivateFields(js: JsObject): JsObject = {
    //CouchDB json may include private fields like _attachments. Such fields need to be dropped
    //prior to put
    JsObject(js.fields.filter { case (k, _) => !k.startsWith("_") || k == "_id" })
  }

  def lineCount(file: File): Long = Files.lines(file.toPath).count()

  val attachmentFileName = "attachment"

  def hasAttachment(js: JsObject): Boolean = {
    JsHelpers.getFieldPath(js, "exec", "code") match {
      case Some(_: JsObject) => true
      case _                 => false
    }
  }

  def getOrCreateAttachmentDir(file: File): File = {
    val dir = file.getParentFile
    val attachmentDir = new File(dir, "attachments")
    FileUtils.forceMkdir(attachmentDir)
    attachmentDir
  }

  def getAttachmentFile(basedir: File, action: WhiskAction): File = {
    val path = action
      .fullyQualifiedName(false)
      .fullPath
      .addPath(EntityName(attachmentFileName))
    val names = path.namespace.split(EntityPath.PATHSEP)
    FileUtils.getFile(basedir, names: _*)
  }

  private def createFile(file: File): File = {
    //TODO Review if this has any issue with concurrent creation
    FileUtils.forceMkdirParent(file)
    file
  }

  private val unusedSubject = Subject()
  private val unusedParams = Parameters()
  private val unusedPath = EntityPath("not used")
  private val unusedName = EntityName("not used")
  private val unusedActivationId = ActivationId.generate()
  private val unusedInstant = Instant.now()
  private val unusedVersion = SemVer()

  //Need to use these proxy classes to enable passing in the js without
  //needing to convert them to actual entity type. Later we should look
  //into supporting ArtifactStore.putRaw kind of method to directly persist js
  class AnyAuth(js: JsObject) extends WhiskAuth(unusedSubject, Set.empty) {
    override def toDocumentRecord: JsObject = js
  }

  case class AnyEntity(js: JsObject,
                       version: SemVer = unusedVersion,
                       publish: Boolean = false,
                       namespace: EntityPath = unusedPath,
                       annotations: Parameters = unusedParams)
      extends WhiskEntity(EntityName("not used"), "not used") {
    override def toDocumentRecord: JsObject = withEntityType(js)
    override def toJson: JsObject = toDocumentRecord
  }

  private class AnyActivation(js: JsObject)
      extends WhiskActivation(unusedPath, unusedName, unusedSubject, unusedActivationId, unusedInstant, unusedInstant) {
    override def toDocumentRecord: JsObject = js
  }
}
