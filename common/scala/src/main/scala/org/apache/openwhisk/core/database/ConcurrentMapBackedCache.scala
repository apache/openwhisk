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

/*
 * Cache base implementation:
 * Copyright (C) 2011-2015 the spray project <http://spray.io>
 */

package org.apache.openwhisk.core.database

import java.util.concurrent.ConcurrentMap

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * A thread-safe implementation of [[spray.caching.cache]] backed by a plain
 * [[java.util.concurrent.ConcurrentMap]].
 *
 * The implementation is entirely copied from Spray's [[spray.caching.Cache]] and
 * [[spray.caching.SimpleLruCache]] respectively, the only difference being the store type.
 * Implementation otherwise is identical.
 */
private class ConcurrentMapBackedCache[V](store: ConcurrentMap[Any, Future[V]]) {
  val cache = this

  def apply(key: Any) = new Keyed(key)

  class Keyed(key: Any) {
    def apply(magnet: => ValueMagnet[V])(implicit ec: ExecutionContext): Future[V] =
      cache.apply(
        key,
        () =>
          try magnet.future
          catch { case NonFatal(e) => Future.failed(e) })
  }

  def apply(key: Any, genValue: () => Future[V])(implicit ec: ExecutionContext): Future[V] = {
    store.computeIfAbsent(
      key,
      new java.util.function.Function[Any, Future[V]]() {
        override def apply(key: Any): Future[V] = {
          val future = genValue()
          future.onComplete { value =>
            // in case of exceptions we remove the cache entry (i.e. try again later)
            if (value.isFailure) store.remove(key, future)
          }
          future
        }
      })
  }

  def remove(key: Any) = Option(store.remove(key))

  def size = store.size
}

class ValueMagnet[V](val future: Future[V])
object ValueMagnet {
  import scala.language.implicitConversions

  implicit def fromAny[V](block: V): ValueMagnet[V] = fromFuture(Future.successful(block))
  implicit def fromFuture[V](future: Future[V]): ValueMagnet[V] = new ValueMagnet(future)
}
