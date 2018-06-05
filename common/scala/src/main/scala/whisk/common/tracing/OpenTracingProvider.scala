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

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
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

/**
 * OpenTracing based implementation for tracing
 */
class OpenTracer(val tracer: Tracer) extends WhiskTracer {

  private val spanMap: mutable.Map[String, mutable.ListBuffer[Span]] =
    TrieMap[String, mutable.ListBuffer[Span]]()
  private val contextMap: mutable.Map[String, SpanContext] = TrieMap[String, SpanContext]()

  /**
   * Start a Trace for given service.
   *
   * @param transactionId transactionId to which this Trace belongs.
   * @return TracedRequest which provides details about current service being traced.
   */
  override def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {

    val activeSpan: Option[Span] =
      spanMap.get(transactionId.meta.id) match {
        case Some(spanList) => {
          //create a child span
          Some(
            tracer
              .buildSpan(logMarker.action)
              .withTag("transactionId", transactionId.meta.id)
              .asChildOf(spanList.last)
              .startActive(true)
              .span())
        }
        case None => {
          //initialize list for this transactionId
          val list: mutable.ListBuffer[Span] = mutable.ListBuffer()
          spanMap.put(transactionId.meta.id, list)

          contextMap.get(transactionId.meta.id) match {
            case Some(context) => {
              //create child span if we have a tracing context
              Some(
                tracer
                  .buildSpan(logMarker.action)
                  .withTag("transactionId", transactionId.meta.id)
                  .asChildOf(context)
                  .startActive(true)
                  .span())
            }
            case None => {
              Some(
                tracer
                  .buildSpan(logMarker.action)
                  .ignoreActiveSpan()
                  .withTag("transactionId", transactionId.meta.id)
                  .startActive(true)
                  .span())
            }
          }
        }
      }

    //add active span to list
    if (activeSpan.isDefined)
      spanMap.get(transactionId.meta.id).map(_.+=:(activeSpan.get))
  }

  /**
   * Finish a Trace associated with given transactionId.
   *
   * @param transactionId
   */
  override def finishSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {
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
    var contextMap: Option[Map[String, String]] = None
    spanMap.get(transactionId.meta.id) match {
      case Some(spanList) => {
        var map: java.util.Map[String, String] = new java.util.HashMap()
        //inject latest span context in map
        tracer.inject(spanList.last.context(), Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(map))
        contextMap = Some(scala.collection.JavaConverters.mapAsScalaMapConverter(map).asScala.toMap)
      }
      case None => None
    }
    contextMap
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
        var javaMap: java.util.Map[String, String] =
          scala.collection.JavaConverters.mapAsJavaMapConverter(scalaMap).asJava
        var ctx: SpanContext = tracer.extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(javaMap))
        contextMap.put(transactionId.meta.id, ctx)
      }
      case None =>
    }
  }

  private def clear(transactionId: TransactionId): Unit = {
    spanMap.get(transactionId.meta.id) match {
      case Some(spanList) => {
        spanList.last.finish
        spanList.remove(spanList.size - 1)
        if (spanList.isEmpty) {
          spanMap.remove(transactionId.meta.id)
          contextMap.remove(transactionId.meta.id)
        }

      }
      case None =>
    }
  }
}

trait WhiskTracer {
  def startSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {}
  def finishSpan(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {}
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
          val sender: Sender = OkHttpSender.create(zipkinConfig.url)
          val spanReporter = AsyncReporter.create(sender)
          val braveTracing = Tracing
            .newBuilder()
            .localServiceName(tracingConfig.component)
            .spanReporter(spanReporter)
            .sampler(Sampler.create(zipkinConfig.sampleRate.toFloat))
            .build();

          //register with OpenTracing
          GlobalTracer.register(BraveTracer.create(braveTracing))
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
case class TracingConfig(component: String, zipkin: Option[ZipkinConfig] = None)
case class ZipkinConfig(host: String, port: Int, sampleRate: String) {
  def url = s"http://$host:$port/api/v2/spans"
}
