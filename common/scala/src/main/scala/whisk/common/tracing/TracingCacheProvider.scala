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

import java.util.concurrent.TimeUnit

import com.github.benmanes.caffeine.cache.Caffeine
import io.opentracing.{Span, SpanContext}
import pureconfig.loadConfigOrThrow
import whisk.core.ConfigKeys

import scala.ref.WeakReference
import scala.collection.concurrent.Map
import scala.collection.JavaConverters._

object TracingCacheProvider {
  val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)
  lazy val spanMap: Map[String, List[WeakReference[Span]]] = configureSpanMap
  lazy val contextMap: Map[String, SpanContext] = configureContextMap

  def configureContextMap(): Map[String, SpanContext] = {

    Caffeine
      .newBuilder()
      .asInstanceOf[Caffeine[String, SpanContext]]
      .expireAfterAccess(tracingConfig.cacheExpiry.toSeconds, TimeUnit.SECONDS)
      .softValues()
      .build()
      .asMap()
      .asScala
  }

  def configureSpanMap(): Map[String, List[WeakReference[Span]]] = {

    Caffeine
      .newBuilder()
      .asInstanceOf[Caffeine[String, List[WeakReference[Span]]]]
      .expireAfterAccess(tracingConfig.cacheExpiry.toSeconds, TimeUnit.SECONDS)
      .softValues()
      .build()
      .asMap()
      .asScala
  }
}
