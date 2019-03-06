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

package org.apache.openwhisk.core.database.cosmosdb.lambda

import akka.Done
import akka.event.slf4j.SLF4JLogging
import com.microsoft.azure.documentdb.Document
import com.microsoft.azure.documentdb.changefeedprocessor.ChangeFeedObserverContext
import com.typesafe.config.ConfigFactory
import kamon.metric.MeasurementUnit
import org.apache.openwhisk.common.TransactionId.systemPrefix
import org.apache.openwhisk.common.{LogMarkerToken, MetricEmitter, TransactionId}
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBUtil.unescapeId
import org.apache.openwhisk.core.entity.{DocRevision, WhiskAction}
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class WhisksChangeProcessor extends BaseObserver with SLF4JLogging {
  import WhisksChangeProcessor._

  override def process(context: ChangeFeedObserverContext, docs: Seq[Document]): Unit = {
    //Each observer is called from a pool managed by CosmosDB ChangeFeedProcessor
    //So its fine to have a blocking wait. If this fails then batch would be reread and
    //retried thus ensuring at-least-once semantics
    //TODO Generate tid per action
    implicit val tid = TransactionId(systemPrefix + "lambdaBuilder")
    implicit val ec: ExecutionContext = executionContext
    val actions = processDocs(docs, config)

    //Convert the result to Try such that one single failure does not bring down whole
    val fs = actions.map(a => actionConsumer.send(a).transform(Success(_, a)))
    val f = Future.sequence(fs)

    //TODO The timeout should be on per action basis
    val results = Await.result(f, config.lambdaProcessTimeout)

    //TODO If failure is due to error in adding to queue then they should be retried
    //TODO How to handle stuff which cannot be processed at all
    val failures = results.collect { case (Failure(t), a) => (Failure(t), a) }
    failures.foreach {
      case (Failure(t), a) => log.warn(s"Not able to process action ${a.fullyQualifiedName(false)}", t)
    }
    MetricEmitter.emitCounterMetric(feedCounter, docs.size)
    recordLag(context, docs.last)
  }
}

trait WhiskActionConsumer {
  def send(action: WhiskAction)(implicit tid: TransactionId): Future[Done]
}

object WhisksChangeProcessor extends SLF4JLogging {
  val instanceId = "cache-invalidator"
  private val feedCounter =
    LogMarkerToken("cosmosdb", "change_feed", "count", tags = Map("collection" -> "whisks"))(MeasurementUnit.none)
  private var _actionConsumer: WhiskActionConsumer = _
  private var _ec: ExecutionContext = _
  private var _config: LambdaBuilderServiceConfig = LambdaBuilderConfig.getLambdaBuilderConfig()(ConfigFactory.load())
  private val lags = new TrieMap[String, LogMarkerToken]
  private val actionEntity = Some("action")

  def actionConsumer: WhiskActionConsumer = {
    require(_actionConsumer != null, "WhiskActionConsumer yet not initialized")
    _actionConsumer
  }
  def actionConsumer_=(actionConsumer: WhiskActionConsumer): Unit = _actionConsumer = actionConsumer

  def config: LambdaBuilderServiceConfig = _config
  def config_=(config: LambdaBuilderServiceConfig): Unit = _config = config

  def executionContext: ExecutionContext = {
    require(_ec != null, "ExecutionContext yet not initialized")
    _ec
  }
  def executionContext_=(ec: ExecutionContext): Unit = _ec = ec

  /**
   * Records the current lag on per partition basis. In ideal cases the lag should not continue to increase
   */
  def recordLag(context: ChangeFeedObserverContext, lastDoc: Document): Unit = {
    val sessionToken = context.getFeedResponde.getSessionToken
    val lsnRef = lastDoc.get("_lsn")
    require(lsnRef != null, s"Non lsn defined in document $lastDoc")

    val lsn = lsnRef.toString.toLong
    val sessionLsn = getSessionLsn(sessionToken)
    val lag = sessionLsn - lsn
    val partitionKey = context.getPartitionKeyRangeId
    val gaugeToken = lags.getOrElseUpdate(partitionKey, createLagToken(partitionKey))
  }

  private def createLagToken(partitionKey: String) = {
    LogMarkerToken("cosmosdb", "change_feed", "lag", tags = Map("collection" -> "whisks", "pk" -> partitionKey))(
      MeasurementUnit.none)
  }

  def getSessionLsn(token: String): Long = {
    // Session Token can be in two formats. Either {PartitionKeyRangeId}:{LSN}
    // or {PartitionKeyRangeId}:{Version}#{GlobalLSN}
    // See https://github.com/Azure/azure-documentdb-changefeedprocessor-dotnet/pull/113/files#diff-54cbd8ddcc33cab4120c8af04869f881
    val parsedSessionToken = token.substring(token.indexOf(":") + 1)
    val segments = parsedSessionToken.split("#")
    val lsn = if (segments.size < 2) segments(0) else segments(1)
    lsn.toLong
  }

  def processDocs(docs: Seq[Document], config: LambdaBuilderServiceConfig): Seq[WhiskAction] = {
    docs
      .filter { doc =>
        val entityType = Option(doc.getString("entityType"))
        entityType == actionEntity
      }
      .map { doc =>
        val id = unescapeId(doc.getId)
        log.debug("Changed doc [{}]", id)
        val js = toWhiskJsonDoc(doc)
        val wa = WhiskAction.serdes.read(js)
        val rev = js.fields("_rev").convertTo[String]
        wa.revision[WhiskAction](DocRevision(rev))
      }
      .filter { wa =>
        //Ignore the health actions
        !wa.name.name.startsWith("invokerHealthTestAction")
      }
  }

  //TODO Move these util method to CosmosDBArtifactStore companion
  private def toWhiskJsonDoc(doc: Document): JsObject = {
    val js = doc.toJson.parseJson.asJsObject
    toWhiskJsonDoc(js, doc.getId, Some(JsString(doc.getETag)))
  }

  private def toWhiskJsonDoc(js: JsObject, id: String, etag: Option[JsString]): JsObject = {
    val fieldsToAdd = Seq(("_id", Some(JsString(unescapeId(id)))), ("_rev", etag))
    transform(stripInternalFields(js), fieldsToAdd, Seq.empty)
  }

  private def transform(json: JsObject, fieldsToAdd: Seq[(String, Option[JsValue])], fieldsToRemove: Seq[String]) = {
    val fields = json.fields ++ fieldsToAdd.flatMap(f => f._2.map((f._1, _))) -- fieldsToRemove
    JsObject(fields)
  }

  private def stripInternalFields(js: JsObject) = {
    //Strip out all field name starting with '_' which are considered as db specific internal fields
    JsObject(js.fields.filter { case (k, _) => !k.startsWith("_") && k != "id" })
  }

}
