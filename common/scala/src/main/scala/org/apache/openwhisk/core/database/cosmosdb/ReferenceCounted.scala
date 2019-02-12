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

package org.apache.openwhisk.core.database.cosmosdb

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

private[cosmosdb] case class ReferenceCounted[T <: AutoCloseable](private val inner: T) {
  private val count = new AtomicInteger(0)

  private def inc(): Unit = count.incrementAndGet()

  private def dec(): Unit = {
    val newCount = count.decrementAndGet()
    if (newCount <= 0) {
      inner.close()
      //Turn count to negative to ensure future reference call fail
      count.decrementAndGet()
    }
  }

  def isClosed: Boolean = count.get() < 0

  def reference(): CountedReference = {
    require(count.get >= 0, "Reference is already closed")
    new CountedReference
  }

  class CountedReference extends AutoCloseable {
    private val closed = new AtomicBoolean()
    inc()
    override def close(): Unit = if (closed.compareAndSet(false, true)) dec()

    def get: T = inner
  }
}
