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
import org.apache.commons.io.output.CloseShieldOutputStream
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import org.slf4j.LoggerFactory
import spray.json.{JsObject, JsString, JsonParser}
import whisk.common.{Logging, TransactionId}
import whisk.core.cli.ConsoleUtil._
import whisk.core.cli._
import whisk.core.database.DbCommand._
import whisk.core.entity._
import whisk.core.entity.size._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.{classTag, ClassTag}
import scala.util.Properties

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
    val outputSink = Flow[JsObject]
      .map(jsToStringLine)
      .via(tick(ticker))
      .toMat(createSink())(Keep.right)

    val f = store.getAll[IOResult](outputSink)
    f.onComplete { _ =>
      ticker.close()
      artifactStore.shutdown()
    }
    f.map {
      case (count, r) =>
        if (r.wasSuccessful)
          Right(get.out.map(CommandMessages.dbContentToFile(count, _)).getOrElse(""))
        else throw r.getError
    }
  }

  def putDBContents()(implicit system: ActorSystem,
                      logging: Logging,
                      materializer: ActorMaterializer,
                      transid: TransactionId,
                      ec: ExecutionContext): Future[Either[CommandError, String]] = {
    import spray.json.DefaultJsonProtocol._

    val authStore = WhiskAuthStore.datastore()
    val entityStore = WhiskEntityStore.datastore()
    val activationStore = WhiskActivationStore.datastore()

    val ticker = if (showProgressBar()) new FiniteProgressBar("Importing", lineCount(put.in())) else NoopTicker
    val source = createJSStream(put.in())
    val f = source
      .mapAsyncUnordered(put.threads()) { js =>
        val id = js.fields("_id").convertTo[String]
        val g = put.dbType.runtimeClass match {
          case x if x == classOf[WhiskEntity]     => entityStore.put(AnyEntity(js))
          case x if x == classOf[WhiskActivation] => activationStore.put(new AnyActivation(js))
          case x if x == classOf[WhiskAuth]       => authStore.put(new AnyAuth(js))
        }
        g.map(_ => PutResultState.SUCCESS)
          .recover {
            case _: DocumentConflictException =>
              log.warn("Document exists [{}]", id)
              PutResultState.EXISTS
            case e =>
              log.warn(s"Error while adding [$id]", e)
              PutResultState.ERROR
          }
      }
      .via(tick(ticker))
      .runWith(Sink.fold[PutResultAccumulator, PutResultState.PutResultState](new PutResultAccumulator) { (acc, s) =>
        acc.update(s)
      })

    f.onComplete(_ => ticker.close())
    f.map { acc =>
      val r = acc.toResult()
      if (r.ok) Right(CommandMessages.putDocs(r.success))
      else Left(IllegalState(CommandMessages.putDocsFailed(success = r.success, failed = r.failed, exists = r.exists)))
    }
  }

  private def createSink() =
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

  private object PutResultState extends Enumeration {
    type PutResultState = Value
    val SUCCESS, EXISTS, ERROR = Value
  }

  private class PutResultAccumulator(private var success: Long = 0,
                                     private var failed: Long = 0,
                                     private var exists: Long = 0) {
    import PutResultState._
    def update(r: PutResultState): PutResultAccumulator = {
      r match {
        case SUCCESS => success += 1
        case EXISTS  => exists += 1
        case ERROR   => failed += 1
      }
      this
    }

    def toResult(): PutResult = PutResult(success, failed, exists)
  }

  case class PutResult(success: Long, failed: Long, exists: Long) {
    def ok: Boolean = failed == 0 && exists == 0
  }
}

object DbCommand {
  private val log = LoggerFactory.getLogger(getClass.getName)

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

  /**
   * Filters out system generated fields which start with '_' except '_id
   */
  def retainProperFields(js: JsObject): JsObject =
    JsObject(js.fields.filterKeys(key => key == "_id" || !key.startsWith("_")))

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
  def entityWithEntityType(js: JsObject): JsObject = {
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
    else None
  }

  def lineCount(file: File): Long = Files.lines(file.toPath).count()

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
    override def toDocumentRecord: JsObject = retainProperFields(js)
  }

  case class AnyEntity(js: JsObject,
                       version: SemVer = unusedVersion,
                       publish: Boolean = false,
                       namespace: EntityPath = unusedPath,
                       annotations: Parameters = unusedParams)
      extends WhiskEntity(EntityName("not used"), "not used") {
    override def toDocumentRecord: JsObject = retainProperFields(entityWithEntityType(js))
    override def toJson: JsObject = toDocumentRecord
  }

  private class AnyActivation(js: JsObject)
      extends WhiskActivation(unusedPath, unusedName, unusedSubject, unusedActivationId, unusedInstant, unusedInstant) {
    override def toDocumentRecord: JsObject = retainProperFields(js)
  }
}
