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

import com.typesafe.config.ConfigFactory
import io.opentracing.{ActiveSpan, SpanContext}
import io.opentracing.util.GlobalTracer
import whisk.common.TransactionId
import io.opentracing.propagation.{Format, TextMapExtractAdapter, TextMapInjectAdapter}
import brave.Tracing
import zipkin.reporter.Sender
import zipkin.reporter.okhttp3.OkHttpSender
import zipkin.reporter.AsyncReporter
import zipkin.reporter.Reporter
import brave.opentracing.BraveTracer

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * OpenTracing based implementation for tracing
  */
object OpenTracingProvider{

  private val spanMap : mutable.Map[Long, ActiveSpan] =  TrieMap[Long, ActiveSpan]()
  private val contextMap : mutable.Map[Long, SpanContext] =  TrieMap[Long, SpanContext]()

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
  def startTrace(spanName: String, transactionId: TransactionId): Unit = {
    if(enabled) {
      var activeSpan: Option[ActiveSpan] = None
      spanMap.get(transactionId.meta.id) match {
        case Some(parentSpan) => {
          //create a child trace
          activeSpan = Some(GlobalTracer.get().buildSpan(spanName).asChildOf(parentSpan).startActive())
        }
        case None => {
          contextMap.get(transactionId.meta.id) match {
            case Some(context) => {
              activeSpan = Some(GlobalTracer.get().buildSpan(spanName).asChildOf(context).startActive())
            }
            case None => {
              activeSpan = Some(GlobalTracer.get().buildSpan(spanName).startActive())
            }
          }
        }
      }

      if (activeSpan.isDefined)
        spanMap.put(transactionId.meta.id, activeSpan.get)
    }
  }

  /**
    * Finish a Trace associated with given transactionId.
    *
    * @param transactionId
    */
  def finish(transactionId: TransactionId): Unit = {
    if(enabled) {
      spanMap.get(transactionId.meta.id) match {
        case Some(currentSpan) => {
          currentSpan.deactivate()
        }
        case None =>
      }
      clear(transactionId)
    }
  }

  /**
    * Register error
    *
    * @param transactionId
    */
  def error(transactionId: TransactionId): Unit = {
    if(enabled) {
      spanMap.get(transactionId.meta.id) match {
        case Some(currentSpan) => {
          currentSpan.deactivate()
        }
        case None =>
      }
    }
    clear(transactionId)
  }

  /**
    * Get the current TraceContext which can be used for downstream services
    *
    * @param transactionId
    * @return
    */
  def getTraceContext(transactionId: TransactionId): Option[Map[String, String]] = {
    if(enabled){
      spanMap.get(transactionId.meta.id) match {
        case Some(currentSpan) => {
          var map: java.util.Map[String, String] = new java.util.HashMap()
          GlobalTracer.get().inject(currentSpan.context(), Format.Builtin.TEXT_MAP, new TextMapInjectAdapter(map))
          val convertedMap: Map[String, String] = scala.collection.JavaConverters.mapAsScalaMapConverter(map).asScala.toMap
          Some(convertedMap)
        }
        case None => None
      }
    }
    None
  }

  /**
    * Get the current TraceContext which can be used for downstream services
    *
    * @param transactionId
    * @return
    */
  def setTraceContext(transactionId: TransactionId, context: Option[Map[String, String]]) = {
    if(enabled){
      context match {
        case Some(scalaMap) => {
          var javaMap: java.util.Map[String, String] = scala.collection.JavaConverters.mapAsJavaMapConverter(scalaMap).asJava
          var ctx: SpanContext =  GlobalTracer.get().extract(Format.Builtin.TEXT_MAP, new TextMapExtractAdapter(javaMap))
          contextMap.put(transactionId.meta.id, ctx)
        }
        case None =>
      }
    }
  }

  def clear(transactionId: TransactionId): Unit = {
    spanMap.remove(transactionId.meta.id)
    contextMap.remove(transactionId.meta.id)
  }

  def configureTracer(componentName: String): Unit = {
    enabled = ConfigFactory.load().getBoolean("tracing_enabled")
    if(enabled) {
      val tracer = ConfigFactory.load().getString("tracer")

      //configure opentracing with zipkin
      if (tracer.equalsIgnoreCase("zipkin")) {
        val sender: Sender = OkHttpSender.create("http://" +
          ConfigFactory.load().getString("zipkin.reporter_host") + ":" +
          ConfigFactory.load().getString("zipkin.reporter_port") + "/api/v1/spans")

        val reporter: Reporter[zipkin.Span] = AsyncReporter.builder(sender).build()
        GlobalTracer.register(BraveTracer.create(Tracing.newBuilder()
          .localServiceName(componentName)
          .reporter(reporter)
          .build()));
      }
    }
  }
}
