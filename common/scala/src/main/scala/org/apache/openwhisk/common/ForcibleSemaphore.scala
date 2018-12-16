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

import java.util.concurrent.locks.AbstractQueuedSynchronizer

import scala.annotation.tailrec

/**
 * A Semaphore, which in addition to the usual features has means to force more clients to get permits.
 *
 * Like any usual Semaphore, this implementation will give away at most `maxAllowed` permits when used the "usual" way.
 * In addition to that, it also has a `forceAcquire` method which will push the Semaphore's remaining permits into a
 * negative value. Getting permits using `tryAcquire` will only be possible once the permits value is in a positive
 * state again.
 *
 * As this is (now) only used for the loadbalancer's scheduling, this does not implement the "whole" Java Semaphore's
 * interface but only the methods needed.
 *
 * @param maxAllowed maximum number of permits given away by `tryAcquire`
 */
class ForcibleSemaphore(maxAllowed: Int) {
  class Sync extends AbstractQueuedSynchronizer {
    setState(maxAllowed)

    def permits: Int = getState

    /** Try to release a permit and return whether or not that operation was successful. */
    @tailrec
    override final def tryReleaseShared(releases: Int): Boolean = {
      val current = getState
      val next = current + releases
      if (next < current) { // integer overflow
        throw new Error("Maximum permit count exceeded, permit variable overflowed")
      }
      if (compareAndSetState(current, next)) {
        true
      } else {
        tryReleaseShared(releases)
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

    /**
     * Basically the same as `nonFairTryAcquireShared`, but does bound to a minimal value of 0 so permits can get
     * negative.
     */
    @tailrec
    final def forceAquireShared(acquires: Int): Unit = {
      val available = getState
      val remaining = available - acquires
      if (!compareAndSetState(available, remaining)) {
        forceAquireShared(acquires)
      }
    }
  }

  private val sync = new Sync

  /**
   * Acquires the given numbers of permits.
   *
   * @param acquires the number of permits to get
   * @return `true`, iff the internal semaphore's number of permits is positive, `false` if negative
   */
  def tryAcquire(acquires: Int = 1): Boolean = {
    require(acquires > 0, "cannot acquire negative or no permits")
    sync.nonFairTryAcquireShared(acquires) >= 0
  }

  /**
   * Forces the amount of permits.
   *
   * This possibly pushes the internal number of available permits to a negative value.
   *
   * @param acquires the number of permits to get
   */
  def forceAcquire(acquires: Int = 1): Unit = {
    require(acquires > 0, "cannot force acquire negative or no permits")
    sync.forceAquireShared(acquires)
  }

  /**
   * Releases the given amount of permits
   *
   * @param acquires the number of permits to release
   */
  def release(acquires: Int = 1): Unit = {
    require(acquires > 0, "cannot release negative or no permits")
    sync.releaseShared(acquires)
  }

  /** Returns the number of currently available permits. Possibly negative. */
  def availablePermits: Int = sync.permits
}
