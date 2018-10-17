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

package org.apache.openwhisk.core.database.test

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.WskActorSystem
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.database.CacheChangeNotification
import org.apache.openwhisk.core.database.MultipleReadersSingleWriterCache
import org.apache.openwhisk.core.entity.CacheKey

@RunWith(classOf[JUnitRunner])
class MultipleReadersSingleWriterCacheTests
    extends FlatSpec
    with Matchers
    with MultipleReadersSingleWriterCache[String, String]
    with WskActorSystem
    with StreamLogging {

  behavior of "the cache"

  it should "execute the callback on invalidating and updating an entry" in {
    val ctr = new AtomicInteger(0)
    val key = CacheKey("key")

    implicit val transId = TransactionId.testing
    lazy implicit val cacheUpdateNotifier = Some {
      new CacheChangeNotification {
        override def apply(key: CacheKey) = {
          ctr.incrementAndGet()
          Future.successful(())
        }
      }
    }

    // Create an cache entry
    Await.ready(cacheUpdate("doc", key, Future.successful("db save successful")), 10.seconds)
    ctr.get shouldBe 1

    // Callback should be called if entry exists
    Await.ready(cacheInvalidate(key, Future.successful(())), 10.seconds)
    ctr.get shouldBe 2
    Await.ready(cacheUpdate("docdoc", key, Future.successful("update in db successful")), 10.seconds)
    ctr.get shouldBe 3

    // Callback should be called if entry does not exist
    Await.ready(cacheInvalidate(CacheKey("abc"), Future.successful(())), 10.seconds)
    ctr.get shouldBe 4
  }
}
