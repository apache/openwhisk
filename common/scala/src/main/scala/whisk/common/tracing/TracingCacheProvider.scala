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

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import io.opentracing.{Span, SpanContext}
import pureconfig.loadConfigOrThrow
import whisk.core.ConfigKeys

import scala.collection.mutable
import scala.ref.WeakReference

class WhiskTracingSpanCacheImpl(val cache: Cache[String, mutable.ListBuffer[WeakReference[Span]]])
    extends WhiskTracingSpanCache {

  override def put(key: String, value: mutable.ListBuffer[WeakReference[Span]]): Unit = {
    cache.put(key, value)
  }

  override def get(key: String): Option[mutable.ListBuffer[WeakReference[Span]]] = {
    Option(cache.getIfPresent(key))
  }

  override def invalidate(key: String): Unit = {
    cache.invalidate(key)
  }

}

class WhiskTracingContextCacheImpl(val cache: Cache[String, SpanContext]) extends WhiskTraceContextCache {

  override def put(key: String, value: SpanContext): Unit = {
    cache.put(key, value)
  }

  override def get(key: String): Option[SpanContext] = {
    Option(cache.getIfPresent(key))
  }

  override def invalidate(key: String): Unit = {
    cache.invalidate(key)
  }
}

object TracingCacheProvider {
  val tracingConfig = loadConfigOrThrow[TracingConfig](ConfigKeys.tracing)
  lazy val spanCache: WhiskTracingSpanCache = configureSpanCache
  lazy val contextCache: WhiskTraceContextCache = configureContextCache

  def configureSpanCache(): WhiskTracingSpanCache = {
    val expiry: Int = tracingConfig.cacheExpiry.getOrElse(3600 * 5)

    val cacheImpl: Cache[String, mutable.ListBuffer[WeakReference[Span]]] =
      Caffeine
        .newBuilder()
        .asInstanceOf[Caffeine[String, mutable.ListBuffer[WeakReference[Span]]]]
        .expireAfterAccess(expiry, TimeUnit.SECONDS)
        .softValues()
        .build()

    new WhiskTracingSpanCacheImpl(cacheImpl)
  }

  def configureContextCache(): WhiskTraceContextCache = {
    val expiry: Int = tracingConfig.cacheExpiry.getOrElse(3600 * 5)

    val cacheImpl: Cache[String, SpanContext] =
      Caffeine
        .newBuilder()
        .asInstanceOf[Caffeine[String, SpanContext]]
        .expireAfterAccess(expiry, TimeUnit.SECONDS)
        .softValues()
        .build()

    new WhiskTracingContextCacheImpl(cacheImpl)
  }
}
trait WhiskTracingSpanCache {

  def put(key: String, value: mutable.ListBuffer[WeakReference[Span]]): Unit = {}
  def get(key: String): Option[mutable.ListBuffer[WeakReference[Span]]] = None
  def invalidate(key: String): Unit = {}
}

trait WhiskTraceContextCache {

  def put(key: String, value: SpanContext): Unit = {}
  def get(key: String): Option[SpanContext] = null
  def invalidate(key: String): Unit = {}

}
