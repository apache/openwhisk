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

package org.apache.openwhisk.core.database.cosmosdb

import java.util.Collections

import com.microsoft.azure.cosmosdb.DataType.{Number, String}
import com.microsoft.azure.cosmosdb.IndexKind.Range
import com.microsoft.azure.cosmosdb.{PartitionKeyDefinition, SqlParameter, SqlParameterCollection, SqlQuerySpec}
import kamon.metric.MeasurementUnit
import org.apache.openwhisk.common.{LogMarkerToken, TransactionId, WhiskInstants}
import org.apache.openwhisk.core.database.ActivationHandler.NS_PATH
import org.apache.openwhisk.core.database.WhisksHandler.ROOT_NS
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBConstants.{alias, computed, deleted}
import org.apache.openwhisk.core.database.{
  ActivationHandler,
  DocumentHandler,
  SubjectHandler,
  UnsupportedQueryKeys,
  UnsupportedView,
  WhisksHandler
}
import org.apache.openwhisk.core.entity.WhiskQueries.TOP
import org.apache.openwhisk.utils.JsHelpers
import spray.json.{JsNumber, JsObject}

import scala.collection.JavaConverters._

private[cosmosdb] trait CosmosDBViewMapper {
  protected val NOTHING = ""
  protected val ALL_FIELDS = "*"
  protected val notDeleted = s"(NOT(IS_DEFINED(r.$deleted)) OR r.$deleted = false)"
  protected def handler: DocumentHandler

  def prepareQuery(ddoc: String,
                   viewName: String,
                   startKey: List[Any],
                   endKey: List[Any],
                   limit: Int,
                   includeDocs: Boolean,
                   descending: Boolean): SqlQuerySpec

  def prepareCountQuery(ddoc: String, viewName: String, startKey: List[Any], endKey: List[Any]): SqlQuerySpec

  def indexingPolicy: IndexingPolicy

  val partitionKeyDefn: PartitionKeyDefinition = {
    val defn = new PartitionKeyDefinition
    defn.setPaths(Collections.singletonList("/id"))
    defn
  }

  protected def checkKeys(startKey: List[Any], endKey: List[Any]): Unit = {
    require(startKey.nonEmpty)
    require(endKey.nonEmpty)
    require(startKey.head == endKey.head, s"First key should be same => ($startKey) - ($endKey)")
  }

  protected def prepareSpec(query: String, params: List[(String, Any)]): SqlQuerySpec = {
    val paramColl = new SqlParameterCollection
    params.foreach { case (k, v) => paramColl.add(new SqlParameter(k, v)) }

    new SqlQuerySpec(query, paramColl)
  }

  /**
   *  Records query related stats based on result returned and arguments passed
   *
   * @return an optional string representation of stats for logging purpose
   */
  def recordQueryStats(ddoc: String,
                       viewName: String,
                       descending: Boolean,
                       queryParams: SqlParameterCollection,
                       result: List[JsObject]): Option[String] = None
}

private[cosmosdb] abstract class SimpleMapper extends CosmosDBViewMapper {

  def prepareQuery(ddoc: String,
                   viewName: String,
                   startKey: List[Any],
                   endKey: List[Any],
                   limit: Int,
                   includeDocs: Boolean,
                   descending: Boolean): SqlQuerySpec = {
    checkKeys(startKey, endKey)

    val selectClause = select(ddoc, viewName, limit, includeDocs)
    val whereClause = where(ddoc, viewName, startKey, endKey)
    val orderField = orderByField(ddoc, viewName)
    val order = if (descending) "DESC" else NOTHING

    val query = s"SELECT $selectClause FROM root r WHERE $notDeleted AND ${whereClause._1} ORDER BY $orderField $order"

    prepareSpec(query, whereClause._2)
  }

  def prepareCountQuery(ddoc: String, viewName: String, startKey: List[Any], endKey: List[Any]): SqlQuerySpec = {
    checkKeys(startKey, endKey)

    val whereClause = where(ddoc, viewName, startKey, endKey)
    val query = s"SELECT TOP 1 VALUE COUNT(r) FROM root r WHERE ${whereClause._1}"

    prepareSpec(query, whereClause._2)
  }

  private def select(ddoc: String, viewName: String, limit: Int, includeDocs: Boolean): String = {
    val fieldClause = if (includeDocs) ALL_FIELDS else prepareFieldClause(ddoc, viewName)
    s"${top(limit)} $fieldClause"
  }

  private def top(limit: Int): String = {
    if (limit > 0) s"TOP $limit" else NOTHING
  }

  private def prepareFieldClause(ddoc: String, viewName: String) =
    CosmosDBUtil.prepareFieldClause(handler.fieldsRequiredForView(ddoc, viewName))

  protected def where(ddoc: String,
                      viewName: String,
                      startKey: List[Any],
                      endKey: List[Any]): (String, List[(String, Any)])

  protected def orderByField(ddoc: String, viewName: String): String
}

