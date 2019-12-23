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

package org.apache.openwhisk.core.database.cosmosdb

import akka.event.slf4j.SLF4JLogging
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.microsoft.azure.cosmosdb.rx.internal.ResourceThrottleRetryPolicy
import org.apache.openwhisk.common.{Counter => WhiskCounter}
import kamon.metric.{Counter, MeasurementUnit}
import org.apache.openwhisk.common.{LogMarkerToken, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.slf4j.LoggerFactory
import pureconfig._
import pureconfig.generic.auto._

import scala.util.Try

object CosmosDBAction extends Enumeration {
  val Create, Query, Get, Others = Value
}

object RetryMetricsCollector extends AppenderBase[ILoggingEvent] with SLF4JLogging {
  import CosmosDBAction._
  private val tokens =
    Map(Create -> Token(Create), Query -> Token(Query), Get -> Token(Get), Others -> Token(Others))

  val retryCounter = new WhiskCounter
  private[cosmosdb] def registerIfEnabled(): Unit = {
    val enabled = loadConfigOrThrow[Boolean](s"${ConfigKeys.cosmosdb}.retry-stats-enabled")
    if (enabled) {
      log.info("Enabling retry metrics collector")
      register()
    }
  }

  /**
   * CosmosDB uses below log message
   * ```
   * logger.warn(
   * "Operation will be retried after {} milliseconds. Current attempt {}, Cumulative delay {}",
   *                     retryDelay.toMillis(),
   *                     this.currentAttemptCount,
   *                     this.cumulativeRetryDelay,
   * exception);
   * ```
   *
   */
  override def append(e: ILoggingEvent): Unit = {
    val msg = e.getMessage
    val errorMsg = Option(e.getThrowableProxy).map(_.getMessage).getOrElse(msg)
    for {
      success <- isSuccessOrFailedRetry(msg)
      token <- tokens.get(operationType(errorMsg))
    } {
      if (success) {
        token.success.counter.increment()
        //Element 1 has the count
        val attemptCount = getRetryAttempt(e.getArgumentArray, 1)
        token.success.histogram.record(attemptCount)

        //Used mostly for test mode where tags may be disabled
        //and test need to determine if count is increased
        if (!TransactionId.metricsKamonTags) {
          retryCounter.next()
        }
      } else {
        token.failed.counter.increment()
      }
    }
  }

  def getCounter(opType: CosmosDBAction.Value, retryPassed: Boolean = true): Option[Counter] = {
    tokens.get(opType).map(t => if (retryPassed) t.success else t.failed).map { _.counter }
  }

  private def getRetryAttempt(args: Array[AnyRef], index: Int) = {
    val t = Try {
      if (args != null & args.length > index) {
        args(index) match {
          case n: Number => n.intValue
          case _         => 0
        }
      } else 0
    }
    t.getOrElse(0)
  }

  private def register(): Unit = {
    val logCtx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val retryLogger = logCtx.getLogger(classOf[ResourceThrottleRetryPolicy].getName)
    start()
    retryLogger.addAppender(this)
  }

  private def isSuccessOrFailedRetry(msg: String) = {
    if (msg.startsWith("Operation will be retried after")) Some(true)
    else if (msg.startsWith("Operation will NOT be retried")) Some(false)
    else None
  }

  private def operationType(errorMsg: String) = {
    if (errorMsg.contains("OperationType: Query")) Query
    else if (errorMsg.contains("OperationType: Create")) Create
    else if (errorMsg.contains("OperationType: Get")) Get
    else Others
  }

  private def createToken(opType: String, retryPassed: Boolean): LogMarkerToken = {
    val action = if (retryPassed) "success" else "failed"
    val tags = Map("type" -> opType)
    if (TransactionId.metricsKamonTags) LogMarkerToken("cosmosdb", "retry", action, tags = tags)(MeasurementUnit.none)
    else LogMarkerToken("cosmosdb", "retry", action, Some(opType))(MeasurementUnit.none)
  }

  private case class Token(success: LogMarkerToken, failed: LogMarkerToken)

  private object Token {
    def apply(opType: CosmosDBAction.Value): Token =
      new Token(createToken(opType.toString, retryPassed = true), createToken(opType.toString, retryPassed = false))
  }
}
