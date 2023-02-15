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

package org.apache.openwhisk.core.database.mongodb

import org.apache.openwhisk.core.database._
import org.apache.openwhisk.core.entity.WhiskQueries
import org.mongodb.scala.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Sorts

trait MongoDBViewMapper {
  protected val _computed: String = "_computed"
  protected val TOP: String = WhiskQueries.TOP

  val indexes: List[Document]

  def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson

  def sort(ddoc: String, view: String, descending: Boolean): Option[Bson]

  protected def checkKeys(startKey: List[Any], endKey: List[Any]): Unit = {
    require(startKey.nonEmpty)
    require(endKey.nonEmpty)
    require(startKey.head == endKey.head, s"First key should be same => ($startKey) - ($endKey)")
  }
}

private object ActivationViewMapper extends MongoDBViewMapper {
  private val NS = "namespace"
  private val NS_WITH_PATH = s"${_computed}.${ActivationHandler.NS_PATH}"
  private val START = "start"
  override val indexes: List[Document] =
    List(
      Document(s"$START" -> -1),
      Document(s"$START" -> -1, s"$NS" -> -1),
      Document(s"$NS_WITH_PATH" -> -1, s"$START" -> -1))

  override def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson = {
    checkKeys(startKey, endKey)
    view match {
      //whisks-filters ddoc uses namespace + invoking action path as first key
      case "activations" if ddoc.startsWith("whisks-filters") => createActivationFilter(NS_WITH_PATH, startKey, endKey)
      //whisks ddoc uses namespace as first key
      case "activations" if ddoc.startsWith("whisks") => createActivationFilter(NS, startKey, endKey)
      case _                                          => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  override def sort(ddoc: String, view: String, descending: Boolean): Option[Bson] = {
    view match {
      case "activations" if ddoc.startsWith("whisks-filters") =>
        val sort = if (descending) Sorts.descending(NS_WITH_PATH, START) else Sorts.ascending(NS_WITH_PATH, START)
        Some(sort)
      case "activations" if ddoc.startsWith("whisks") =>
        val sort = if (descending) Sorts.descending(NS, START) else Sorts.ascending(NS, START)
        Some(sort)
      case _ => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def createActivationFilter(nsPropName: String, startKey: List[Any], endKey: List[Any]) = {
    require(startKey.head.isInstanceOf[String])
    val matchNS = equal(nsPropName, startKey.head)

    val filter = (startKey, endKey) match {
      case (_ :: Nil, _ :: `TOP` :: Nil) =>
        matchNS
      case (_ :: since :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        and(matchNS, gte(START, since))
      case (_ :: since :: Nil, _ :: upto :: `TOP` :: Nil) =>
        and(matchNS, gte(START, since), lte(START, upto))
      case _ => throw UnsupportedQueryKeys(s"$startKey, $endKey")
    }
    filter
  }
}

private object WhisksViewMapper extends MongoDBViewMapper {
  private val NS = "namespace"
  private val ROOT_NS = s"${_computed}.${WhisksHandler.ROOT_NS}"
  private val TYPE = "entityType"
  private val UPDATED = "updated"
  private val PUBLISH = "publish"
  private val BINDING = "binding"
  override val indexes: List[Document] =
    List(Document(s"$NS" -> -1, s"$UPDATED" -> -1), Document(s"$ROOT_NS" -> -1, s"$UPDATED" -> -1))

  override def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson = {
    checkKeys(startKey, endKey)
    view match {
      case "all" => listAllInNamespace(ddoc, view, startKey, endKey)
      case _     => listCollectionInNamespace(ddoc, view, startKey, endKey)
    }
  }

  private def listCollectionInNamespace(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson = {

    val entityType = getEntityType(ddoc, view)

    val matchType = equal(TYPE, entityType)
    val matchNS = equal(NS, startKey.head)
    val matchRootNS = equal(ROOT_NS, startKey.head)

    val filter = (startKey, endKey) match {
      case (ns :: Nil, _ :: `TOP` :: Nil) =>
        or(and(matchType, matchNS), and(matchType, matchRootNS))
      case (ns :: since :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        // @formatter:off
                or(
                    and(matchType, matchNS, gte(UPDATED, since)),
                    and(matchType, matchRootNS, gte(UPDATED, since))
                )
            // @formatter:on
      case (ns :: since :: Nil, _ :: upto :: `TOP` :: Nil) =>
        or(
          and(matchType, matchNS, gte(UPDATED, since), lte(UPDATED, upto)),
          and(matchType, matchRootNS, gte(UPDATED, since), lte(UPDATED, upto)))
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    if (view == "packages-public")
      and(equal(BINDING, Map.empty), equal(PUBLISH, true), filter)
    else
      filter
  }

  private def listAllInNamespace(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson = {
    val matchRootNS = equal(ROOT_NS, startKey.head)
    val filter = (startKey, endKey) match {
      case (ns :: Nil, _ :: `TOP` :: Nil) =>
        and(exists(TYPE), matchRootNS)
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    filter
  }

  override def sort(ddoc: String, view: String, descending: Boolean): Option[Bson] = {
    view match {
      case "actions" | "rules" | "triggers" | "packages" | "packages-public" | "all"
          if ddoc.startsWith("whisks") || ddoc.startsWith("all-whisks") =>
        val sort = if (descending) Sorts.descending(UPDATED) else Sorts.ascending(UPDATED)
        Some(sort)
      case _ => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def getEntityType(ddoc: String, view: String): String = view match {
    case "actions"                      => "action"
    case "rules"                        => "rule"
    case "triggers"                     => "trigger"
    case "packages" | "packages-public" => "package"
    case _                              => throw UnsupportedView(s"$ddoc/$view")
  }
}
private object SubjectViewMapper extends MongoDBViewMapper {
  private val BLOCKED = "blocked"
  private val SUBJECT = "subject"
  private val UUID = "uuid"
  private val KEY = "key"
  private val NS_NAME = "namespaces.name"
  private val NS_UUID = "namespaces.uuid"
  private val NS_KEY = "namespaces.key"
  private val CONCURRENT_INVOCATIONS = "concurrentInvocations"
  private val INVOCATIONS_PERMINUTE = "invocationsPerMinute"
  override val indexes: List[Document] =
    List(Document(s"$NS_NAME" -> -1), Document(s"$NS_UUID" -> -1, s"$NS_KEY" -> -1))

  override def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any]): Bson = {
    require(startKey == endKey, s"startKey: $startKey and endKey: $endKey must be same for $ddoc/$view")
    (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") =>
        filterForMatchingSubjectOrNamespace(ddoc, view, startKey, endKey)
      case ("namespaceThrottlings", "blockedNamespaces") =>
        or(equal(BLOCKED, true), equal(CONCURRENT_INVOCATIONS, 0), equal(INVOCATIONS_PERMINUTE, 0))
      case _ =>
        throw UnsupportedView(s"$ddoc/$view")
    }
  }

  override def sort(ddoc: String, view: String, descending: Boolean): Option[Bson] = {
    (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") => None
      case ("namespaceThrottlings", "blockedNamespaces") => None
      case _ =>
        throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def filterForMatchingSubjectOrNamespace(ddoc: String,
                                                  view: String,
                                                  startKey: List[Any],
                                                  endKey: List[Any]): Bson = {
    val notBlocked = notEqual(BLOCKED, true)
    startKey match {
      case (ns: String) :: Nil                    => and(notBlocked, or(equal(SUBJECT, ns), equal(NS_NAME, ns)))
      case (uuid: String) :: (key: String) :: Nil =>
        // @formatter:off
        and(
          notBlocked,
          or(
            and(equal(UUID, uuid), equal(KEY, key)),
            and(equal(NS_UUID, uuid), equal(NS_KEY, key))
          ))
      // @formatter:on
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
  }

}
