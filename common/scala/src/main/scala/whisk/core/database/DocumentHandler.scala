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

package whisk.core.database

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.entity.DocId
import whisk.core.entity.EntityPath.PATHSEP
import whisk.utils.JsHelpers

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

  def fieldsRequiredForView(ddoc: String, view: String): Set[String] = Set()

  def transformViewResult(
    ddoc: String,
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    includeDocs: Boolean,
    js: JsObject,
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[JsObject]

  def shouldAlwaysIncludeDocs(ddoc: String, view: String): Boolean = false
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
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[JsObject] = {
    //Query result from CouchDB have below object structure with actual result in `value` key
    //So transform the result to confirm to that structure
    val viewResult = JsObject(
      "id" -> js.fields("_id"),
      "key" -> createKey(ddoc, view, startKey, js),
      "value" -> computeView(ddoc, view, js))

    val result = if (includeDocs) JsObject(viewResult.fields + ("doc" -> js)) else viewResult
    Future.successful(result)
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
    val common = js.fields.filterKeys(commonFields)

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
    JsObject(js.fields.filterKeys(commonFields))
  }

  private def computePublicPackageView(js: JsObject): JsObject = {
    JsObject(js.fields.filterKeys(commonFields) + ("binding" -> JsFalse))
  }

  private def computeRulesView(js: JsObject) = {
    JsObject(js.fields.filterKeys(ruleFields))
  }

  private def computePackageView(js: JsObject): JsObject = {
    val common = js.fields.filterKeys(commonFields)
    val binding = js.fields.get("binding") match {
      case Some(x: JsObject) if x.fields.nonEmpty => x
      case _                                      => JsFalse
    }
    JsObject(common + ("binding" -> binding))
  }

  private def computeActionView(js: JsObject): JsObject = {
    val base = js.fields.filterKeys(commonFields ++ Set("limits"))
    val exec_binary = JsHelpers.getFieldPath(js, "exec", "binary")
    JsObject(base + ("exec" -> JsObject("binary" -> exec_binary.getOrElse(JsFalse))))
  }
}

object SubjectHandler extends DocumentHandler {

  override def shouldAlwaysIncludeDocs(ddoc: String, view: String): Boolean = {
    checkSupportedView(ddoc, view)
    true
  }

  override def transformViewResult(
    ddoc: String,
    view: String,
    startKey: List[Any],
    endKey: List[Any],
    includeDocs: Boolean,
    js: JsObject,
    provider: DocumentProvider)(implicit transid: TransactionId, ec: ExecutionContext): Future[JsObject] = {
    require(includeDocs) //For subject/identities includeDocs is always true

    val subject = computeSubjectView(ddoc, view, startKey, js)
    val viewJS = JsObject(
      "namespace" -> JsString(subject.namespace),
      "uuid" -> JsString(subject.uuid),
      "key" -> JsString(subject.key))
    val result =
      JsObject("id" -> js.fields("_id"), "key" -> createKey(ddoc, view, startKey), "value" -> viewJS, "doc" -> JsNull)
    if (subject.matchInNamespace) {
      val limitDocId = s"${subject.namespace}/limits"
      provider
        .get(DocId(limitDocId))
        .map(limits => JsObject(result.fields + ("doc" -> limits.getOrElse(JsObject.empty))))
    } else {
      Future.successful(result)
    }
  }

  def checkSupportedView(ddoc: String, view: String): Unit = {
    if (ddoc != "subjects" || view != "identities") {
      throw UnsupportedView(s"$ddoc/$view")
    }
  }

  def computeSubjectView(ddoc: String, view: String, startKey: List[Any], js: JsObject): SubjectView = {
    val viewJs = startKey match {
      case (ns: String) :: Nil => findMatchingSubject(js, s => s.namespace == ns && !s.blocked)
      case (uuid: String) :: (key: String) :: Nil =>
        findMatchingSubject(js, s => s.uuid == uuid && s.key == key && !s.blocked)
      case _ => None
    }
    viewJs.getOrElse(throw new IllegalArgumentException(s"Subject does not match ${startKey.head}"))
  }

  private def createKey(ddoc: String, view: String, startKey: List[Any]): JsArray = {
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

        case _ => Seq()
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
