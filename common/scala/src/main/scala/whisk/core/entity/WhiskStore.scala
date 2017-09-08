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
  def requiredProperties =
    Map(
      dbProvider -> null,
      dbProtocol -> null,
      dbUsername -> null,
      dbPassword -> null,
      dbHost -> null,
      dbPort -> null,
      dbAuths -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging) =
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
      dbWhisk -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging) =
    SpiLoader
      .get[ArtifactStoreProvider]
      .makeStore[WhiskEntity](config, _.dbWhisk)(WhiskEntityJsonFormat, system, logging)

}

object WhiskActivationStore {
  def requiredProperties =
    Map(
      dbProvider -> null,
      dbProtocol -> null,
      dbUsername -> null,
      dbPassword -> null,
      dbHost -> null,
      dbPort -> null,
      dbActivations -> null)

  def datastore(config: WhiskConfig)(implicit system: ActorSystem, logging: Logging) =
    SpiLoader.get[ArtifactStoreProvider].makeStore[WhiskActivation](config, _.dbActivations)
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
  val WHISKVIEW = "whisks"
  val ALL = "all"
  val ENTITIES = "entities"

  /**
   * Determines the view name for the collection. There are two cases: a view
   * that is namespace specific, or namespace agnostic..
   */
  def viewname(collection: String, allNamespaces: Boolean = false): String = {
    if (!allNamespaces) {
      s"$WHISKVIEW/$collection"
    } else s"$WHISKVIEW/$collection-all"
  }

  /**
   * Queries the datastore for all entities in a namespace, and converts the list of entities
   * to a map that collects the entities by their type.
   */
  def listAllInNamespace[A <: WhiskEntity](
    db: ArtifactStore[A],
    namespace: EntityPath,
    includeDocs: Boolean,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId): Future[Map[String, List[JsObject]]] = {
    implicit val ec = db.executionContext
    val startKey = List(namespace.toString)
    val endKey = List(namespace.toString, TOP)
    db.query(viewname(ALL), startKey, endKey, 0, 0, includeDocs, descending = true, reduce = false, stale = stale) map {
      _ map { row =>
        val value = row.fields("value").asJsObject
        val JsString(collection) = value.fields("collection")
        (collection, JsObject(value.fields.filterNot { _._1 == "collection" }))
      } groupBy { _._1 } mapValues { _.map(_._2) }
    }
  }

  /**
   * Queries the datastore for all entities without activations in a namespace, and converts the list of entities
   * to a map that collects the entities by their type.
   */
  def listEntitiesInNamespace[A <: WhiskEntity](
    db: ArtifactStore[A],
    namespace: EntityPath,
    includeDocs: Boolean,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId): Future[Map[String, List[JsObject]]] = {
    implicit val ec = db.executionContext
    val startKey = List(namespace.toString)
    val endKey = List(namespace.toString, TOP)
    db.query(viewname(ENTITIES), startKey, endKey, 0, 0, includeDocs, descending = true, reduce = false, stale = stale) map {
      _ map { row =>
        val value = row.fields("value").asJsObject
        val JsString(collection) = value.fields("collection")
        (collection, JsObject(value.fields.filterNot { _._1 == "collection" }))
      } groupBy { _._1 } mapValues { _.map(_._2) }
    }
  }

  def listCollectionInAnyNamespace[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    collection: String,
    skip: Int,
    limit: Int,
    reduce: Boolean,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No,
    convert: Option[JsObject => Try[T]])(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    val startKey = List(since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, viewname(collection, true), startKey, endKey, skip, limit, reduce, stale, convert)
  }

  def listCollectionInNamespace[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    collection: String,
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No,
    convert: Option[JsObject => Try[T]])(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    val startKey = List(namespace.toString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(namespace.toString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, viewname(collection), startKey, endKey, skip, limit, reduce = false, stale, convert)
  }

  def listCollectionByName[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    collection: String,
    namespace: EntityPath,
    name: EntityName,
    skip: Int,
    limit: Int,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No,
    convert: Option[JsObject => Try[T]])(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    val startKey = List(namespace.addPath(name).toString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(namespace.addPath(name).toString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, viewname(collection), startKey, endKey, skip, limit, reduce = false, stale, convert)
  }

  private def query[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    skip: Int,
    limit: Int,
    reduce: Boolean,
    stale: StaleParameter = StaleParameter.No,
    convert: Option[JsObject => Try[T]])(implicit transid: TransactionId): Future[Either[List[JsObject], List[T]]] = {
    implicit val ec = db.executionContext
    val includeDocs = convert.isDefined
    db.query(view, startKey, endKey, skip, limit, includeDocs, true, reduce, stale) map { rows =>
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

trait WhiskEntityQueries[T] {
  val collectionName: String
  val serdes: RootJsonFormat[T]

  def listCollectionInAnyNamespace[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    skip: Int,
    limit: Int,
    docs: Boolean = false,
    reduce: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId) = {
    val convert = if (docs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    WhiskEntityQueries.listCollectionInAnyNamespace(
      db,
      collectionName,
      skip,
      limit,
      reduce,
      since,
      upto,
      stale,
      convert)
  }

  def listCollectionInNamespace[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    namespace: EntityPath,
    skip: Int,
    limit: Int,
    docs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId) = {
    val convert = if (docs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    WhiskEntityQueries.listCollectionInNamespace(
      db,
      collectionName,
      namespace,
      skip,
      limit,
      since,
      upto,
      stale,
      convert)
  }

  def listCollectionByName[A <: WhiskEntity, T](
    db: ArtifactStore[A],
    namespace: EntityPath,
    name: EntityName,
    skip: Int,
    limit: Int,
    docs: Boolean = false,
    since: Option[Instant] = None,
    upto: Option[Instant] = None,
    stale: StaleParameter = StaleParameter.No)(implicit transid: TransactionId) = {
    val convert = if (docs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    WhiskEntityQueries.listCollectionByName(
      db,
      collectionName,
      namespace,
      name,
      skip,
      limit,
      since,
      upto,
      stale,
      convert)
  }
}