private[cosmosdb] object WhisksViewMapper extends SimpleMapper {
  private val NS = "namespace"
  private val ROOT_NS_C = s"$computed.$ROOT_NS"
  private val TYPE = "entityType"
  private val UPDATED = "updated"
  private val PUBLISH = "publish"
  private val BINDING = "binding"

  val handler = WhisksHandler

  override def indexingPolicy: IndexingPolicy =
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath(s"/$TYPE/?", Index(Range, String, -1)),
        IncludedPath(s"/$NS/?", Index(Range, String, -1)),
        IncludedPath(s"/$computed/$ROOT_NS/?", Index(Range, String, -1)),
        IncludedPath(s"/$UPDATED/?", Index(Range, Number, -1))))

  override protected def where(ddoc: String,
                               view: String,
                               startKey: List[Any],
                               endKey: List[Any]): (String, List[(String, Any)]) = {
    val entityType = WhisksHandler.getEntityTypeForDesignDoc(ddoc, view)
    val namespace = startKey.head

    val (vc, vcParams) =
      viewConditions(ddoc, view).map(q => (s"${q._1} AND", q._2)).getOrElse((NOTHING, Nil))

    val params = ("@entityType", entityType) :: ("@namespace", namespace) :: vcParams
    val baseCondition = s"$vc r.$TYPE = @entityType AND (r.$NS = @namespace OR r.$ROOT_NS_C = @namespace)"

    (startKey, endKey) match {
      case (_ :: Nil, _ :: `TOP` :: Nil) =>
        (baseCondition, params)

      case (_ :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (s"$baseCondition AND r.$UPDATED >= @since", ("@since", since) :: params)

      case (_ :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (s"$baseCondition AND (r.$UPDATED BETWEEN @since AND @upto)", ("@upto", upto) :: ("@since", since) :: params)

      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  private def viewConditions(ddoc: String, view: String): Option[(String, List[(String, Any)])] = {
    view match {
      case "packages-public" if ddoc.startsWith("whisks") =>
        Some(s"r.$PUBLISH = true AND (NOT IS_OBJECT(r.$BINDING) OR r.$BINDING = {})", Nil)
      case _ => None
    }
  }

  override protected def orderByField(ddoc: String, view: String): String = view match {
    case "actions" | "rules" | "triggers" | "packages" | "packages-public" if ddoc.startsWith("whisks") =>
      s"r.$UPDATED"
    case _ => throw UnsupportedView(s"$ddoc/$view")
  }

}
private[cosmosdb] object ActivationViewMapper extends SimpleMapper with WhiskInstants {
  import CosmosDBViewMapper._
  private val NS = "namespace"
  private val NS_WITH_PATH = s"$computed.$NS_PATH"
  private val START = "start"

  val handler = ActivationHandler

  override def indexingPolicy: IndexingPolicy =
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath(s"/$NS/?", Index(Range, String, -1)),
        IncludedPath(s"/$computed/$NS_PATH/?", Index(Range, String, -1)),
        IncludedPath(s"/$START/?", Index(Range, Number, -1)),
        IncludedPath(s"/$deleted/?", Index(Range, Number, -1))))

  override protected def where(ddoc: String,
                               view: String,
                               startKey: List[Any],
                               endKey: List[Any]): (String, List[(String, Any)]) = {
    val nsValue = startKey.head.asInstanceOf[String]
    view match {
      //whisks-filters ddoc uses namespace + invoking action path as first key
      case "activations" if ddoc.startsWith("whisks-filters") =>
        filterActivation(NS_WITH_PATH, nsValue, startKey, endKey)
      //whisks ddoc uses namespace as first key
      case "activations" if ddoc.startsWith("whisks") => filterActivation(NS, nsValue, startKey, endKey)
      case _                                          => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def filterActivation(nsKey: String,
                               nsValue: String,
                               startKey: List[Any],
                               endKey: List[Any]): (String, List[(String, Any)]) = {
    val params = ("@nsvalue", nsValue) :: Nil
    val filter = (startKey, endKey) match {
      case (_ :: Nil, _ :: `TOP` :: Nil) =>
        (s"r.$nsKey = @nsvalue", params)
      case (_ :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (s"r.$nsKey = @nsvalue AND r.$START >= @start", ("@start", since) :: params)
      case (_ :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (s"r.$nsKey = @nsvalue AND (r.$START BETWEEN @start AND @upto)", ("@upto", upto) :: ("@start", since) :: params)
      case _ => throw UnsupportedQueryKeys(s"$startKey, $endKey")
    }
    filter
  }

  override protected def orderByField(ddoc: String, view: String): String = view match {
    case "activations" if ddoc.startsWith("whisks") => s"r.$START"
    case _                                          => throw UnsupportedView(s"$ddoc/$view")
  }

  private val resultDeltaToken = createStatsToken("activations", "resultDelta", "activations")
  private val sinceDeltaToken = createStatsToken("activations", "sinceDelta", "activations")

  override def recordQueryStats(ddoc: String,
                                viewName: String,
                                descending: Boolean,
                                queryParams: SqlParameterCollection,
                                result: List[JsObject]): Option[String] = {
    val stat = if (viewName == "activations" && descending) {
      // Collect stats for the delta between
      // 1. now and start time of last activation
      // 2. now and start time as specific in query for `since` parameter
      // These stats would help in determining how much old activations are being queried for list query (used in activation poll)
      val uptoOpt = paramValue(queryParams, "upto", classOf[Number])
      val startOpt = paramValue(queryParams, "start", classOf[Number])

      // Result json has structure { id: "", "key": [], "value": {activation}}
      // So fetch value of start via `value.start` path
      val lastOpt = result.lastOption.flatMap(js => JsHelpers.getFieldPath(js, "value", "start"))

      (uptoOpt, startOpt, lastOpt) match {
        //Go for case which does not specify upto as that would be the case with poll based query
        case (None, Some(startFromQuery), Some(JsNumber(start))) =>
          val now = nowInMillis().toEpochMilli
          val resultStartDelta = (now - start.longValue).max(0)
          val queryStartDelta = (now - startFromQuery.longValue).max(0)
          resultDeltaToken.histogram.record(resultStartDelta)
          sinceDeltaToken.histogram.record(queryStartDelta)
          Some(s"resultDelta=$resultStartDelta, sinceDelta=$queryStartDelta")
        case _ => None
      }
    } else None
    stat
  }
}
private[cosmosdb] object SubjectViewMapper extends CosmosDBViewMapper {
  private val UUID = "uuid"
  private val KEY = "key"
  private val NSS = "namespaces"
  private val CONCURRENT_INVOCATIONS = "concurrentInvocations"
  private val INVOCATIONS_PER_MIN = "invocationsPerMinute"
  private val BLOCKED = "blocked"
  private val SUBJECT = "subject"
  private val NAME = "name"
  private val notBlocked = s"(NOT(IS_DEFINED(r.$BLOCKED)) OR r.$BLOCKED = false)"

  val handler = SubjectHandler

  override def indexingPolicy: IndexingPolicy =
    //Booleans are indexed by default
    //Specifying less precision for key as match on uuid should be sufficient
    //and keys are bigger
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath(s"/$UUID/?", Index(Range, String, -1)),
        IncludedPath(s"/$NSS/[]/$NAME/?", Index(Range, String, -1)),
        IncludedPath(s"/$SUBJECT/?", Index(Range, String, -1)),
        IncludedPath(s"/$NSS/[]/$UUID/?", Index(Range, String, -1)),
        IncludedPath(s"/$CONCURRENT_INVOCATIONS/?", Index(Range, Number, -1)),
        IncludedPath(s"/$INVOCATIONS_PER_MIN/?", Index(Range, Number, -1))))

  override def prepareQuery(ddoc: String,
                            view: String,
                            startKey: List[Any],
                            endKey: List[Any],
                            limit: Int,
                            includeDocs: Boolean,
                            descending: Boolean): SqlQuerySpec =
    prepareQuery(ddoc, view, startKey, endKey, count = false)

  override def prepareCountQuery(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): SqlQuerySpec =
    prepareQuery(ddoc, view, startKey, endKey, count = true)

  private def prepareQuery(ddoc: String,
                           view: String,
                           startKey: List[Any],
                           endKey: List[Any],
                           count: Boolean): SqlQuerySpec = {
    require(startKey == endKey, s"startKey: $startKey and endKey: $endKey must be same for $ddoc/$view")
    (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") =>
        queryForMatchingSubjectOrNamespace(ddoc, view, startKey, endKey, count)
      case ("namespaceThrottlings", "blockedNamespaces") =>
        queryForBlacklistedNamespace(count)
      case _ =>
        throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def queryForMatchingSubjectOrNamespace(ddoc: String,
                                                 view: String,
                                                 startKey: List[Any],
                                                 endKey: List[Any],
                                                 count: Boolean): SqlQuerySpec = {
    val (where, params) = startKey match {
      case (ns: String) :: Nil =>
        (
          s"$notDeleted AND $notBlocked AND ((r.$SUBJECT = @name AND IS_DEFINED(r.$KEY)) OR n.$NAME = @name)",
          ("@name", ns) :: Nil)
      case (uuid: String) :: (key: String) :: Nil =>
        (
          s"$notDeleted AND $notBlocked AND ((r.$UUID = @uuid AND r.$KEY = @key) OR (n.$UUID = @uuid AND n.$KEY = @key))",
          ("@uuid", uuid) :: ("@key", key) :: Nil)
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    prepareSpec(s"SELECT ${selectClause(count)} AS $alias FROM root r JOIN n in r.namespaces WHERE $where", params)
  }

  private def queryForBlacklistedNamespace(count: Boolean): SqlQuerySpec =
    prepareSpec(
      s"""SELECT ${selectClause(count)} AS $alias
                  FROM   root r
                  WHERE  (r.$BLOCKED = true
                          OR r.$CONCURRENT_INVOCATIONS = 0
                          OR r.$INVOCATIONS_PER_MIN = 0) AND $notDeleted """,
      Nil)

  private def selectClause(count: Boolean) = if (count) "TOP 1 VALUE COUNT(r)" else "r"
}

object CosmosDBViewMapper {

  def paramValue[T](params: SqlParameterCollection, key: String, clazz: Class[T]): Option[T] = {
    val name = "@" + key
    params.iterator().asScala.find(_.getName == name).map(_.getValue(clazz).asInstanceOf[T])
  }

  def createStatsToken(viewName: String, statName: String, collName: String): LogMarkerToken = {
    val unit = MeasurementUnit.time.milliseconds
    val tags = Map("view" -> viewName, "collection" -> collName)
    if (TransactionId.metricsKamonTags) LogMarkerToken("cosmosdb", "query", statName, tags = tags)(unit)
    else LogMarkerToken("cosmosdb", "query", collName, Some(statName))(unit)
  }
}
