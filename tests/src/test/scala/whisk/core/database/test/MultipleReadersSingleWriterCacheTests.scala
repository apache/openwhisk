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

package whisk.core.database.test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import common.WskActorSystem
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.database.CacheChangeNotification
import whisk.core.database.MultipleReadersSingleWriterCache
import whisk.core.entity.CacheKey

@RunWith(classOf[JUnitRunner])
class MultipleReadersSingleWriterCacheTests extends FlatSpec
    with Matchers
    with MultipleReadersSingleWriterCache[String, String]
    with WskActorSystem
    with StreamLogging {

    val nIters: Int = 3

    behavior of "the cache"

    it should "support simple CRUD" in {
        val inhibits = doReadWriteRead("foo").go(0 seconds)
        inhibits.debug(this)

        inhibits.nReadInhibits.get should be(0)
        cacheSize should be(1)
    }

    ignore should "support concurrent CRUD to different keys" in {
        //
        // for the first iter, all reads are not-cached and each thread
        // requests a different key, so we expect no read inhibits, and a
        // bunch of write inhibits
        //
        val inhibits = doCRUD("CONCURRENT CRUD to different keys", { i => "foop_" + i })
        inhibits.nReadInhibits.get should be(0)
        inhibits.nWriteInhibits.get should not be (0)

        //
        // after the first iter, the keys already exist, so the first read
        // should be cached, resulting in the writes proceeding more
        // smoothly this time, thus inhibiting some of the second reads
        //
        for (i <- 1 to nIters - 1) {
            doCRUD("CONCURRENT CRUD to different keys", { i => "foop_" + i })
                .nReadInhibits.get should not be (0)
        }
    }

    ignore should "support concurrent CRUD to shared keys" in {
        for (i <- 1 to nIters) {
            doCRUD("CONCURRENT CRUD to shared keys", sharedKeys)
                .nWriteInhibits.get should not be (0)
        }
    }

    ignore should "support concurrent CRUD to shared keys (zero latency)" in {
        var hasInhibits = false
        for (i <- 1 to nIters) {
            hasInhibits = doCRUD("concurrent CRUD to shared keys (zero latency)", sharedKeys, 0 seconds)
                .hasInhibits
        }
        hasInhibits should not be (false)
    }

    ignore should "support concurrent CRUD to shared keys (short latency)" in {
        for (i <- 1 to nIters) {
            doCRUD("concurrent CRUD to shared keys (short latency)", sharedKeys, 10 milliseconds)
                .hasInhibits should be(true)
        }
    }

    ignore should "support concurrent CRUD to shared keys (medium latency)" in {
        for (i <- 1 to nIters) {
            doCRUD("concurrent CRUD to shared keys (medium latency)", sharedKeys, 100 milliseconds)
                .hasInhibits should be(true)
        }
    }

    ignore should "support concurrent CRUD to shared keys (long latency)" in {
        for (i <- 1 to nIters) {
            doCRUD("CONCURRENT CRUD to shared keys (long latency)", sharedKeys, 5 seconds)
                .nWriteInhibits.get should not be (0)
        }
    }

    it should "support concurrent CRUD to shared keys, with update first" in {
        for (i <- 1 to nIters) {
            doCRUD("CONCURRENT CRUD to shared keys, with update first", sharedKeys, 1 second, false)
                .nWriteInhibits.get should be(0)
        }
    }

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
        cacheUpdate("doc", key, Future.successful("db save successful"))
        ctr.get shouldBe 1

        // Callback should be called if entry exists
        cacheInvalidate(key, Future.successful(()))
        ctr.get shouldBe 2
        cacheUpdate("docdoc", key, Future.successful("update in db successful"))
        ctr.get shouldBe 3

        // Callback should be called if entry does not exist
        cacheInvalidate(CacheKey("abc"), Future.successful(()))
        ctr.get shouldBe 4
    }

    def sharedKeys = { i: Int => "foop_" + (i % 2) }

    def doCRUD(
        testName: String,
        key: Int => String,
        delay: FiniteDuration = 1 second,
        readsFirst: Boolean = true,
        nThreads: Int = 10): Inhibits = {

        System.out.println(testName);

        val exec = Executors.newFixedThreadPool(nThreads)
        val inhibits = Inhibits()

        for (i <- 1 to nThreads) {
            exec.submit(new Runnable { def run() = { doReadWriteRead(key(i), inhibits, readsFirst).go(delay) } })
        }

        exec.shutdown
        exec.awaitTermination(2, TimeUnit.MINUTES)

        inhibits.debug(this)
        inhibits
    }

    case class Inhibits(
        nReadInhibits: AtomicInteger = new AtomicInteger(0),
        nWriteInhibits: AtomicInteger = new AtomicInteger(0)) {

        def debug(from: AnyRef) = {
            logging.debug(from, "InhibitedReads: " + nReadInhibits)
            logging.debug(from, "InhibitedWrites: " + nWriteInhibits)
        }

        def hasInhibits: Boolean = { nReadInhibits.get > 0 || nWriteInhibits.get > 0 }
    }

    private case class doReadWriteRead(key: String, inhibits: Inhibits = Inhibits(), readFirst: Boolean = true)(implicit logging: Logging) {
        def go(implicit delay: FiniteDuration): Inhibits = {
            val latch = new CountDownLatch(2)

            implicit val transId = TransactionId.testing
            implicit val cacheUpdateNotifier = None

            if (!readFirst) {
                // we want to do the update before the first read
                cacheUpdate(key, CacheKey(key), delayed("bar_b")) onFailure {
                    case t =>
                        inhibits.nWriteInhibits.incrementAndGet();
                }
            }

            cacheLookup(CacheKey(key), delayed("bar"), true) onComplete {
                case Success(s) => {
                    latch.countDown()
                }
                case Failure(t) => {
                    latch.countDown()
                    inhibits.nReadInhibits.incrementAndGet();
                }
            }

            if (readFirst) {
                // we did the read before the update, so do the write next
                cacheUpdate(key, CacheKey(key), delayed("bar_b")) onFailure {
                    case t =>
                        inhibits.nWriteInhibits.incrementAndGet();
                }
            }

            cacheLookup(CacheKey(key), delayed("bar_c"), true) onComplete {
                case Success(s) => {
                    latch.countDown();
                }
                case Failure(t) => {
                    inhibits.nReadInhibits.incrementAndGet();
                    latch.countDown();
                }
            }

            latch.await(2, TimeUnit.MINUTES)

            inhibits
        }
    }

    private def delayed[W](v: W)(implicit delay: FiniteDuration): Future[W] = {
        akka.pattern.after(duration = delay, using = actorSystem.scheduler)(
            Future.successful { v })
    }
}
