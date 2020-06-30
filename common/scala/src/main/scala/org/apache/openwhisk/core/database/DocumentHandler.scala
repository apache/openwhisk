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

package org.apache.openwhisk.core.database

import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entity.{DocId, UserLimits}
import org.apache.openwhisk.core.entity.EntityPath.PATHSEP
import org.apache.openwhisk.utils.JsHelpers

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.ExecutionContext

/**
 * Simple abstraction allow accessing a document just by _id. This would be used
 * to perform queries related to join support
 */
trait DocumentProvider {
  protected[database] def get(id: DocId)(implicit transid: TransactionId): Future[Option[JsObject]]
}

trait DocumentHandler {

  /**
   * Returns a JsObject having computed fields. This is a substitution for fields
   * computed in CouchDB views
   */
  def computedFields(js: JsObject): JsObject = JsObject.empty

  /**
   * Returns the set of field names (including sub document field) which needs to be fetched as part of
   * query made for the given view.
   */
  def fieldsRequiredForView(ddoc: String, view: String): Set[String] = Set.empty

  /**
   * Transforms the query result instance from artifact store as per view requirements. Some view computation
   * may result in performing a join operation.
   *
   * If the passed instance does not confirm to view conditions that transformed result would be None. This
   * would be the case if view condition cannot be completed handled in query made to artifact store
   */
  def transformViewResult(
    ddoc: String,
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    includeDocs: Boolean,
    js: JsObject,
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[Seq[JsObject]]

  /**
   * Determines if the complete document should be fetched even if `includeDocs` is set to true. For some view computation
   * complete document (including sub documents) may be needed and for them its required that complete document must be
   * fetched as part of query response
   */
  def shouldAlwaysIncludeDocs(ddoc: String, view: String): Boolean = false

  def checkIfTableSupported(table: String): Unit = {
    if (!supportedTables.contains(table)) {
      throw UnsupportedView(table)
    }
  }

  protected def supportedTables: Set[String]
}

/**
 * Base class for handlers which do not perform joins for computing views
 */
abstract class SimpleHandler extends DocumentHandler {
  override def transformViewResult(
    ddoc: String,
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    includeDocs: Boolean,
    js: JsObject,
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[Seq[JsObject]] = {
    //Query result from CouchDB have below object structure with actual result in `value` key
    //So transform the result to confirm to that structure
    val viewResult = JsObject(
      "id" -> js.fields("_id"),
      "key" -> createKey(ddoc, view, startKey, js),
      "value" -> computeView(ddoc, view, js))

    val result = if (includeDocs) JsObject(viewResult.fields + ("doc" -> js)) else viewResult
    Future.successful(Seq(result))
  }

  /**
   * Computes the view as per viewName. Its passed either the projected object or actual
   * object
   */
  def computeView(ddoc: String, view: String, js: JsObject): JsObject

  /**
   * Key is an array which matches the view query key
   */
  protected def createKey(ddoc: String, view: String, startKey: List[Any], js: JsObject): JsArray
}

object ActivationHandler extends SimpleHandler {
  val NS_PATH = "nspath"
  private val commonFields =
    Set("namespace", "name", "version", "publish", "annotations", "activationId", "start", "cause")
  private val fieldsForView = commonFields ++ Seq("end", "response.statusCode")

  protected val supportedTables =
    Set("activations/byDate", "whisks-filters.v2.1.1/activations", "whisks.v2.1.0/activations")

  override def computedFields(js: JsObject): JsObject = {
    val path = js.fields.get("namespace") match {
      case Some(JsString(namespace)) => JsString(namespace + PATHSEP + pathFilter(js))
      case _                         => JsNull
    }
    val deleteLogs = annotationValue(js, "kind", { v =>
      v.convertTo[String] != "sequence"
    }, true)
    dropNull((NS_PATH, path), ("deleteLogs", JsBoolean(deleteLogs)))
  }

  override def fieldsRequiredForView(ddoc: String, view: String): Set[String] = view match {
    case "activations" => fieldsForView
    case _             => throw UnsupportedView(s"$ddoc/$view")
  }

