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

package whisk.core.tracing

import io.opentracing.SpanContext
import whisk.common.TransactionId
import whisk.spi.Spi

/**
  * SPI for providing Tracing implementations
  */
trait TracingProvider extends Spi{

  def init(serviceName: String): Unit

  /**
  * Start a Trace for given service.
  * @param serviceName Name of Service to be traced
  * @param transactionId transactionId to which this Trace belongs.
  * @return TracedRequest which provides details about current service being traced.
  */
  def startTrace(serviceName: String, spanName: String, transactionId: TransactionId): Unit

  /**
  * Finish a Trace associated with given transactionId.
  * @param transactionId
  */
  def finish(transactionId: TransactionId): Unit

  /**
    * Register error
    * @param transactionId
    * @param t
    */
  def error(transactionId: TransactionId): Unit

  /**
    * Get the current TraceContext which can be used for downstream services
    * @param transactionId
    * @return
    */
  def getTraceContext(transactionId: TransactionId): Option[SpanContext]

}
