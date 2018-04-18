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

import com.microsoft.azure.cosmosdb.{SqlParameter, SqlParameterCollection, SqlQuerySpec}
import whisk.core.database._
import whisk.core.database.cosmosdb.CosmosDBConstants._computed
import whisk.core.entity.WhiskEntityQueries.TOP

trait CosmosDBViewMapper {
  protected val NOTHING = ""
  protected val ALL_FIELDS = "*"
  protected def handler: DocumentHandler

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

    val params = new SqlParameterCollection
    whereClause._2.foreach { case (k, v) => params.add(new SqlParameter(k, v)) }

    new SqlQuerySpec(query, params)
  }

  protected def checkKeys(startKey: List[Any], endKey: List[Any]): Unit = {
    require(startKey.nonEmpty)
    require(endKey.nonEmpty)
    require(startKey.head == endKey.head, s"First key should be same => ($startKey) - ($endKey)")
  }

  protected def select(ddoc: String, viewName: String, limit: Int, includeDocs: Boolean): String = {
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
                      endKey: List[Any]): (String, List[(String, Any)]) = ???

  protected def orderByField(ddoc: String, viewName: String): String = ???

}

object WhisksViewMapper extends CosmosDBViewMapper {
  val handler = WhisksHandler
}
object ActivationViewMapper extends CosmosDBViewMapper {
  private val NS = "r.namespace"
  private val NS_WITH_PATH = s"r.${_computed}.${ActivationHandler.NS_PATH}"
  private val START = "r.start"

  val handler = ActivationHandler

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
object SubjectViewMapper extends CosmosDBViewMapper {
  val handler = SubjectHandler
}
