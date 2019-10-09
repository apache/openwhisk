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
 * A Semaphore that coordinates the resource (ForcibleSemaphore) and concurrency (ResizableSemaphore) where
 * - for invocations when maxConcurrent == 1, delegate to super
 * - for others, acquire resource slots and concurrency slots, do it atomically
 * @param permits (no matter what) permits count in Int
 * @tparam T
 */
class NestedSemaphore[T](permits: Int) extends ForcibleSemaphore(permits) {
  private val actionConcurrentSlotsMap = TrieMap.empty[T, ResizableSemaphore] //one key per action; resized per container

  def tryAcquireConcurrent(actionid: T, maxConcurrent: Int, permits: Int): Boolean = {
    if (maxConcurrent == 1) {
      super.tryAcquire(permits)
    } else {
      tryOrForceAcquireConcurrent(actionid, maxConcurrent, permits, false)
    }
  }

  /**
   * Coordinated permit acquisition:
   * - first try to acquire concurrency slot
   * - then try to acquire lock for this action
   * - within the lock:
   *     - try to acquire concurrency slot (double check)
   *     - try to acquire resources slot
   *     - if resources slot acquired, release concurrency slots
   * - release the lock
   * - if neither concurrency slot nor resources slot acquired, return false
   * @param actionid
   * @param maxConcurrent
   * @param permits
   * @param force
   * @return
   */
  private def tryOrForceAcquireConcurrent(actionid: T, maxConcurrent: Int, permits: Int, force: Boolean): Boolean = {
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
          super.forceAcquire(permits)
          concurrentSlots.release(maxConcurrent - 1, false)
          true
        } else if (super.tryAcquire(permits)) {
          concurrentSlots.release(maxConcurrent - 1, false)
          true
        } else {
          false
        }
      }
    }
  }

  def forceAcquireConcurrent(actionid: T, maxConcurrent: Int, permits: Int): Unit = {
    require(permits > 0, "cannot force acquire negative or no permits")
    if (maxConcurrent == 1) {
      super.forceAcquire(permits)
    } else {
      tryOrForceAcquireConcurrent(actionid, maxConcurrent, permits, true)
    }
  }

  /**
   * Releases the given amount of permits
   *
   * @param acquires the number of permits to release
   */
  def releaseConcurrent(actionid: T, maxConcurrent: Int, permits: Int): Unit = {
    require(permits > 0, "cannot release negative or no permits")
    if (maxConcurrent == 1) {
      super.release(permits)
    } else {
      val concurrentSlots = actionConcurrentSlotsMap(actionid)
      val (resourcesRelease, actionRelease) = concurrentSlots.release(1, true)
      //concurrent slots
      if (resourcesRelease) {
        super.release(permits)
      }
      if (actionRelease) {
        actionConcurrentSlotsMap.remove(actionid)
      }
    }
  }
  //for testing
  def concurrentState = actionConcurrentSlotsMap.readOnlySnapshot()
}
