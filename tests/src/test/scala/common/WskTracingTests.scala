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
import com.github.benmanes.caffeine.cache.Ticker
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common.{LoggingMarkers, TransactionId}
import org.apache.openwhisk.common.tracing.{OpenTracer, TracingConfig}
import org.apache.openwhisk.core.ConfigKeys

import scala.ref.WeakReference
import org.scalatest.{Matchers, TestData}
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class WskTracingTests extends TestHelpers with Matchers {

  val tracer: MockTracer = new MockTracer()
  val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)
  val ticker = new FakeTicker(System.nanoTime())
  val openTracer = new OpenTracer(tracer, tracingConfig, ticker)

  override def beforeEach(td: TestData): Unit = {
    super.beforeEach(td)
    tracer.reset()
  }

  it should "create span and context and invalidate cache after expiry" in {
    val transactionId: TransactionId = TransactionId.testing
    var list: List[WeakReference[Span]] = List.empty

    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    var ctx = openTracer.getTraceContext(transactionId)
    openTracer.setTraceContext(transactionId, ctx)
    ctx should be(defined)

    //advance ticker
    ticker.time = System.nanoTime() + (tracingConfig.cacheExpiry.toNanos + 100)
    ctx = openTracer.getTraceContext(transactionId)
    ctx should not be (defined)
    openTracer.startSpan(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    openTracer.finishSpan(transactionId)
    val finishedSpans = tracer.finishedSpans()
    finishedSpans should have size 1
    //no parent for new span as cache expiry cleared spanMap and contextMap
    finishedSpans.get(0).parentId() should be(0)
  }

  it should "create a finished span" in {
    val transactionId: TransactionId = TransactionId.testing
    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    openTracer.finishSpan(transactionId)
    val finishedSpans = tracer.finishedSpans()
    finishedSpans should have size 1
  }

  it should "put error message into span" in {
    val transactionId = TransactionId.testing
    val errorMessage = "dummy error message"
    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    openTracer.error(transactionId, errorMessage)
    val errorSpans = tracer.finishedSpans()
    errorSpans should have size 1
    val spanMap = errorSpans.get(0).tags().asScala
    spanMap.get("error") should be(Some(true))
    spanMap.get("message") should be(Some(errorMessage))
  }

  it should "create a child span" in {
    val transactionId: TransactionId = TransactionId.testing
    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    openTracer.startSpan(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    openTracer.finishSpan(transactionId)
    openTracer.finishSpan(transactionId)
    val finishedSpans = tracer.finishedSpans()
    finishedSpans should have size 2
    val parent: MockSpan = finishedSpans.get(1)
    val child: MockSpan = finishedSpans.get(0)
    child.parentId should be(parent.context().spanId)
  }

  it should "create a span with tag" in {
    val transactionId: TransactionId = TransactionId.testing
    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    openTracer.finishSpan(transactionId)
    val finishedSpans = tracer.finishedSpans()
    finishedSpans should have size 1
    val mockSpan: MockSpan = finishedSpans.get(0)
    mockSpan.tags should not be null
    mockSpan.tags should have size 1
  }

  it should "create a valid trace context and use it" in {
    val transactionId: TransactionId = TransactionId.testing
    openTracer.startSpan(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    val context = openTracer.getTraceContext(transactionId)
    openTracer.finishSpan(transactionId)
    tracer.reset()
    //use context for new span
    openTracer.setTraceContext(transactionId, context)
    openTracer.startSpan(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    openTracer.finishSpan(transactionId)
    val finishedSpans = tracer.finishedSpans()
    finishedSpans should have size 1
    val child: MockSpan = finishedSpans.get(0)
    //This child span should have a parent as we have set trace context
    child.parentId should be > 0L
  }
}

class FakeTicker(var time: Long) extends Ticker {
  override def read() = {
    time
  }
}
