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

package org.apache.openwhisk.common

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import scala.annotation.tailrec

/**
 * A Semaphore that has a specialized release process that optionally allows reduction of permits in batches.
 * When permit size after release is a factor of reductionSize, the release process will reset permits to state + 1 - reductionSize;
 * otherwise the release will reset permits to state + 1.
 * It also maintains an operationCount where a tryAquire + release is a single operation,
 * so that we can know once all operations are completed.
 * @param maxAllowed
 * @param reductionSize
 */
class ResizableSemaphore(maxAllowed: Int, reductionSize: Int) {
  private val operationCount = new AtomicInteger(0)
  class Sync extends AbstractQueuedSynchronizer {
    setState(maxAllowed)

    def permits: Int = getState

    /** Try to release a permit and return whether or not that operation was successful. */
    @tailrec
    final def tryReleaseSharedWithResult(releases: Int): Boolean = {
      val current = getState
      val next2 = current + releases
      val (next, reduced) = if (next2 % reductionSize == 0) {
        (next2 - reductionSize, true)
      } else {
        (next2, false)
      }
      //next MIGHT be < current in case of reduction; this is OK!!!
      if (compareAndSetState(current, next)) {
        reduced
      } else {
        tryReleaseSharedWithResult(releases)
      }
    }

    /**
     * Try to acquire a permit and return whether or not that operation was successful. Requests may not finish in FIFO
     * order, hence this method is not necessarily fair.
     */
    @tailrec
    final def nonFairTryAcquireShared(acquires: Int): Int = {
      val available = getState
      val remaining = available - acquires
      if (remaining < 0 || compareAndSetState(available, remaining)) {
        remaining
      } else {
        nonFairTryAcquireShared(acquires)
      }
    }
  }

  val sync = new Sync

  /**
   * Acquires the given numbers of permits.
   *
   * @param acquires the number of permits to get
   * @return `true`, iff the internal semaphore's number of permits is positive, `false` if negative
   */
  def tryAcquire(acquires: Int = 1): Boolean = {
    require(acquires > 0, "cannot acquire negative or no permits")
    if (sync.nonFairTryAcquireShared(acquires) >= 0) {
      operationCount.incrementAndGet()
      true
    } else {
      false
    }
  }

  /**
   * Releases the given amount of permits
   *
   * @param acquires the number of permits to release
   * @return (releaseMemory, releaseAction) releaseMemory is true if concurrency count is a factor of reductionSize
   *         releaseAction is true if the operationCount reaches 0
   */
  def release(acquires: Int = 1, opComplete: Boolean): (Boolean, Boolean) = {
    require(acquires > 0, "cannot release negative or no permits")
    //release always succeeds, so we can always adjust the operationCount
    val releaseAction = if (opComplete) { // an operation completion
      operationCount.decrementAndGet() == 0
    } else { //otherwise an allocation + operation initialization
      operationCount.incrementAndGet() == 0
    }
    (sync.tryReleaseSharedWithResult(acquires), releaseAction)
  }

  /** Returns the number of currently available permits. Possibly negative. */
  def availablePermits: Int = sync.permits

  //for testing
  def counter = operationCount.get()
}
