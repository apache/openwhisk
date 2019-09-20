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

package org.apache.openwhisk.core.entity

import java.time.Instant

import scala.concurrent.Future
import scala.util.Try
import spray.json._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.{ArtifactStore, CacheChangeNotification, DocumentFactory, StaleParameter}

/**
 * A WhiskActivation provides an abstraction of the meta-data
 * for a whisk action activation record.
 *
 * The WhiskActivation object is used as a helper to adapt objects between
 * the schema used by the database and the WhiskAuth abstraction.
 *
 * @param namespace the namespace for the activation
 * @param name the name of the activated entity
 * @param subject the subject activating the entity
 * @param activationId the activation id
 * @param start the start of the activation in epoch millis
 * @param end the end of the activation in epoch millis
 * @param cause the activation id of the activated entity that causes this activation
 * @param response the activation response
 * @param logs the activation logs
 * @param version the semantic version (usually matches the activated entity)
 * @param publish true to share the activation or false otherwise
 * @param annotations the set of annotations to attribute to the activation
 * @param duration of the activation in milliseconds
 * @throws IllegalArgumentException if any required argument is undefined
 */
@throws[IllegalArgumentException]
case class WhiskActivation(namespace: EntityPath,
                           override val name: EntityName,
                           subject: Subject,
                           activationId: ActivationId,
                           start: Instant,
                           end: Instant,
                           cause: Option[ActivationId] = None,
                           response: ActivationResponse = ActivationResponse.success(),
                           logs: ActivationLogs = ActivationLogs(),
                           version: SemVer = SemVer(),
                           publish: Boolean = false,
                           annotations: Parameters = Parameters(),
                           duration: Option[Long] = None)
    extends WhiskEntity(EntityName(activationId.asString), "activation") {

  require(cause != null, "cause undefined")
  require(start != null, "start undefined")
  require(end != null, "end undefined")
  require(response != null, "response undefined")

  def toJson = WhiskActivation.serdes.write(this).asJsObject

  /**
   * This the activation summary as computed by the database view.
   * Strictly used in view testing to enforce alignment.
   */
  override def summaryAsJson = {
    import WhiskActivation.instantSerdes

    def actionOrNot() = {
      if (end != Instant.EPOCH) {
        Map(
          "end" -> end.toJson,
          "duration" -> (duration getOrElse (end.toEpochMilli - start.toEpochMilli)).toJson,
          "statusCode" -> response.statusCode.toJson)
      } else Map.empty
    }

    JsObject(
      super.summaryAsJson.fields - "updated" +
        ("activationId" -> activationId.toJson) +
        ("start" -> start.toJson) ++
        cause.map(("cause" -> _.toJson)) ++
        actionOrNot())
  }

  def resultAsJson = response.result.toJson.asJsObject

  def toExtendedJson(removeFields: Seq[String] = Seq.empty, addFields: Map[String, JsValue] = Map.empty) = {
    val JsObject(baseFields) = WhiskActivation.serdes.write(this).asJsObject

    val newFields = (baseFields - "response") + ("response" -> response.toExtendedJson) -- removeFields ++ addFields
    if (end != Instant.EPOCH) {
      val durationValue = (duration getOrElse (end.toEpochMilli - start.toEpochMilli)).toJson
      JsObject(newFields + ("duration" -> durationValue))
    } else {
      JsObject(newFields - "end")
    }
  }

  def metadata =
    copy(response = response.withoutResult, annotations = Parameters(), logs = ActivationLogs())
      .revision[WhiskActivation](rev)

  def withoutResult =
    copy(response = response.withoutResult)
      .revision[WhiskActivation](rev)

  def withoutLogsOrResult =
    copy(response = response.withoutResult, logs = ActivationLogs()).revision[WhiskActivation](rev)

  def withoutLogs = copy(logs = ActivationLogs()).revision[WhiskActivation](rev)

  def withLogs(logs: ActivationLogs) = copy(logs = logs).revision[WhiskActivation](rev)

  def isTimedoutActivation = annotations.getAs[Boolean](WhiskActivation.timeoutAnnotation).getOrElse(false)

}

object WhiskActivation
    extends DocumentFactory[WhiskActivation]
    with WhiskEntityQueries[WhiskActivation]
    with DefaultJsonProtocol {

  /** Some field names for annotations */
  val pathAnnotation = "path"
  val bindingAnnotation = "binding"
  val kindAnnotation = "kind"
  val limitsAnnotation = "limits"
  val topmostAnnotation = "topmost"
  val causedByAnnotation = "causedBy"
  val initTimeAnnotation = "initTime"
  val waitTimeAnnotation = "waitTime"
  val conductorAnnotation = "conductor"
  val timeoutAnnotation = "timeout"

  val memory = "memory"
  val duration = "duration"
  val statusCode = "statusCode"

  /** Some field names for compositions */
  val actionField = "action"
  val paramsField = "params"
  val stateField = "state"
  val valueField = "value"

  protected[entity] implicit val instantSerdes: RootJsonFormat[Instant] = new RootJsonFormat[Instant] {
    def write(t: Instant) = t.toEpochMilli.toJson

    def read(value: JsValue) =
      Try {
        value match {
          case JsString(t) => Instant.parse(t)
          case JsNumber(i) => Instant.ofEpochMilli(i.bigDecimal.longValue)
          case _           => deserializationError("timestamp malformed")
        }
      } getOrElse deserializationError("timestamp malformed")
  }

  override val collectionName = "activations"

  /** The main view for activations, keyed by namespace, sorted by date. */
  override lazy val view = WhiskQueries.view(WhiskQueries.dbConfig.activationsDdoc, collectionName)

  /**
   * A view for activations in a namespace additionally keyed by action name
   * (and package name if present) sorted by date.
   */
  lazy val filtersView = WhiskQueries.view(WhiskQueries.dbConfig.activationsFilterDdoc, collectionName)

  override implicit val serdes = jsonFormat13(WhiskActivation.apply)

  // Caching activations doesn't make much sense in the common case as usually,
  // an activation is only asked for once.
  override val cacheEnabled = false

  /**
   * Queries datastore for activation records which have an entity name matching the
   * given parameter.
   *
   * @return list of records as JSON object if docs parameter is false, as Left
   *         and a list of the WhiskActivations if including the full record, as Right
   */
  def listActivationsMatchingName(db: ArtifactStore[WhiskActivation],
                                  namespace: EntityPath,
                                  path: EntityPath,
                                  skip: Int,
                                  limit: Int,
                                  includeDocs: Boolean = false,
                                  since: Option[Instant] = None,
                                  upto: Option[Instant] = None,
                                  stale: StaleParameter = StaleParameter.No)(
    implicit transid: TransactionId): Future[Either[List[JsObject], List[WhiskActivation]]] = {
    import WhiskQueries.TOP
    val convert = if (includeDocs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    val startKey = List(namespace.addPath(path).asString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(namespace.addPath(path).asString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, filtersView, startKey, endKey, skip, limit, reduce = false, stale, convert)
  }

  def put[Wsuper >: WhiskActivation](db: ArtifactStore[Wsuper], doc: WhiskActivation)(
    implicit transid: TransactionId,
    notifier: Option[CacheChangeNotification]): Future[DocInfo] =
    //As activations are not updated we just pass None for the old document
    super.put(db, doc, None)
}
