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

package whisk.core.entity

import java.time.Instant

import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import spray.json.JsObject
import spray.json.JsString
import spray.json.RootJsonFormat
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.dbActivations
import whisk.core.WhiskConfig.dbAuths
import whisk.core.WhiskConfig.dbHost
import whisk.core.WhiskConfig.dbPassword
import whisk.core.WhiskConfig.dbPort
import whisk.core.WhiskConfig.dbProtocol
import whisk.core.WhiskConfig.dbProvider
import whisk.core.WhiskConfig.dbUsername
import whisk.core.WhiskConfig.dbWhisk
import whisk.core.WhiskConfig.dbWhiskDesignDoc
import whisk.core.WhiskConfig.{dbActivationsDesignDoc, dbActivationsFilterDesignDoc}
import whisk.core.database.ArtifactStore
import whisk.core.database.ArtifactStoreProvider
import whisk.core.database.DocumentRevisionProvider
import whisk.core.database.DocumentSerializer
import whisk.core.database.StaleParameter
import whisk.spi.SpiLoader

package object types {
  type AuthStore = ArtifactStore[WhiskAuth]
  type EntityStore = ArtifactStore[WhiskEntity]
  type ActivationStore = ArtifactStore[WhiskActivation]
}

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

  def requiredProperties =
    Map(
      dbProvider -> null,
      dbProtocol -> null,
      dbUsername -> null,
      dbPassword -> null,
      dbHost -> null,
      dbPort -> null,
      dbAuths -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader.get[ArtifactStoreProvider].makeStore[WhiskAuth](config, _.dbAuths)
}

object WhiskEntityStore {
  def requiredProperties =
    Map(
      dbProvider -> null,
      dbProtocol -> null,
      dbUsername -> null,
      dbPassword -> null,
      dbHost -> null,
      dbPort -> null,
      dbWhisk -> null,
      dbWhiskDesignDoc -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskEntity](config, _.dbWhisk)(
        WhiskEntityJsonFormat,
        WhiskDocumentReader,
        system,
        logging,
        materializer)
}

object WhiskActivationStore {
  implicit val docReader = WhiskDocumentReader
  def requiredProperties =
    Map(
      dbProvider -> null,
      dbProtocol -> null,
      dbUsername -> null,
      dbPassword -> null,
      dbHost -> null,
      dbPort -> null,
      dbActivations -> null,
      dbActivationsDesignDoc -> null,
      dbActivationsFilterDesignDoc -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging, materializer: ActorMaterializer) =
    SpiLoader.get[ArtifactStoreProvider].makeStore[WhiskActivation](config, _.dbActivations, true)
}

/**
 * A class to type the design doc and view within a database.
 *
 * @param ddoc the design document
 * @param view the view name within the design doc
 */
protected[core] class View(ddoc: String, view: String) {

  /** The name of the table to query. */
  val name = s"$ddoc/$view"
}

/**
 * This object provides some utilities that query the whisk datastore.
 * The datastore is assumed to have views (pre-computed joins or indexes)
 * for each of the whisk collection types. Entities may be queries by
 * [namespace, date, name] where
 *
 * - namespace is the either root namespace for an entity (the owning subject
 *   or organization) or a packaged qualified namespace,
 * - date is the date the entity was created or last updated, or for activations
 *   this is the start of the activation. See EntityRecord for the last updated
 *   property.
 * - name is the actual name of the entity (its simple name, not qualified by
 *   a package name)
 *
 * This order is important because the datastore is assumed to sort lexicographically
 * and hence either the fields are ordered according to the set of queries that are
 * desired: all entities in a namespace (by type), further refined by date, further
 * refined by name.
 *
 * In addition, for entities that may be queried across namespaces (currently
 * packages only), there must be a view which omits the namespace from the key,
 * as in [date, name] only. This permits the same queries that work for a collection
 * in a namespace to also work across namespaces.
 *
 * It is also assumed that the "-all" views implement a meaningful reduction for the
 * collection. Namely, the packages-all view will reduce the results to packages that
 * are public.
 *
 * The names of the views are assumed to be either the collection name, or
 * the collection name suffixed with "-all" per the method viewname. All of
 * the required views are installed by wipeTransientDBs.sh.
 */
object WhiskEntityQueries {
  val TOP = "\ufff0"

  /** The design document to use for queries. */
  // FIXME: reading the design doc from sys.env instead of a canonical property reader
  // because WhiskConfig requires a logger, which requires an actor system, neither of
  // which are readily available here; rather than introduce significant refactoring,
  // defer this fix until WhiskConfig is refactored itself, which is planned to introduce
  // type safe properties
  val designDoc = WhiskConfig.readFromEnv(dbWhiskDesignDoc).getOrElse("whisks.v2")

  /** The view name for the collection, within the design document. */
  def view(ddoc: String = designDoc, collection: String) = new View(ddoc, collection)

  /**
   * Name of view in design-doc that lists all entities in that views regardless of types.
   * This is uses in the namespace API, and also in tests to check preconditions.
   */
  val viewAll: View = view(collection = "all")

  /**
   * Queries the datastore for all entities in a namespace, and converts the list of entities
   * to a map that collects the entities by their type. This method applies to only to the main
   * asset database, not the activations records because it does not offer the required view.
   */
  def listAllInNamespace[A <: WhiskEntity](
    db: ArtifactStore[A],
    namespace: EntityName,
    includeDocs: Boolean,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId): Future[Map[String, List[JsObject]]] = {
    implicit val ec = db.executionContext
    val startKey = List(namespace.asString)
    val endKey = List(namespace.asString, TOP)
    db.query(viewAll.name, startKey, endKey, 0, 0, includeDocs, descending = true, reduce = false, stale = stale) map {
      _ map { row =>
        val value = row.fields("value").asJsObject
        val JsString(collection) = value.fields("collection")
        (collection, JsObject(value.fields.filterNot { _._1 == "collection" }))
      } groupBy { _._1 } mapValues { _.map(_._2) }
    }
  }
}

trait WhiskEntityQueries[T] {
  val collectionName: String
  val serdes: RootJsonFormat[T]
  import WhiskEntityQueries._

  /** The view name for the collection, within the design document. */
  lazy val view: View = WhiskEntityQueries.view(collection = collectionName)

  /**
   * Queries the datastore for records from a specific collection (i.e., type) matching
   * the given path (which should be one namespace, or namespace + package name).
   *
   * @return list of records as JSON object if docs parameter is false, as Left
   *         and a list of the records as their type T if including the full record, as Right
   */
  def listCollectionInNamespace[A <: WhiskEntity](db: ArtifactStore[A],
                                                  path: EntityPath, // could be a namesapce or namespace + package name
                                                  skip: Int,
                                                  limit: Int,
                                                  includeDocs: Boolean = false,
                                                  since: Option[Instant] = None,
                                                  upto: Option[Instant] = None,
                                                  stale: StaleParameter = StaleParameter.No)(
    implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    val convert = if (includeDocs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    val startKey = List(path.asString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(path.asString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, view, startKey, endKey, skip, limit, reduce = false, stale, convert)
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
    db.query(view.name, startKey, endKey, skip, limit, includeDocs, true, reduce, stale) map { rows =>
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
