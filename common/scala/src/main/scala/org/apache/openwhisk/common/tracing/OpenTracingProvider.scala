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

package org.apache.openwhisk.common.tracing

import java.util.concurrent.TimeUnit

import brave.Tracing
import brave.opentracing.BraveTracer
import brave.sampler.Sampler
import com.github.benmanes.caffeine.cache.{Caffeine, Ticker}
import io.opentracing.propagation.{Format, TextMapExtractAdapter, TextMapInjectAdapter}
import io.opentracing.util.GlobalTracer
import io.opentracing.{Span, SpanContext, Tracer}
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common.{LogMarkerToken, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import zipkin2.reporter.okhttp3.OkHttpSender
import zipkin2.reporter.{AsyncReporter, Sender}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration.Duration

/**
 * OpenTracing based implementation for tracing
 */
class OpenTracer(val tracer: Tracer, tracingConfig: TracingConfig, ticker: Ticker = SystemTicker) extends WhiskTracer {
  val spanMap = configureCache[String, List[Span]]()
  val contextMap = configureCache[String, SpanContext]()

  /**
   * Start a Trace for given service.
   *
   * @param transactionId transactionId to which this Trace belongs.
   * @return TracedRequest which provides details about current service being traced.
   */
  override def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {
    //initialize list for this transactionId
    val spanList = spanMap.getOrElse(transactionId.meta.id, Nil)

    val spanBuilder = tracer
      .buildSpan(logMarker.action)
      .withTag("transactionId", transactionId.meta.id)

    val active = spanList match {
      case Nil =>
        //Check if any active context then resume from that else create a fresh span
        contextMap
          .get(transactionId.meta.id)
          .map(spanBuilder.asChildOf)
          .getOrElse(spanBuilder.ignoreActiveSpan())
          .startActive(true)
          .span()
      case head :: _ =>
        //Create a child span of current head
        spanBuilder.asChildOf(head).startActive(true).span()
    }
    //add active span to list
    spanMap.put(transactionId.meta.id, active :: spanList)
  }

  /**
   * Finish a Trace associated with given transactionId.
   *
   * @param transactionId
   */
  override def finishSpan(transactionId: TransactionId): Unit = {
    clear(transactionId, withErrorMessage = None)
  }

  /**
   * Register error
   *
   * @param transactionId
   */
  override def error(transactionId: TransactionId, message: => String): Unit = {
    clear(transactionId, withErrorMessage = Some(message))
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  override def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = {
    spanMap
      .get(transactionId.meta.id)
      .flatMap(_.headOption)
      .map { span =>
        val map = mutable.Map.empty[String, String]
        tracer.inject(span.context(), Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(map.asJava))
        map.toMap
      }
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  override def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]) = {
    context.foreach { scalaMap =>
      val ctx: SpanContext = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(scalaMap.asJava))
      contextMap.put(transactionId.meta.id, ctx)
    }
  }

  private def clear(transactionId: TransactionId, withErrorMessage: Option[String]): Unit = {
    spanMap.get(transactionId.meta.id).foreach {
      case head :: Nil =>
        withErrorMessage.foreach(setErrorTags(head, _))
        head.finish()
        spanMap.remove(transactionId.meta.id)
        contextMap.remove(transactionId.meta.id)
      case head :: tail =>
        withErrorMessage.foreach(setErrorTags(head, _))
        head.finish()
        spanMap.put(transactionId.meta.id, tail)
      case Nil =>
    }
  }

  private def setErrorTags(span: Span, message: => String): Unit = {
    span.setTag("error", true)
    span.setTag("message", message)
  }

  private def configureCache[T, R](): collection.concurrent.Map[T, R] =
    Caffeine
      .newBuilder()
      .ticker(ticker)
      .expireAfterAccess(tracingConfig.cacheExpiry.toSeconds, TimeUnit.SECONDS)
      .build()
      .asMap()
      .asScala
      .asInstanceOf[collection.concurrent.Map[T, R]]
}

trait WhiskTracer {
  def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {}
  def finishSpan(transactionId: TransactionId): Unit = {}
  def error(transactionId: TransactionId, message: => String): Unit = {}
  def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = None
  def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]): Unit = {}
}

object WhiskTracerProvider {
  val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)

  val tracer: WhiskTracer = createTracer(tracingConfig)

  private def createTracer(tracingConfig: TracingConfig): WhiskTracer = {

    tracingConfig.zipkin match {
      case Some(zipkinConfig) => {
        if (!GlobalTracer.isRegistered) {
          val sender: Sender = OkHttpSender.create(zipkinConfig.generateUrl)
          val spanReporter = AsyncReporter.create(sender)
          val braveTracing = Tracing
            .newBuilder()
            .localServiceName(tracingConfig.component)
            .spanReporter(spanReporter)
            .sampler(Sampler.create(zipkinConfig.sampleRate.toFloat))
            .build()

          //register with OpenTracing
          GlobalTracer.register(BraveTracer.create(braveTracing))

          sys.addShutdownHook({ spanReporter.close() })
        }
      }
      case None =>
    }

    if (GlobalTracer.isRegistered)
      new OpenTracer(GlobalTracer.get(), tracingConfig)
    else
      NoopTracer
  }
}

private object NoopTracer extends WhiskTracer
case class TracingConfig(component: String, cacheExpiry: Duration, zipkin: Option[ZipkinConfig] = None)
case class ZipkinConfig(url: String, sampleRate: String) {
  def generateUrl = s"$url/api/v2/spans"
}
object SystemTicker extends Ticker {
  override def read() = {
    System.nanoTime()
  }
}
