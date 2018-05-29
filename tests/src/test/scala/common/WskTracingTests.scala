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

import common.rest.WskRest
import io.opentracing.mock.{MockSpan, MockTracer}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import whisk.common.{LoggingMarkers, TransactionId}
import whisk.common.tracing.OpenTracingProvider
import whisk.common.tracing.OpenTracingProvider.TracingConfig

@RunWith(classOf[JUnitRunner])
class WskTracingTests extends TestHelpers {

  implicit val wskprops = WskProps()
  val wsk: WskRest = new WskRest
  val testString = "this is a test"
  val tracer: MockTracer = new MockTracer()
  val sleepTime = 10

  {
    val tracingConfig = new TracingConfig(true, "", null)
    OpenTracingProvider.configureTracer("Test", Some(tracer), Some(tracingConfig))
  }

  it should "create a finished span" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)

  }

  it should "create a child span" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
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
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)
    val mockSpan: MockSpan = finishedSpans.get(0)
    assert(mockSpan.tags != null)
    assert(mockSpan.tags.size == 1)

  }

  it should "create a valid trace context" in {
    tracer.reset
    val transactionId: TransactionId = TransactionId.testing
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    Thread.sleep(sleepTime)
    val context = OpenTracingProvider.getTraceContext(transactionId)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_ACTIVATION, transactionId)
    tracer.reset
    //use context for new span
    OpenTracingProvider.setTraceContext(transactionId, context)
    OpenTracingProvider.startTrace(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    OpenTracingProvider.finish(LoggingMarkers.CONTROLLER_KAFKA, transactionId)
    Thread.sleep(sleepTime)
    val finishedSpans = tracer.finishedSpans()
    assert(finishedSpans.size() == 1)
    val child: MockSpan = finishedSpans.get(0)
    //This child span should have a parent as we have set trace context
    assert(child.parentId > 0)
  }
}
