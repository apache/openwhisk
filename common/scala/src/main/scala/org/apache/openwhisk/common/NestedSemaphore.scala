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

import scala.collection.concurrent.TrieMap

/**
 * A Semaphore that coordinates the memory (ForcibleSemaphore) and concurrency (ResizableSemaphore) where
 * - for invocations when maxConcurrent == 1, delegate to super
 * - for invocations that cause acquire on memory slots, also acquire concurrency slots, and do it atomically
 * @param memoryPermits
 * @tparam T
 */
class NestedSemaphore[T](memoryPermits: Int) extends ForcibleSemaphore(memoryPermits) {
  private val actionConcurrentSlotsMap = TrieMap.empty[T, ResizableSemaphore] //one key per action; resized per container

  final def tryAcquireConcurrent(actionid: T, maxConcurrent: Int, memoryPermits: Int): Boolean = {

    if (maxConcurrent == 1) {
      super.tryAcquire(memoryPermits)
    } else {
      tryOrForceAcquireConcurrent(actionid, maxConcurrent, memoryPermits, false)
    }
  }

  /**
   * Coordinated permit acquisition:
   * - first try to acquire concurrency slot
   * - then try to acquire lock for this action
   * - within the lock:
   *     - try to acquire concurrency slot (double check)
   *     - try to acquire memory slot
   *     - if memory slot acquired, release concurrency slots
   * - release the lock
   * - if neither concurrency slot nor memory slot acquired, return false
   * @param actionid
   * @param maxConcurrent
   * @param memoryPermits
   * @param force
   * @return
   */
  private def tryOrForceAcquireConcurrent(actionid: T,
                                          maxConcurrent: Int,
                                          memoryPermits: Int,
                                          force: Boolean): Boolean = {
    val concurrentSlots = actionConcurrentSlotsMap
      .getOrElseUpdate(actionid, new ResizableSemaphore(0, maxConcurrent))
    if (concurrentSlots.tryAcquire(1)) {
      true
    } else {
      // with synchronized:
      concurrentSlots.synchronized {
        if (concurrentSlots.tryAcquire(1)) {
          true
        } else if (force) {
          super.forceAcquire(memoryPermits)
          concurrentSlots.release(maxConcurrent - 1, false)
          true
        } else if (super.tryAcquire(memoryPermits)) {
          concurrentSlots.release(maxConcurrent - 1, false)
          true
        } else {
          false
        }
      }
    }
  }

  def forceAcquireConcurrent(actionid: T, maxConcurrent: Int, memoryPermits: Int): Unit = {
    require(memoryPermits > 0, "cannot force acquire negative or no permits")
    if (maxConcurrent == 1) {
      super.forceAcquire(memoryPermits)
    } else {
      tryOrForceAcquireConcurrent(actionid, maxConcurrent, memoryPermits, true)
    }
  }

  /**
   * Releases the given amount of permits
   *
   * @param acquires the number of permits to release
   */
  def releaseConcurrent(actionid: T, maxConcurrent: Int, memoryPermits: Int): Unit = {
    require(memoryPermits > 0, "cannot release negative or no permits")
    if (maxConcurrent == 1) {
      super.release(memoryPermits)
    } else {
      val concurrentSlots = actionConcurrentSlotsMap(actionid)
      val (memoryRelease, actionRelease) = concurrentSlots.release(1, true)
      //concurrent slots
      if (memoryRelease) {
        super.release(memoryPermits)
      }
      if (actionRelease) {
        actionConcurrentSlotsMap.remove(actionid)
      }
    }
  }
  //for testing
  def concurrentState = actionConcurrentSlotsMap.readOnlySnapshot()
}
