/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.database.test

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest._
import whisk.core.database.MultipleReadersSingleWriterCache
import whisk.core.database.MultipleReadersSingleWriterCache
import whisk.common.TransactionId
import whisk.common.Logging


class MultipleReadersSingleWriterCacheTest extends FlatSpec with Matchers with MultipleReadersSingleWriterCache[String, String] with Logging {
  // run each test this number of times
  val nIters = 3

  override def cacheKeyForUpdate(w: String): String = (w)

  implicit val logger = this

  "the cache" should "support simple CRUD" in {
    System.out.println();
    System.out.println("simple CRUD");

    val inhibits = new doReadWriteRead("foo").go(0)
    inhibits.debug

    inhibits.nReadInhibits.get should be(0)
    getSize() should be(1)
  }

  "the cache" should "support concurrent CRUD to different keys" in {
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

  "the cache" should "support concurrent CRUD to shared keys" in {
    for (i <- 1 to nIters) {
      doCRUD("CONCURRENT CRUD to shared keys", sharedKeys)
        .nWriteInhibits.get should not be (0)
    }
  }

  "the cache" should "support concurrent CRUD to shared keys (zero latency)" in {
    var someInhibits = false
    for (i <- 1 to nIters) {
      val inhibits = doCRUD("concurrent CRUD to shared keys (zero latency)", sharedKeys, 0)
      someInhibits = inhibits.nReadInhibits.get + inhibits.nWriteInhibits.get > 0
    }
    someInhibits should not be (0)
  }

  "the cache" should "support concurrent CRUD to shared keys (short latency)" in {
    for (i <- 1 to nIters) {
      val inhibits = doCRUD("concurrent CRUD to shared keys (short latency)", sharedKeys, 10)
      inhibits.nReadInhibits.get + inhibits.nWriteInhibits.get should not be (0)
    }
  }

  "the cache" should "support concurrent CRUD to shared keys (medium latency)" in {
    for (i <- 1 to nIters) {
      val inhibits = doCRUD("concurrent CRUD to shared keys (medium latency)", sharedKeys, 100)
      inhibits.nReadInhibits.get + inhibits.nWriteInhibits.get should not be (0)
    }
  }

  "the cache" should "support concurrent CRUD to shared keys (long latency)" in {
    for (i <- 1 to nIters) {
      doCRUD("CONCURRENT CRUD to shared keys (long latency)", sharedKeys, 5000)
        .nWriteInhibits.get should not be (0)
    }
  }

  "the cache" should "support concurrent CRUD to shared keys, with update first" in {
    for (i <- 1 to nIters) {
      doCRUD("CONCURRENT CRUD to shared keys, with update first", sharedKeys, 1000, false)
        .nWriteInhibits.get should be(0)
    }
  }

  def sharedKeys = { i: Int => "foop_" + (i % 2) }

  def doCRUD(
    testName: String,
    key: Int => String,
    delay: Int = 1000,
    readsFirst: Boolean = true,
    nThreads: Int = 10): Inhibits = {

    System.out.println();
    System.out.println(testName);

    val exec = Executors.newFixedThreadPool(nThreads)
    val inhibits = Inhibits()

    for (i <- 1 to nThreads) {
      exec.submit(new Runnable { def run() = { new doReadWriteRead(key(i), inhibits, readsFirst).go(delay) } })
    }

    exec.shutdown
    exec.awaitTermination(2, TimeUnit.MINUTES)

    inhibits.debug
    inhibits
  }

  case class Inhibits(
      nReadInhibits: AtomicInteger = new AtomicInteger(0),
      nWriteInhibits: AtomicInteger = new AtomicInteger(0)) {
    def debug {
      System.out.println("InhibitedReads: " + nReadInhibits);
      System.out.println("InhibitedWrites: " + nWriteInhibits);
    }
  }

  class doReadWriteRead(key: String, inhibits: Inhibits = Inhibits(), readFirst: Boolean = true) {
    def go(implicit delay: Int): Inhibits = {
      val latch = new CountDownLatch(2)

      implicit val transId = TransactionId.testing

      if (!readFirst) {
        // we want to do the update before the first read
        cacheUpdate(key, key, delayed("bar_b")).future onFailure {
          case t =>
            inhibits.nWriteInhibits.incrementAndGet();
        }
      }

      System.out.println("R1");
      cacheLookup(key, delayed("bar"), true) onComplete {
        case Success(s) => {
          latch.countDown()
        }
        case Failure(t) => {
          latch.countDown()
          inhibits.nReadInhibits.incrementAndGet();
        }
      }

      if (readFirst) {
        System.out.println("W");
        // we did the read before the update, so do the write next
        cacheUpdate(key, key, delayed("bar_b")).future onFailure {
          case t =>
            inhibits.nWriteInhibits.incrementAndGet();
        }
      }

      System.out.println("R2");
      cacheLookup(key, delayed("bar_c"), true) onComplete {
        case Success(s) => {
          latch.countDown();
        }
        case Failure(t) => {
          inhibits.nReadInhibits.incrementAndGet();
          latch.countDown();
        }
      }

      latch.await(2, TimeUnit.MINUTES)
      // System.out.println("DONE " + i);

      inhibits
    }
  }

  def delayed[W](v: W)(implicit delay: Int): Future[W] = {
    Future {
      Thread.sleep(delay)
      v
    }
  }
}
