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
import com.azure.data.cosmos.CosmosItemProperties
import com.azure.data.cosmos.internal.changefeed.ChangeFeedObserverContext
import com.google.common.base.Throwables
import kamon.metric.MeasurementUnit
import org.apache.openwhisk.common.{LogMarkerToken, Logging, MetricEmitter}
import org.apache.openwhisk.core.database.CacheInvalidationMessage
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBConstants
import org.apache.openwhisk.core.database.cosmosdb.CosmosDBUtil.unescapeId
import org.apache.openwhisk.core.entity.CacheKey

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class WhiskChangeEventObserver(config: InvalidatorConfig, eventProducer: EventProducer)(implicit ec: ExecutionContext,
                                                                                        log: Logging)
    extends ChangeFeedObserver {
  import WhiskChangeEventObserver._

  override def process(context: ChangeFeedObserverContext, docs: Seq[CosmosItemProperties]): Future[Done] = {
    //Each observer is called from a pool managed by CosmosDB ChangeFeedProcessor
    //So its fine to have a blocking wait. If this fails then batch would be reread and
    //retried thus ensuring at-least-once semantics
    val f = eventProducer.send(processDocs(docs, config))
    f.andThen {
      case Success(_) =>
        MetricEmitter.emitCounterMetric(feedCounter, docs.size)
        recordLag(context, docs.last)
      case Failure(t) =>
        log.warn(this, "Error occurred while sending cache invalidation message " + Throwables.getStackTraceAsString(t))
    }
  }
}

trait EventProducer {
  def send(msg: Seq[String]): Future[Done]
}

object WhiskChangeEventObserver {
  val instanceId = "cache-invalidator"
  private val feedCounter =
    LogMarkerToken("cosmosdb", "change_feed", "count", tags = Map("collection" -> "whisks"))(MeasurementUnit.none)
  private val lags = new TrieMap[String, LogMarkerToken]

  /**
   * Records the current lag on per partition basis. In ideal cases the lag should not continue to increase
   */
  def recordLag(context: ChangeFeedObserverContext, lastDoc: CosmosItemProperties): Unit = {
    val sessionToken = context.getFeedResponse.sessionToken()
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

  def processDocs(docs: Seq[CosmosItemProperties], config: InvalidatorConfig)(implicit log: Logging): Seq[String] = {
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
        val id = unescapeId(doc.id())
        log.info(this, s"Changed doc [$id]")
        val event = CacheInvalidationMessage(CacheKey(id), instanceId)
        event.serialize
      }
  }

}
