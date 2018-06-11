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

package common

import io.opentracing.Span
import io.opentracing.mock.{MockSpan, MockTracer}
import io.opentracing.util.GlobalTracer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import whisk.common.{LoggingMarkers, TransactionId}
import whisk.common.tracing.{TracingCacheProvider, WhiskTracerProvider}

import scala.collection.mutable
import scala.ref.WeakReference

@RunWith(classOf[JUnitRunner])
class WskTracingTests extends TestHelpers {

  val tracer: MockTracer = new MockTracer()
  val sleepTime = 10

  {
    GlobalTracer.register(tracer)
  }

  it should "create span and context and invalidate cache after expiry" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    val list: mutable.ListBuffer[WeakReference[Span]] = mutable.ListBuffer()

    val span = GlobalTracer.get().buildSpan("test").startActive(true).span()
    list.+=:(new WeakReference(span))
    TracingCacheProvider.spanCache.put(transactionId.meta.id, list)
    TracingCacheProvider.contextCache.put(transactionId.meta.id, span.context())
    val expiryTime: Int = (TracingCacheProvider.tracingConfig.cacheExpiry.getOrElse(5))

    var cachedSpan = TracingCacheProvider.spanCache.get(transactionId.meta.id)
    assert(cachedSpan.isDefined)
    var ctx = TracingCacheProvider.contextCache.get(transactionId.meta.id)
    assert(ctx.isDefined)

    Thread.sleep((expiryTime + 10) * 1000)
    cachedSpan = TracingCacheProvider.spanCache.get(transactionId.meta.id)
    assert(!cachedSpan.isDefined)
    ctx = TracingCacheProvider.contextCache.get(transactionId.meta.id)
    assert(!ctx.isDefined)
  }

  it should "create a finished span" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)

  }

  it should "create a child span" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 2)
    val parent: MockSpan = finishedSpans.get(0)
    val child: MockSpan = finishedSpans.get(1)
    assert(child.parentId == parent.context().spanId)

  }

  it should "create a span with tag" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)
    val mockSpan: MockSpan = finishedSpans.get(0)
    assert(mockSpan.tags != null)
    assert(mockSpan.tags.size == 1)

  }

  it should "create a valid trace context and use it" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    val context = WhiskTracerProvider.tracer.getTraceContext(transactionId)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    tracer.reset
    //use context for new span
    WhiskTracerProvider.tracer.setTraceContext(transactionId, context)
    WhiskTracerProvider.tracer.startSpan(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    WhiskTracerProvider.tracer.finishSpan(transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)
    val child: MockSpan = finishedSpans.get(0)
    //This child span should have a parent as we have set trace context
    assert(child.parentId > 0)
  }
}
