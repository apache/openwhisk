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

package whisk.common.tracing

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import brave.Tracing
import brave.opentracing.BraveTracer
import brave.sampler.Sampler
import io.opentracing.{Span, SpanContext, Tracer}
import io.opentracing.util.GlobalTracer
import io.opentracing.propagation.{Format, TextMapExtractAdapter, TextMapInjectAdapter}
import zipkin2.reporter.{AsyncReporter, Sender}
import zipkin2.reporter.okhttp3.OkHttpSender
import pureconfig._
import whisk.common.{LogMarkerToken, TransactionId}
import whisk.core.ConfigKeys

import scala.collection.mutable
import scala.ref.WeakReference

/**
 * OpenTracing based implementation for tracing
 */
class OpenTracer(val tracer: Tracer) extends WhiskTracer {

  /**
   * Start a Trace for given service.
   *
   * @param transactionId transactionId to which this Trace belongs.
   * @return TracedRequest which provides details about current service being traced.
   */
  override def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {

    //initialize list for this transactionId
    val spanList: List[WeakReference[Span]] = TracingCacheProvider.spanMap.get(transactionId.meta.id).getOrElse(Nil)

    val spanBuilder = tracer
      .buildSpan(logMarker.action)
      .withTag("transactionId", transactionId.meta.id)

    val activeSpan: Span = spanList match {
      case Nil =>
        TracingCacheProvider.contextMap
          .get(transactionId.meta.id)
          .map(spanBuilder.asChildOf(_).startActive(true).span())
          .getOrElse(spanBuilder.ignoreActiveSpan().startActive(true).span())

      case _ =>
        spanList.head.get
          .map(spanBuilder.asChildOf(_).startActive(true).span())
          .getOrElse(spanBuilder.ignoreActiveSpan().startActive(true).span())

    }
    //add active span to list
    TracingCacheProvider.spanMap.put(transactionId.meta.id, spanList.::(new WeakReference(activeSpan)))
  }

  /**
   * Finish a Trace associated with given transactionId.
   *
   * @param transactionId
   */
  override def finishSpan(transactionId: TransactionId): Unit = {
    clear(transactionId)
  }

  /**
   * Register error
   *
   * @param transactionId
   */
  override def error(transactionId: TransactionId): Unit = {
    clear(transactionId)
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  override def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = {
    TracingCacheProvider.spanMap.get(transactionId.meta.id) match {
      case Some(spanList) => {
        val map: mutable.Map[String, String] = mutable.HashMap()
        //inject latest span context in map
        spanList.head.get match {
          case Some(span) => {
            tracer.inject(span.context(), Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(map.asJava))
            Some(map.toMap)
          }
          case _ => None
        }
      }
      case None => None
    }
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  override def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]) = {
    context match {
      case Some(scalaMap) => {
        val ctx: SpanContext = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(scalaMap.asJava))
        TracingCacheProvider.contextMap.put(transactionId.meta.id, ctx)
      }
      case None =>
    }
  }

  private def clear(transactionId: TransactionId): Unit = {
    TracingCacheProvider.spanMap.get(transactionId.meta.id).foreach {
      case head :: tail =>
        head.get.foreach(_.finish)
        if (tail.isEmpty) {
          TracingCacheProvider.spanMap.remove(transactionId.meta.id)
          TracingCacheProvider.contextMap.remove(transactionId.meta.id)
        } else {
          TracingCacheProvider.spanMap.put(transactionId.meta.id, tail)
        }

      case _ =>
    }
  }
}

trait WhiskTracer {
  def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {}
  def finishSpan(transactionId: TransactionId): Unit = {}
  def error(transactionId: TransactionId): Unit = {}
  def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = None
  def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]): Unit = {}
}

object WhiskTracerProvider {
  val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)

  val tracer: WhiskTracer = createTracer(tracingConfig)

  private def createTracer(tracingConfig: TracingConfig): WhiskTracer = {

    tracingConfig.zipkin match {
      case Some(zipkinConfig) => {
        val zipkinConfig = tracingConfig.zipkin.get
        if (!GlobalTracer.isRegistered) {
          val sender: Sender = OkHttpSender.create(zipkinConfig.getUrl)
          val spanReporter = AsyncReporter.create(sender)
          val braveTracing = Tracing
            .newBuilder()
            .localServiceName(tracingConfig.component)
            .spanReporter(spanReporter)
            .sampler(Sampler.create(zipkinConfig.sampleRate.toFloat))
            .build();

          //register with OpenTracing
          GlobalTracer.register(BraveTracer.create(braveTracing))

          sys.addShutdownHook({spanReporter.close()})
        }
      }
      case None =>
    }

    if (GlobalTracer.isRegistered)
      new OpenTracer(GlobalTracer.get())
    else
      NoopTracer
  }
}

private object NoopTracer extends WhiskTracer
case class TracingConfig(component: String, cacheExpiry: Duration, zipkin: Option[ZipkinConfig] = None)
case class ZipkinConfig(url: String, sampleRate: String) {
  def getUrl = s"$url/api/v2/spans"
}
