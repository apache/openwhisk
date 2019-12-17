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

package org.apache.openwhisk.core.database.memory

import spray.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsTrue}
import org.apache.openwhisk.core.database.{ActivationHandler, UnsupportedQueryKeys, UnsupportedView, WhisksHandler}
import org.apache.openwhisk.core.entity.{UserLimits, WhiskQueries}
import org.apache.openwhisk.utils.JsHelpers

/**
 * Maps the CouchDB view logic to expressed in javascript to Scala logic so as to enable
 * performing queries by {{{MemoryArtifactStore}}}. Also serves as an example of what all query usecases
 * are to be supported by any {{{ArtifactStore}}} implementation
 */
trait MemoryViewMapper {
  protected val TOP: String = WhiskQueries.TOP

  def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any], d: JsObject, c: JsObject): Boolean

  def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]): Seq[JsObject]

  protected def checkKeys(startKey: List[Any], endKey: List[Any]): Unit = {
    require(startKey.nonEmpty)
    require(endKey.nonEmpty)
    require(startKey.head == endKey.head, s"First key should be same => ($startKey) - ($endKey)")
  }

  protected def equal(js: JsObject, name: String, value: String): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsString(v)) => v == value
      case _                 => false
    }

  protected def isTrue(js: JsObject, name: String): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsBoolean(v)) => v
      case _                  => false
    }

  protected def gte(js: JsObject, name: String, value: Number): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsNumber(n)) => n.longValue >= value.longValue
      case _                 => false
    }

  protected def lte(js: JsObject, name: String, value: Number): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsNumber(n)) => n.longValue <= value.longValue
      case _                 => false
    }

  protected def numericSort(s: Seq[JsObject], descending: Boolean, name: String): Seq[JsObject] = {
    val f =
      (js: JsObject) =>
        JsHelpers.getFieldPath(js, name) match {
          case Some(JsNumber(n)) => n.longValue
          case _                 => 0L
      }
    val order = implicitly[Ordering[Long]]
    val ordering = if (descending) order.reverse else order
    s.sortBy(f)(ordering)
  }
}

private object ActivationViewMapper extends MemoryViewMapper {
  private val NS = "namespace"
  private val NS_WITH_PATH = ActivationHandler.NS_PATH
  private val START = "start"

  override def filter(ddoc: String,
                      view: String,
                      startKey: List[Any],
                      endKey: List[Any],
                      d: JsObject,
                      c: JsObject): Boolean = {
    checkKeys(startKey, endKey)
    val nsValue = startKey.head.asInstanceOf[String]
    view match {
      //whisks-filters ddoc uses namespace + invoking action path as first key
      case "activations" if ddoc.startsWith("whisks-filters") =>
        filterActivation(d, equal(c, NS_WITH_PATH, nsValue), startKey, endKey)
      //whisks ddoc uses namespace as first key
      case "activations" if ddoc.startsWith("whisks") => filterActivation(d, equal(d, NS, nsValue), startKey, endKey)
      case _                                          => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  override def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]): Seq[JsObject] =
    view match {
      case "activations" if ddoc.startsWith("whisks") => numericSort(s, descending, START)
      case _                                          => throw UnsupportedView(s"$ddoc/$view")
    }

