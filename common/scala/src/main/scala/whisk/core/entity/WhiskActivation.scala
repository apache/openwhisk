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
import scala.util.Try

import spray.json._
import spray.json.DefaultJsonProtocol._
import whisk.common.TransactionId
import whisk.core.database.ArtifactStore
import whisk.core.database.DocumentFactory
import whisk.core.database.StaleParameter
import whisk.core.WhiskConfig
import whisk.core.WhiskConfig.{dbActivationsDesignDoc, dbActivationsFilterDesignDoc}

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
    extends WhiskEntity(EntityName(activationId.asString)) {

  require(cause != null, "cause undefined")
  require(start != null, "start undefined")
  require(end != null, "end undefined")
  require(response != null, "response undefined")

  def toJson = WhiskActivation.serdes.write(this).asJsObject

  /** This the activation summary as computed by the database view. Strictly used for testing. */
  override def summaryAsJson = {
    import WhiskActivation.instantSerdes
    val summary = JsObject(super.summaryAsJson.fields + ("activationId" -> activationId.toJson))

    def actionOrNot() = {
      if (end != Instant.EPOCH) {
        Map(
          "end" -> end.toJson,
          "duration" -> (duration getOrElse (end.toEpochMilli - start.toEpochMilli)).toJson,
          "statusCode" -> response.statusCode.toJson)
      } else Map.empty
    }

    if (WhiskActivation.mainDdoc.endsWith(".v2")) {
      JsObject(
        summary.fields +
          ("start" -> start.toJson) ++
          cause.map(("cause" -> _.toJson)) ++
          actionOrNot())
    } else summary
  }

  def resultAsJson = response.result.toJson.asJsObject

  def toExtendedJson = {
    val JsObject(baseFields) = WhiskActivation.serdes.write(this).asJsObject
    val newFields = (baseFields - "response") + ("response" -> response.toExtendedJson)
    if (end != Instant.EPOCH) {
      val durationValue = (duration getOrElse (end.toEpochMilli - start.toEpochMilli)).toJson
      JsObject(newFields + ("duration" -> durationValue))
    } else {
      JsObject(newFields - "end")
    }
  }

  def withoutLogsOrResult = {
    copy(response = response.withoutResult, logs = ActivationLogs()).revision[WhiskActivation](rev)
  }

  def withoutLogs = copy(logs = ActivationLogs()).revision[WhiskActivation](rev)
  def withLogs(logs: ActivationLogs) = copy(logs = logs).revision[WhiskActivation](rev)
}

object WhiskActivation
    extends DocumentFactory[WhiskActivation]
    with WhiskEntityQueries[WhiskActivation]
    with DefaultJsonProtocol {

  private implicit val instantSerdes = new RootJsonFormat[Instant] {
    def write(t: Instant) = t.toEpochMilli.toJson

    def read(value: JsValue) =
      Try {
        value match {
          case JsString(t) => Instant.parse(t)
          case JsNumber(i) => Instant.ofEpochMilli(i.bigDecimal.longValue)
          case _           => deserializationError("timetsamp malformed")
        }
      } getOrElse deserializationError("timetsamp malformed 2")
  }

  override val collectionName = "activations"

  // FIXME: reading the design doc from sys.env instead of a canonical property reader
  // because WhiskConfig requires a logger, which requires an actor system, neither of
  // which are readily available here; rather than introduce significant refactoring,
  // defer this fix until WhiskConfig is refactored itself, which is planned to introduce
  // type safe properties
  private val mainDdoc = WhiskConfig.readFromEnv(dbActivationsDesignDoc).getOrElse("whisks.v2")
  private val filtersDdoc = WhiskConfig.readFromEnv(dbActivationsFilterDesignDoc).getOrElse("whisks-filters.v2")

  /** The main view for activations, keyed by namespace, sorted by date. */
  override lazy val view = WhiskEntityQueries.view(mainDdoc, collectionName)

  /**
   * A view for activations in a namespace additionally keyed by action name
   * (and package name if present) sorted by date.
   */
  private val filtersView = WhiskEntityQueries.view(filtersDdoc, collectionName)

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
    import WhiskEntityQueries.TOP
    val convert = if (includeDocs) Some((o: JsObject) => Try { serdes.read(o) }) else None
    val startKey = List(namespace.addPath(path).asString, since map { _.toEpochMilli } getOrElse 0)
    val endKey = List(namespace.addPath(path).asString, upto map { _.toEpochMilli } getOrElse TOP, TOP)
    query(db, filtersView, startKey, endKey, skip, limit, reduce = false, stale, convert)
  }
}
