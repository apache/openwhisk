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
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{FileIO, Flow, Framing, Keep, Sink, Source, StreamConverters}
import akka.stream.{ActorMaterializer, IOResult}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.output.CloseShieldOutputStream
import org.rogach.scallop.{ScallopConfBase, Subcommand}
import org.slf4j.LoggerFactory
import spray.json.{JsObject, JsonParser, ParserInput}
import whisk.common.{Logging, TransactionId}
import whisk.core.cli.{CommandError, CommandMessages, IllegalState, NoopTicker, ProgressTicker, Ticker, WhiskCommand}
import whisk.core.database.DbCommand._
import whisk.core.entity._
import whisk.core.entity.size._

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.{classTag, ClassTag}
import scala.util.Properties

class DbCommand extends Subcommand("db") with WhiskCommand {
  descr("work with dbs")

  private val log = LoggerFactory.getLogger(getClass.getName)

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
    val ticker = if (get.out.isDefined && showProgressBar()) new ProgressTicker else NoopTicker
    val outputSink = Flow[JsObject]
      .map(jsToStringLine)
      .via(tick(ticker))
      .toMat(createSink())(Keep.right)
    val store = DbCommand.createStreamingStore(get.dbType)

    val f = store.getAll[IOResult](outputSink)
    f.onComplete(_ => ticker.close())
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

    val ticker = if (showProgressBar()) new ProgressTicker else NoopTicker
    val source = createJSStream(put.in())
    val f = source
      .mapAsyncUnordered(4) { js =>
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

  private def showProgressBar(): Boolean = true

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
  def createStreamingStore[D <: DocumentSerializer](classTag: ClassTag[D])(
    implicit system: ActorSystem,
    logging: Logging,
    materializer: ActorMaterializer): StreamingArtifactStore = {
    implicit val tag = classTag
    getStoreProvider().makeStore[D]()
  }

  def retainProperFields(js: JsObject): JsObject =
    JsObject(js.fields.filterKeys(key => key == "_id" || !key.startsWith("_")))

  def getStoreProvider(): StreamingArtifactStoreProvider = {
    val storeClass = ConfigFactory.load().getString("whisk.spi.ArtifactStoreProvider") + "$"
    if (storeClass == CouchDbStoreProvider.getClass.getName)
      CouchDBStreamingStoreProvider
    else
      throw new IllegalArgumentException(s"Unsupported ArtifactStore $storeClass")
  }

  def createJSStream(file: File, maxLineLength: ByteSize = 10.MB): Source[JsObject, Future[IOResult]] = {
    //Use a large look ahead buffer as actions can be big
    FileIO
      .fromPath(file.toPath)
      .via(Framing.delimiter(ByteString("\n"), maxLineLength.toBytes.toInt))
      .map(bs => JsonParser(ParserInput.apply(bs.toArray)).asJsObject)
  }

  def jsToStringLine(js: JsObject): ByteString = ByteString(js.compactPrint + Properties.lineSeparator)

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
  private class AnyAuth(js: JsObject) extends WhiskAuth(unusedSubject, Set.empty) {
    override def toDocumentRecord: JsObject = retainProperFields(js)
  }

  private case class AnyEntity(js: JsObject,
                               version: SemVer = unusedVersion,
                               publish: Boolean = false,
                               namespace: EntityPath = unusedPath,
                               annotations: Parameters = unusedParams)
      extends WhiskEntity(EntityName("not used"), "not used") {
    override def toDocumentRecord: JsObject = retainProperFields(js)
    override def toJson: JsObject = ???
    //TODO entityType handling
  }

  private class AnyActivation(js: JsObject)
      extends WhiskActivation(unusedPath, unusedName, unusedSubject, unusedActivationId, unusedInstant, unusedInstant) {
    override def toDocumentRecord: JsObject = retainProperFields(js)
  }
}