  private def filterActivation(d: JsObject, matchNS: Boolean, startKey: List[Any], endKey: List[Any]): Boolean = {
    val filterResult = (startKey, endKey) match {
      case (_ :: Nil, _ :: `TOP` :: Nil) =>
        matchNS
      case (_ :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        matchNS && gte(d, START, since)
      case (_ :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        matchNS && gte(d, START, since) && lte(d, START, upto)
      case _ => throw UnsupportedQueryKeys(s"$startKey, $endKey")
    }
    filterResult
  }
}

private object WhisksViewMapper extends MemoryViewMapper {
  private val NS = "namespace"
  private val ROOT_NS = WhisksHandler.ROOT_NS
  private val TYPE = "entityType"
  private val UPDATED = "updated"
  private val PUBLISH = "publish"
  private val BINDING = "binding"

  override def filter(ddoc: String,
                      view: String,
                      startKey: List[Any],
                      endKey: List[Any],
                      d: JsObject,
                      c: JsObject): Boolean = {
    checkKeys(startKey, endKey)
    val entityType = WhisksHandler.getEntityTypeForDesignDoc(ddoc, view)

    val matchTypeAndView = equal(d, TYPE, entityType) && matchViewConditions(ddoc, view, d)
    val matchNS = equal(d, NS, startKey.head.asInstanceOf[String])
    val matchRootNS = equal(c, ROOT_NS, startKey.head.asInstanceOf[String])

    //Here ddocs for actions, rules and triggers use
    //namespace and namespace/packageName as first key

    val filterResult = (startKey, endKey) match {
      case (ns :: Nil, _ :: `TOP` :: Nil) =>
        (matchTypeAndView && matchNS) || (matchTypeAndView && matchRootNS)

      case (ns :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (matchTypeAndView && matchNS && gte(d, UPDATED, since)) ||
          (matchTypeAndView && matchRootNS && gte(d, UPDATED, since))
      case (ns :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (matchTypeAndView && matchNS && gte(d, UPDATED, since) && lte(d, UPDATED, upto)) ||
          (matchTypeAndView && matchRootNS && gte(d, UPDATED, since) && lte(d, UPDATED, upto))

      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    filterResult
  }

  private def matchViewConditions(ddoc: String, view: String, d: JsObject): Boolean = {
    view match {
      case "packages-public" if ddoc.startsWith("whisks") =>
        isTrue(d, PUBLISH) && hasEmptyBinding(d)
      case _ => true
    }
  }

  private def hasEmptyBinding(js: JsObject) = {
    js.fields.get(BINDING) match {
      case Some(x: JsObject) if x.fields.nonEmpty => false
      case _                                      => true
    }
  }

  override def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]): Seq[JsObject] = {
    view match {
      case "actions" | "rules" | "triggers" | "packages" | "packages-public" if ddoc.startsWith("whisks") =>
        numericSort(s, descending, UPDATED)
      case _ => throw UnsupportedView(s"$ddoc/$view")
    }
  }
}

private object SubjectViewMapper extends MemoryViewMapper {
  private val BLOCKED = "blocked"
  private val SUBJECT = "subject"
  private val UUID = "uuid"
  private val KEY = "key"
  private val NS_NAME = "name"

  override def filter(ddoc: String,
                      view: String,
                      startKey: List[Any],
                      endKey: List[Any],
                      d: JsObject,
                      c: JsObject): Boolean = {
    require(startKey == endKey, s"startKey: $startKey and endKey: $endKey must be same for $ddoc/$view")
    (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") =>
        filterForMatchingSubjectOrNamespace(ddoc, view, startKey, endKey, d)
      case ("namespaceThrottlings", "blockedNamespaces") =>
        filterForBlacklistedNamespace(d)
      case _ =>
        throw UnsupportedView(s"$ddoc/$view")
    }
  }

  private def filterForBlacklistedNamespace(d: JsObject): Boolean = {
    val id = d.fields("_id")
    id match {
      case JsString(idv) if idv.endsWith("/limits") =>
        val limits = UserLimits.serdes.read(d)
        limits.concurrentInvocations.contains(0) || limits.invocationsPerMinute.contains(0)
      case _ =>
        d.getFields(BLOCKED) match {
          case Seq(JsTrue) => true
          case _           => false
        }
    }
  }

  private def filterForMatchingSubjectOrNamespace(ddoc: String,
                                                  view: String,
                                                  startKey: List[Any],
                                                  endKey: List[Any],
                                                  d: JsObject) = {
    val notBlocked = !isTrue(d, BLOCKED)
    startKey match {
      case (ns: String) :: Nil => notBlocked && (equal(d, SUBJECT, ns) || matchingNamespace(d, equal(_, NS_NAME, ns)))
      case (uuid: String) :: (key: String) :: Nil =>
        notBlocked &&
          (
            (equal(d, UUID, uuid) && equal(d, KEY, key))
              || matchingNamespace(d, js => equal(js, UUID, uuid) && equal(js, KEY, key))
          )
      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  override def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]): Seq[JsObject] = {
    s //No sorting to be done
  }

  private def matchingNamespace(js: JsObject, matcher: JsObject => Boolean): Boolean = {
    js.fields.get("namespaces") match {
      case Some(JsArray(e)) => e.exists(v => matcher(v.asJsObject))
      case _                => false
    }
  }
}
