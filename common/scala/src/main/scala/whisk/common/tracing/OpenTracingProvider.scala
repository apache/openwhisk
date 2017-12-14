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

  private val traceMap : mutable.Map[Long, ActiveSpan] =  TrieMap[Long, ActiveSpan]()

  var enabled = false;

  def apply(serviceName: String): Unit = {
    configureTracer(serviceName)
  }

  /**
    * Start a Trace for given service.
    *
    * @param serviceName   Name of Service to be traced
    * @param transactionId transactionId to which this Trace belongs.
    * @return TracedRequest which provides details about current service being traced.
    */
  def startTrace(serviceName: String, spanName: String, transactionId: TransactionId): Unit = {
    if(enabled) {
      var activeSpan: Option[ActiveSpan] = None

      traceMap.get(transactionId.meta.id) match {
        case Some(parentSpan) => {
          //create a child trace
          activeSpan = Some(GlobalTracer.get().buildSpan(spanName).asChildOf(parentSpan).startActive())
        }
        case None => {
          activeSpan = Some(GlobalTracer.get().buildSpan(spanName).startActive())
        }
      }

      if (activeSpan.isDefined)
        traceMap.put(transactionId.meta.id, activeSpan.get)
    }
  }

  /**
    * Finish a Trace associated with given transactionId.
    *
    * @param transactionId
    */
  def finish(transactionId: TransactionId): Unit = {
    if(enabled) {
      traceMap.get(transactionId.meta.id) match {
        case Some(currentSpan) => {
          currentSpan.deactivate()
        }
        case None =>
      }
    }
  }

  /**
    * Register error
    *
    * @param transactionId
    */
  def error(transactionId: TransactionId): Unit = {
    if(enabled) {
      traceMap.get(transactionId.meta.id) match {
        case Some(currentSpan) => {
          currentSpan.deactivate()
        }
        case None =>
      }
    }
  }

  /**
    * Get the current TraceContext which can be used for downstream services
    *
    * @param transactionId
    * @return
    */
  def getTraceContext(transactionId: TransactionId): Option[SpanContext] = {
    traceMap.get(transactionId.meta.id) match {
      case Some(currentSpan) => {
        Some(currentSpan.context())
      }
      case None => None
    }
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
