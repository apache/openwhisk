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

package whisk.core.database.memory

import spray.json.{JsNumber, JsObject, JsString}
import whisk.core.database.{UnsupportedQueryKeys, UnsupportedView, WhisksHandler}
import whisk.core.entity.WhiskEntityQueries
import whisk.utils.JsHelpers

trait MemoryViewMapper {
  protected val TOP: String = WhiskEntityQueries.TOP

  def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any], d: JsObject, c: JsObject): Boolean =
    ???

  def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]): Seq[JsObject] = ???

  protected def checkKeys(startKey: List[Any], endKey: List[Any]): Unit = {
    require(startKey.nonEmpty)
    require(endKey.nonEmpty)
    require(startKey.head == endKey.head, s"First key should be same => ($startKey) - ($endKey)")
  }

  def equal(js: JsObject, name: String, value: String): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsString(v)) => v == value
      case _                 => false
    }

  def gte(js: JsObject, name: String, value: Number): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsNumber(n)) => n.longValue() >= value.longValue()
      case _                 => false
    }

  def lte(js: JsObject, name: String, value: Number): Boolean =
    JsHelpers.getFieldPath(js, name) match {
      case Some(JsNumber(n)) => n.longValue() <= value.longValue()
      case _                 => false
    }

  def numericSort(s: Seq[JsObject], descending: Boolean, name: String): Seq[JsObject] = {
    val f =
      (js: JsObject) =>
        JsHelpers.getFieldPath(js, name) match {
          case Some(JsNumber(n)) => n.longValue()
          case _                 => 0L
      }
    val order = implicitly[Ordering[Long]]
    val ordering = if (descending) order else order.reverse
    s.sortBy(f)(ordering)
  }
}

private object ActivationViewMapper extends MemoryViewMapper {}

private object WhisksViewMapper extends MemoryViewMapper {
  val NS = "namespace"
  val ROOT_NS = WhisksHandler.ROOT_NS
  val TYPE = "entityType"
  val UPDATED = "updated"

  override def filter(ddoc: String, view: String, startKey: List[Any], endKey: List[Any], d: JsObject, c: JsObject) = {
    checkKeys(startKey, endKey)
    val entityType = WhisksHandler.getEntityTypeForDesignDoc(ddoc, view)

    val matchType = equal(d, TYPE, entityType)
    val matchNS = equal(d, NS, startKey.head.asInstanceOf[String])
    val matchRootNS = equal(c, ROOT_NS, startKey.head.asInstanceOf[String])

    //Here ddocs for actions, rules and triggers use
    //namespace and namespace/packageName as first key

    val result = (startKey, endKey) match {
      case (ns :: Nil, _ :: `TOP` :: Nil) =>
        (matchType && matchNS) || (matchType && matchRootNS)

      case (ns :: (since: Number) :: Nil, _ :: `TOP` :: `TOP` :: Nil) =>
        (matchType && matchNS && gte(d, UPDATED, since)) || (matchType && matchRootNS && gte(d, UPDATED, since))

      case (ns :: (since: Number) :: Nil, _ :: (upto: Number) :: `TOP` :: Nil) =>
        (matchType && matchNS && gte(d, UPDATED, since) && lte(d, UPDATED, upto)) ||
          (matchType && matchRootNS && gte(d, UPDATED, since) && lte(d, UPDATED, upto))

      case _ => throw UnsupportedQueryKeys(s"$ddoc/$view -> ($startKey, $endKey)")
    }
    result
  }

  override def sort(ddoc: String, view: String, descending: Boolean, s: Seq[JsObject]) = {
    view match {
      case "actions" | "rules" | "triggers" | "packages" | "packages-public" if ddoc.startsWith("whisks") =>
        numericSort(s, descending, UPDATED)
      case _ => throw UnsupportedView(s"$ddoc/$view")
    }
  }
}

private object SubjectViewMapper extends MemoryViewMapper {}
