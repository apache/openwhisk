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

package org.apache.openwhisk.core.database.cosmosdb.cache

import akka.Done
import akka.event.slf4j.SLF4JLogging
import com.microsoft.azure.documentdb.Document
import com.microsoft.azure.documentdb.changefeedprocessor.ChangeFeedObserverContext
import kamon.metric.MeasurementUnit
import org.apache.openwhisk.common.{LogMarkerToken, MetricEmitter}
import org.apache.openwhisk.core.database.CacheInvalidationMessage
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBConstants
import org.apache.openwhisk.core.entity.CacheKey
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBUtil.unescapeId

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}

class WhiskChangeEventObserver(config: InvalidatorConfig, eventProducer: EventProducer) extends ChangeFeedObserver {
  import WhiskChangeEventObserver._

  override def process(context: ChangeFeedObserverContext, docs: Seq[Document]): Unit = {
    //Each observer is called from a pool managed by CosmosDB ChangeFeedProcessor
    //So its fine to have a blocking wait. If this fails then batch would be reread and
    //retried thus ensuring at-least-once semantics
    val f = eventProducer.send(processDocs(docs, config))
    Await.result(f, config.feedPublishTimeout)
    MetricEmitter.emitCounterMetric(feedCounter, docs.size)
    recordLag(context, docs.last)
  }
}

trait EventProducer {
  def send(msg: Seq[String]): Future[Done]
}

object WhiskChangeEventObserver extends SLF4JLogging {
  val instanceId = "cache-invalidator"
  private val feedCounter =
    LogMarkerToken("cosmosdb", "change_feed", "count", tags = Map("collection" -> "whisks"))(MeasurementUnit.none)
  private val lags = new TrieMap[String, LogMarkerToken]

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
    MetricEmitter.emitGaugeMetric(gaugeToken, lag)
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

  def processDocs(docs: Seq[Document], config: InvalidatorConfig): Seq[String] = {
    docs
      .filter { doc =>
        val cid = Option(doc.getString(CosmosDBConstants.clusterId))
        val currentCid = config.clusterId

        //only if current clusterId is configured do a check
        currentCid match {
          case Some(_) => cid != currentCid
          case None    => true
        }
      }
      .map { doc =>
        val id = unescapeId(doc.getId)
        log.debug("Changed doc [{}]", id)
        val event = CacheInvalidationMessage(CacheKey(id), instanceId)
        event.serialize
      }
  }

}
