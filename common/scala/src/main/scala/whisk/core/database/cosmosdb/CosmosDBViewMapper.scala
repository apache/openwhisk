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

package whisk.core.database.cosmosdb

import java.util.Collections

import com.microsoft.azure.cosmosdb.IndexKind.{Hash, Range}
import com.microsoft.azure.cosmosdb.DataType.{Number, String}
import com.microsoft.azure.cosmosdb.{PartitionKeyDefinition, SqlParameter, SqlParameterCollection, SqlQuerySpec}
import whisk.core.database.ActivationHandler.NS_PATH
import whisk.core.database.WhisksHandler.ROOT_NS
import whisk.core.database.{
  ActivationHandler,
  DocumentHandler,
  SubjectHandler,
  UnsupportedQueryKeys,
  UnsupportedView,
  WhisksHandler
}
import whisk.core.database.cosmosdb.CosmosDBConstants._computed
import whisk.core.database.cosmosdb.CosmosDBConstants.alias
import whisk.core.entity.WhiskEntityQueries.TOP

private[cosmosdb] trait CosmosDBViewMapper {
  protected val NOTHING = ""
  protected val ALL_FIELDS = "*"
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

    val query = s"SELECT $selectClause FROM root r WHERE ${whereClause._1} ORDER BY $orderField $order"

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
  private val NS = "r.namespace"
  private val ROOT_NS_C = s"r.${_computed}.$ROOT_NS"
  private val TYPE = "r.entityType"
  private val UPDATED = "r.updated"
  private val PUBLISH = "r.publish"
  private val BINDING = "r.binding"

  val handler = WhisksHandler

  override def indexingPolicy: IndexingPolicy =
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath("/entityType/?", Index(Hash, String, -1)),
        IncludedPath("/namespace/?", Index(Hash, String, -1)),
        IncludedPath(s"/${_computed}/$ROOT_NS/?", Index(Hash, String, -1)),
        IncludedPath("/updated/?", Index(Range, Number, -1))))

  override protected def where(ddoc: String,
                               view: String,
                               startKey: List[Any],
                               endKey: List[Any]): (String, List[(String, Any)]) = {
    val entityType = WhisksHandler.getEntityTypeForDesignDoc(ddoc, view)
    val namespace = startKey.head

    val (vc, vcParams) =
      viewConditions(ddoc, view).map(q => (s"${q._1} AND", q._2)).getOrElse((NOTHING, Nil))

    val params = ("@entityType", entityType) :: ("@namespace", namespace) :: vcParams
    val baseCondition = s"$vc $TYPE = @entityType AND ($NS = @namespace OR $ROOT_NS_C = @namespace)"

    (startKey, endKey) match {
      case (_ :: Nil, _ :: `TOP` :: Nil) =>
        (baseCondition, params)

      case (_ :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (s"$baseCondition AND $UPDATED >= @since", ("@since", since) :: params)

      case (_ :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (s"$baseCondition AND ($UPDATED BETWEEN @since AND @upto)", ("@upto", upto) :: ("@since", since) :: params)

      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  private def viewConditions(ddoc: String, view: String): Option[(String, List[(String, Any)])] = {
    view match {
      case "packages-public" if ddoc.startsWith("whisks") =>
        Some(s"$PUBLISH = true AND (NOT IS_OBJECT($BINDING) OR $BINDING = {})", Nil)
      case _ => None
    }
  }

  override protected def orderByField(ddoc: String, view: String): String = view match {
    case "actions" | "rules" | "triggers" | "packages" | "packages-public" if ddoc.startsWith("whisks") =>
      UPDATED
    case _ => throw UnsupportedView(s"$ddoc/$view")
  }

}
private[cosmosdb] object ActivationViewMapper extends SimpleMapper {
  private val NS = "r.namespace"
  private val NS_WITH_PATH = s"r.${_computed}.$NS_PATH"
  private val START = "r.start"

  val handler = ActivationHandler

  override def indexingPolicy: IndexingPolicy =
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath("/namespace/?", Index(Hash, String, -1)),
        IncludedPath(s"/${_computed}/$NS_PATH/?", Index(Hash, String, -1)),
        IncludedPath("/start/?", Index(Range, Number, -1))))

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
        (s"$nsKey = @nsvalue", params)
      case (_ :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (s"$nsKey = @nsvalue AND $START >= @start", ("@start", since) :: params)
      case (_ :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (s"$nsKey = @nsvalue AND $START >= @start AND $START <= @upto", ("@upto", upto) :: ("@start", since) :: params)
      case _ => throw UnsupportedQueryKeys(s"$startKey, $endKey")
    }
    filter
  }

  override protected def orderByField(ddoc: String, view: String): String = view match {
    case "activations" if ddoc.startsWith("whisks") => START
    case _                                          => throw UnsupportedView(s"$ddoc/$view")
  }
}
private[cosmosdb] object SubjectViewMapper extends CosmosDBViewMapper {
  val handler = SubjectHandler

  override def indexingPolicy: IndexingPolicy =
    //Booleans are indexed by default
    //Specifying less precision for key as match on uuid should be sufficient
    //and keys are bigger
    IndexingPolicy(
      includedPaths = Set(
        IncludedPath("/uuid/?", Index(Hash, String, -1)),
        IncludedPath("/key/?", Index(Hash, String, 3)),
        IncludedPath("/namespaces/[]/uuid/?", Index(Hash, String, -1)),
        IncludedPath("/namespaces/[]/key/?", Index(Hash, String, 3)),
        IncludedPath("/concurrentInvocations/?", Index(Hash, Number, -1)),
        IncludedPath("/invocationsPerMinute/?", Index(Hash, Number, -1))))

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
      case ("subjects", "identities") =>
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
    val notBlocked = "(NOT(IS_DEFINED(r.blocked)) OR r.blocked = false)"
    val (where, params) = startKey match {
      case (ns: String) :: Nil =>
        (s"$notBlocked AND (r.subject = @name OR n.name = @name)", ("@name", ns) :: Nil)
      case (uuid: String) :: (key: String) :: Nil =>
        (
          s"$notBlocked AND ((r.uuid = @uuid AND r.key = @key) OR (n.uuid = @uuid AND n.key = @key))",
          ("@uuid", uuid) :: ("@key", key) :: Nil)
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    prepareSpec(s"SELECT ${selectClause(count)} AS $alias FROM root r JOIN n in r.namespaces WHERE $where", params)
  }

  private def queryForBlacklistedNamespace(count: Boolean): SqlQuerySpec =
    prepareSpec(
      s"""SELECT ${selectClause(count)} AS $alias
                  FROM   root r
                  WHERE  r.blocked = true
                          OR r.concurrentInvocations = 0
                          OR r.invocationsPerMinute = 0 """,
      Nil)

  private def selectClause(count: Boolean) = if (count) "TOP 1 VALUE COUNT(r)" else "r"
}