  def computeView(ddoc: String, view: String, js: JsObject): JsObject = view match {
    case "activations" => computeActivationView(js)
    case _             => throw UnsupportedView(s"$ddoc/$view")
  }

  def createKey(ddoc: String, view: String, startKey: List[Any], js: JsObject): JsArray = {
    startKey match {
      case (ns: String) :: Nil      => JsArray(Vector(JsString(ns)))
      case (ns: String) :: _ :: Nil => JsArray(Vector(JsString(ns), js.fields("start")))
      case _                        => throw UnsupportedQueryKeys("$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  private def computeActivationView(js: JsObject): JsObject = {
    val common = js.fields.filterKeys(commonFields).toMap

    val (endTime, duration) = js.getFields("end", "start") match {
      case Seq(JsNumber(end), JsNumber(start)) if end != 0 => (JsNumber(end), JsNumber(end - start))
      case _                                               => (JsNull, JsNull)
    }

    val statusCode = JsHelpers.getFieldPath(js, "response", "statusCode").getOrElse(JsNull)

    val result = common + ("end" -> endTime) + ("duration" -> duration) + ("statusCode" -> statusCode)
    JsObject(result.filter(_._2 != JsNull))
  }

  protected[database] def pathFilter(js: JsObject): String = {
    val name = js.fields("name").convertTo[String]
    annotationValue(js, "path", { v =>
      val p = v.convertTo[String].split(PATHSEP)
      if (p.length == 3) p(1) + PATHSEP + name else name
    }, name)
  }

  /**
   * Finds and transforms annotation with matching key.
   *
   * @param js js object having annotations array
   * @param key annotation key
   * @param vtr transformer function to map annotation value
   * @param default default value to use if no matching annotation found
   * @return annotation value matching given key
   */
  protected[database] def annotationValue[T](js: JsObject, key: String, vtr: JsValue => T, default: T): T = {
    js.fields.get("annotations") match {
      case Some(JsArray(e)) =>
        e.view
          .map(_.asJsObject.getFields("key", "value"))
          .collectFirst {
            case Seq(JsString(`key`), v: JsValue) => vtr(v) //match annotation with given key
          }
          .getOrElse(default)
      case _ => default
    }
  }

  private def dropNull(fields: JsField*) = JsObject(fields.filter(_._2 != JsNull): _*)
}

object WhisksHandler extends SimpleHandler {
  val ROOT_NS = "rootns"
  private val commonFields = Set("namespace", "name", "version", "publish", "annotations", "updated")
  private val actionFields = commonFields ++ Set("limits", "exec.binary")
  private val packageFields = commonFields ++ Set("binding")
  private val packagePublicFields = commonFields
  private val ruleFields = commonFields
  private val triggerFields = commonFields

  protected val supportedTables = Set(
    "whisks.v2.1.0/actions",
    "whisks.v2.1.0/packages",
    "whisks.v2.1.0/packages-public",
    "whisks.v2.1.0/rules",
    "whisks.v2.1.0/triggers")

  override def computedFields(js: JsObject): JsObject = {
    js.fields.get("namespace") match {
      case Some(JsString(namespace)) =>
        val ns = namespace.split(PATHSEP)
        val rootNS = if (ns.length > 1) ns(0) else namespace
        JsObject((ROOT_NS, JsString(rootNS)))
      case _ => JsObject.empty
    }
  }

  override def fieldsRequiredForView(ddoc: String, view: String): Set[String] = view match {
    case "actions"         => actionFields
    case "packages"        => packageFields
    case "packages-public" => packagePublicFields
    case "rules"           => ruleFields
    case "triggers"        => triggerFields
    case _                 => throw UnsupportedView(s"$ddoc/$view")
  }

  def computeView(ddoc: String, view: String, js: JsObject): JsObject = view match {
    case "actions"         => computeActionView(js)
    case "packages"        => computePackageView(js)
    case "packages-public" => computePublicPackageView(js)
    case "rules"           => computeRulesView(js)
    case "triggers"        => computeTriggersView(js)
    case _                 => throw UnsupportedView(s"$ddoc/$view")
  }

  def createKey(ddoc: String, view: String, startKey: List[Any], js: JsObject): JsArray = {
    startKey match {
      case (ns: String) :: Nil      => JsArray(Vector(JsString(ns)))
      case (ns: String) :: _ :: Nil => JsArray(Vector(JsString(ns), js.fields("updated")))
      case _                        => throw UnsupportedQueryKeys("$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  def getEntityTypeForDesignDoc(ddoc: String, view: String): String = view match {
    case "actions"                      => "action"
    case "rules"                        => "rule"
    case "triggers"                     => "trigger"
    case "packages" | "packages-public" => "package"
    case _                              => throw UnsupportedView(s"$ddoc/$view")
  }

  private def computeTriggersView(js: JsObject): JsObject = {
    JsObject(js.fields.filterKeys(commonFields).toMap)
  }

  private def computePublicPackageView(js: JsObject): JsObject = {
    JsObject(js.fields.filterKeys(commonFields).toMap + ("binding" -> JsFalse))
  }

  private def computeRulesView(js: JsObject) = {
    JsObject(js.fields.filterKeys(ruleFields).toMap)
  }

  private def computePackageView(js: JsObject): JsObject = {
    val common = js.fields.filterKeys(commonFields).toMap
    val binding = js.fields.get("binding") match {
      case Some(x: JsObject) if x.fields.nonEmpty => x
      case _                                      => JsFalse
    }
    JsObject(common + ("binding" -> binding))
  }

  private def computeActionView(js: JsObject): JsObject = {
    val base = js.fields.filterKeys(commonFields ++ Set("limits")).toMap
    val exec_binary = JsHelpers.getFieldPath(js, "exec", "binary")
    JsObject(base + ("exec" -> JsObject("binary" -> exec_binary.getOrElse(JsFalse))))
  }
}

object SubjectHandler extends DocumentHandler {

  protected val supportedTables =
    Set("subjects/identities", "subjects.v2.0.0/identities", "namespaceThrottlings/blockedNamespaces")

  override def shouldAlwaysIncludeDocs(ddoc: String, view: String): Boolean = {
    (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") => true
      case ("namespaceThrottlings", "blockedNamespaces") => true
      case _                                             => throw UnsupportedView(s"$ddoc/$view")
    }
  }

  override def transformViewResult(
    ddoc: String,
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    includeDocs: Boolean,
    js: JsObject,
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[Seq[JsObject]] = {

    val result = (ddoc, view) match {
      case (s, "identities") if s.startsWith("subjects") =>
        require(includeDocs) //For subject/identities includeDocs is always true
        computeSubjectView(startKey, js, provider)
      case ("namespaceThrottlings", "blockedNamespaces") =>
        Future.successful(computeBlacklistedNamespaces(js))
      case _ =>
        throw UnsupportedView(s"$ddoc/$view")
    }
    result
  }

  /**
   * {{{
   *   function (doc) {
   *   if (doc._id.indexOf("/limits") >= 0) {
   *     if (doc.concurrentInvocations === 0 || doc.invocationsPerMinute === 0) {
   *       var namespace = doc._id.replace("/limits", "");
   *       emit(namespace, 1);
   *     }
   *   } else if (doc.subject && doc.namespaces && doc.blocked) {
   *     doc.namespaces.forEach(function(namespace) {
   *       emit(namespace.name, 1);
   *     });
   *   }
   * }
   * }}}
   */
  private def computeBlacklistedNamespaces(js: JsObject): Seq[JsObject] = {
    val id = js.fields("_id")
    val value = JsNumber(1)
    id match {
      case JsString(idv) if idv.endsWith("/limits") =>
        val limits = UserLimits.serdes.read(js)
        if (limits.concurrentInvocations.contains(0) || limits.invocationsPerMinute.contains(0)) {
          val ns = idv.substring(0, idv.indexOf("/limits"))
          Seq(JsObject("id" -> id, "key" -> JsString(ns), "value" -> value))
        } else Seq.empty
      case _ =>
        js.getFields("subject", "namespaces", "blocked") match {
          case Seq(_, namespaces: JsArray, JsTrue) =>
            namespaces.elements.map { ns =>
              val name = ns.asJsObject.fields("name")
              JsObject("id" -> id, "key" -> name, "value" -> value)
            }
          case _ =>
            Seq.empty
        }
    }
  }

  private def computeSubjectView(startKey: List[Any], js: JsObject, provider: DocumentProvider)(
    implicit transid: TransactionId,
    ec: ExecutionContext) = {
    val subjectOpt = findMatchingSubject(startKey, js)
    val result = subjectOpt match {
      case Some(subject) =>
        val limitDocId = s"${subject.namespace}/limits"
        val viewJS = JsObject(
          "_id" -> JsString(limitDocId),
          "namespace" -> JsString(subject.namespace),
          "uuid" -> JsString(subject.uuid),
          "key" -> JsString(subject.key))
        val result =
          JsObject("id" -> js.fields("_id"), "key" -> createKey(startKey), "value" -> viewJS, "doc" -> JsNull)
        if (subject.matchInNamespace) {
          provider
            .get(DocId(limitDocId))
            .map(limits => Seq(JsObject(result.fields + ("doc" -> limits.getOrElse(JsNull)))))
        } else {
          Future.successful(Seq(result))
        }
      case None =>
        Future.successful(Seq.empty)
    }
    result
  }

  def findMatchingSubject(startKey: List[Any], js: JsObject): Option[SubjectView] = {
    startKey match {
      case (ns: String) :: Nil => findMatchingSubject(js, s => s.namespace == ns && !s.blocked)
      case (uuid: String) :: (key: String) :: Nil =>
        findMatchingSubject(js, s => s.uuid == uuid && s.key == key && !s.blocked)
      case _ => None
    }
  }

  private def createKey(startKey: List[Any]): JsArray = {
    startKey match {
      case (ns: String) :: Nil                    => JsArray(Vector(JsString(ns))) //namespace or subject
      case (uuid: String) :: (key: String) :: Nil => JsArray(Vector(JsString(uuid), JsString(key))) // uuid, key
      case _                                      => throw UnsupportedQueryKeys("$ddoc/$view -> ($startKey, $endKey)")
    }
  }

  /**
   * Computes the view as per logic below from (identities/subject) view
   *
   * {{{
   *   function (doc) {
   *      if(doc.uuid && doc.key && !doc.blocked) {
   *        var v = {namespace: doc.subject, uuid: doc.uuid, key: doc.key};
   *        emit([doc.subject], v);
   *        emit([doc.uuid, doc.key], v);
   *      }
   *      if(doc.namespaces && !doc.blocked) {
   *        doc.namespaces.forEach(function(namespace) {
   *          var v = {_id: namespace.name + '/limits', namespace: namespace.name, uuid: namespace.uuid, key: namespace.key};
   *          emit([namespace.name], v);
   *          emit([namespace.uuid, namespace.key], v);
   *        });
   *      }
   *    }
   * }}}
   *
   * @param js subject json from db
   * @param matches match predicate
   */
  private def findMatchingSubject(js: JsObject, matches: SubjectView => Boolean): Option[SubjectView] = {
    val blocked = js.fields.get("blocked") match {
      case Some(JsTrue) => true
      case _            => false
    }

    val r = js.getFields("subject", "uuid", "key") match {
      case Seq(JsString(ns), JsString(uuid), JsString(key)) => Some(SubjectView(ns, uuid, key, blocked)).filter(matches)
      case _                                                => None
    }

    r.orElse {
      val namespaces = js.fields.get("namespaces") match {
        case Some(JsArray(e)) =>
          e.map(_.asJsObject.getFields("name", "uuid", "key") match {
            case Seq(JsString(ns), JsString(uuid), JsString(key)) =>
              Some(SubjectView(ns, uuid, key, blocked, matchInNamespace = true))
            case _ => None
          })

        case _ => Seq.empty
      }
      namespaces.flatMap(_.filter(matches)).headOption
    }
  }

  case class SubjectView(namespace: String,
                         uuid: String,
                         key: String,
                         blocked: Boolean = false,
                         matchInNamespace: Boolean = false)
}
