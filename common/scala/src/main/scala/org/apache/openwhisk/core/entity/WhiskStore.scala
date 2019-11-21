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

package org.apache.openwhisk.core.entity

import java.time.Instant

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.RootJsonFormat
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.database.ArtifactStore
import org.apache.openwhisk.core.database.ArtifactStoreProvider
import org.apache.openwhisk.core.database.DocumentRevisionProvider
import org.apache.openwhisk.core.database.DocumentSerializer
import org.apache.openwhisk.core.database.StaleParameter
import org.apache.openwhisk.spi.SpiLoader
import pureconfig._
import pureconfig.generic.auto._
import scala.reflect.classTag

object types {
  type AuthStore = ArtifactStore[WhiskAuth]
  type EntityStore = ArtifactStore[WhiskEntity]
}

case class DBConfig(subjectsDdoc: String, actionsDdoc: String, activationsDdoc: String, activationsFilterDdoc: String)

protected[core] trait WhiskDocument extends DocumentSerializer with DocumentRevisionProvider {

  /**
   * Gets unique document identifier for the document.
   */
  protected def docid: DocId

  /**
   * Creates DocId from the unique document identifier and the
   * document revision if one exists.
   */
  protected[core] final def docinfo: DocInfo = DocInfo(docid, rev)

  /**
   * The representation as JSON, e.g. for REST calls. Does not include id/rev.
   */
  def toJson: JsObject

  /**
   * Database JSON representation. Includes id/rev when appropriate. May
   * differ from `toJson` in exceptional cases.
   */
  override def toDocumentRecord: JsObject = {
    val id = docid.id
    val revOrNull = rev.rev

    // Building up the fields.
    val base = this.toJson.fields
    val withId = base + ("_id" -> JsString(id))
    val withRev = if (revOrNull == null) withId else { withId + ("_rev" -> JsString(revOrNull)) }
    JsObject(withRev)
  }
}

object WhiskAuthStore {
  implicit val docReader = WhiskDocumentReader

  def datastore()(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader.get[ArtifactStoreProvider].makeStore[WhiskAuth]()
}

object WhiskEntityStore {

  def datastore()(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskEntity]()(
        classTag[WhiskEntity],
        WhiskEntityJsonFormat,
        WhiskDocumentReader,
        system,
        logging,
        materializer)
}

object WhiskActivationStore {
  implicit val docReader = WhiskDocumentReader

  def datastore()(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader.get[ArtifactStoreProvider].makeStore[WhiskActivation](useBatching = true)
}

/**
 * A class to type the design doc and view within a database.
 *
 * @param ddoc the design document
 * @param view the view name within the design doc
 */
protected[core] case class View(ddoc: String, view: String) {

  /** The name of the table to query. */
  val name = s"$ddoc/$view"
}

/**
 * This object provides some utilities that query the whisk datastore.
 * The datastore is assumed to have views (pre-computed joins or indexes)
 * for each of the whisk collection types. Entities may be queries by
 * [path, date] where
 *
 * - path is the either root namespace for an entity (the owning subject
 *   or organization) or a packaged qualified namespace,
 * - date is the date the entity was created or last updated, or for activations
 *   this is the start of the activation. See EntityRecord for the last updated
 *   property.
 *
 * This order is important because the datastore is assumed to sort lexicographically
 * and hence either the fields are ordered according to the set of queries that are
 * desired: all entities in a namespace (by type), further refined by date, further
 * refined by name.
 *
 */
object WhiskQueries {
  val TOP = "\ufff0"

  /** The view name for the collection, within the design document. */
  def view(ddoc: String, collection: String) = new View(ddoc, collection)

  /** The view name for the collection, within the design document. */
  def entitiesView(collection: String) = new View(entitiesDesignDoc, collection)

  /** The db configuration. */
  protected[entity] val dbConfig = loadConfigOrThrow[DBConfig](ConfigKeys.db)

  /** The design document to use for queries. */
  private val entitiesDesignDoc = dbConfig.actionsDdoc
}

trait WhiskEntityQueries[T] {
  val collectionName: String
  val serdes: RootJsonFormat[T]
  import WhiskQueries._

  /** The view name for the collection, within the design document. */
  lazy val view: View = WhiskQueries.entitiesView(collection = collectionName)

  /**
   * Queries the datastore for records from a specific collection (i.e., type) matching
   * the given path (which should be one namespace, or namespace + package name).
   *
   * @return list of records as JSON object if docs parameter is false, as Left
   *         and a list of the records as their type T if including the full record, as Right
   */
  def listCollectionInNamespace[A <: WhiskEntity](
    db: ArtifactStore[A],
    path: EntityPath, // could be a namesapce or namespace + package name
    skip: Int,
    limit: Int,
    includeDocs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No,
    viewName: View = view)(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    val convert = if (includeDocs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    val startKey = List(path.asString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(path.asString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, viewName, startKey, endKey, skip, limit, reduce = false, stale, convert)
  }

  /**
   * Queries the datastore for the records count in a specific collection (i.e., type) matching
   * the given path (which should be one namespace, or namespace + package name).
   *
   * @return JSON object with a single key, the collection name, and a value equal to the view length
   */
  def countCollectionInNamespace[A <: WhiskEntity](
    db: ArtifactStore[A],
    path: EntityPath, // could be a namespace or namespace + package name
    skip: Int,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No,
    viewName: View = view)(implicit transid: TransactionId): Future[JsObject] = {
    implicit val ec = db.executionContext
    val startKey = List(path.asString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(path.asString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    db.count(viewName.name, startKey, endKey, skip, stale) map { count =>
      JsObject(collectionName -> JsNumber(count))
    }
  }

  protected[entity] def query[A <: WhiskEntity](
    db: ArtifactStore[A],
    view: View,
    startKey: List[Any],
    endKey: List[Any],
    skip: Int,
    limit: Int,
    reduce: Boolean,
    stale: StaleParameter = StaleParameter.No,
    convert: Option[JsObject => Try[T]])(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    implicit val ec = db.executionContext
    val includeDocs = convert.isDefined
    db.query(view.name, startKey, endKey, skip, limit, includeDocs, descending = true, reduce, stale) map { rows =>
      convert map { fn =>
        Right(rows flatMap { row =>
          fn(row.fields("doc").asJsObject) toOption
        })
      } getOrElse {
        Left(rows flatMap { normalizeRow(_, reduce) toOption })
      }
    }
  }

  /**
   * Normalizes the raw JsObject response from the datastore since the
   * response differs in the case of a reduction.
   */
  protected def normalizeRow(row: JsObject, reduce: Boolean) = Try {
    if (!reduce) {
      row.fields("value").asJsObject
    } else row
  }
}
