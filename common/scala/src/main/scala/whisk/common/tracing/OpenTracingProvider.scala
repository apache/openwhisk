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
import io.opentracing.{ActiveSpan, SpanContext}
import io.opentracing.util.GlobalTracer
import io.opentracing.propagation.{Format, TextMapExtractAdapter, TextMapInjectAdapter}
import zipkin.reporter.Sender
import zipkin.reporter.okhttp3.OkHttpSender
import zipkin.reporter.AsyncReporter
import zipkin.reporter.Reporter
import pureconfig._
import whisk.common.{LogMarkerToken, TransactionId}
import whisk.core.ConfigKeys

/**
 * OpenTracing based implementation for tracing
 */
object OpenTracingProvider {

  private val spanMap: mutable.Map[Long, mutable.ListBuffer[ActiveSpan]] =
    TrieMap[Long, mutable.ListBuffer[ActiveSpan]]()
  private val contextMap: mutable.Map[Long, SpanContext] = TrieMap[Long, SpanContext]()

  var enabled = false; 

  def apply(serviceName: String): Unit = {
    configureTracer(serviceName)
  }

  /**
   * Start a Trace for given service.
   *
   * @param transactionId transactionId to which this Trace belongs.
   * @return TracedRequest which provides details about current service being traced.
   */
  def startTrace(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {
    if (enabled) {
      transactionId.copy(transactionId.meta)
      var activeSpan: Option[ActiveSpan] = None
      spanMap.get(transactionId.meta.id) match {
        case Some(spanList) => {
          //create a child trace
          activeSpan = Some(GlobalTracer.get().buildSpan(logMarker.action).asChildOf(spanList.last).startActive())
        }
        case None => {
          contextMap.get(transactionId.meta.id) match {
            case Some(context) => {
              activeSpan = Some(GlobalTracer.get().buildSpan(logMarker.action).asChildOf(context).startActive())
            }
            case None => {
              activeSpan = Some(GlobalTracer.get().buildSpan(logMarker.action).startActive())
            }
          }
          //initialize list for this transactionId
          val list: mutable.ListBuffer[ActiveSpan] = mutable.ListBuffer()
          spanMap.put(transactionId.meta.id, list)
        }
      }

      //add active span to list
      if (activeSpan.isDefined)
        spanMap.get(transactionId.meta.id).map(_.+=:(activeSpan.get))
    }
  }

  /**
   * Finish a Trace associated with given transactionId.
   *
   * @param transactionId
   */
  def finish(logMarker: LogMarkerToken, transactionId: TransactionId): Unit = {
    if (enabled)
      clear(transactionId)
  }

  /**
   * Register error
   *
   * @param transactionId
   */
  def error(transactionId: TransactionId): Unit = {
    if (enabled)
      clear(transactionId)
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = {
    var contextMap: Option[Map[String, String]] = None
    if (enabled) {
      spanMap.get(transactionId.meta.id) match {
        case Some(spanList) => {
          var map: java.util.Map[String, String] = new java.util.HashMap()
          //inject latest span context in map
          GlobalTracer.get().inject(spanList.last.context(), Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(map))
          contextMap = Some(scala.collection.JavaConverters.mapAsScalaMapConverter(map).asScala.toMap)
        }
        case None => None
      }
    }
    contextMap
  }

  /**
   * Get the current TraceContext which can be used for downstream services
   *
   * @param transactionId
   * @return
   */
  def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]) = {
    if (enabled) {
      context match {
        case Some(scalaMap) => {
          var javaMap: java.util.Map[String, String] =
            scala.collection.JavaConverters.mapAsJavaMapConverter(scalaMap).asJava
          var ctx: SpanContext = GlobalTracer.get().extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(javaMap))
          contextMap.put(transactionId.meta.id, ctx)
        }
        case None =>
      }
    }
  }

  def clear(transactionId: TransactionId): Unit = {
    spanMap.get(transactionId.meta.id) match {
      case Some(spanList) => {
        spanList.last.deactivate()
        spanList.remove(spanList.size - 1)
        if (spanList.isEmpty) {
          spanMap.remove(transactionId.meta.id)
          contextMap.remove(transactionId.meta.id)
        }

      }
      case None =>
    }

  }

  def configureTracer(componentName: String): Unit = {
    val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)

    enabled = tracingConfig.enabled
    if (enabled) {
      val tracer = tracingConfig.tracer

      if (tracer.equalsIgnoreCase("zipkin")) {
        //configure opentracing with zipkin
        val sender: Sender = OkHttpSender.create(
          "http://" +
            tracingConfig.zipkin.get("host").getOrElse("localhost") + ":" +
            tracingConfig.zipkin.get("port").getOrElse(9411) + "/api/v1/spans")

        val reporter: Reporter[zipkin.Span] = AsyncReporter.builder(sender).build()
        GlobalTracer.register(
          BraveTracer.create(
            Tracing
              .newBuilder()
              .localServiceName(componentName)
              .reporter(reporter)
              .build()))
      }
    }
  }
  case class TracingConfig(enabled: Boolean, tracer: String, zipkin: Map[String, String])

}
