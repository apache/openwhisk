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

/**
 * A singleton object which defines the action's CPU limit properties.
 */
object CPULimitUtils {
  // CPU threads transform to CPU permits, and calculate by CPU permits.
  private val unitScala = 100

  /**
   * transform CPU threads to CPU permits for counting slots.
   * @param cpuThreads CPU threads, count in Float
   */
  def threadsToPermits(cpuThreads: Float): Int = (cpuThreads * unitScala).toInt

  /**
   * transform CPU threads to CPU permits for counting slots.
   * @param cpuThreads CPU threads, count in Float
   */
  def threadsToPermits(cpuThreads: Double): Int = (cpuThreads * unitScala).toInt

  /**
   * transform CPU permits to CPU threads for nice printing.
   * @param permits CPU permits, count in Int
   */
  def permitsToThreads(permits: Int): Float = permits.toFloat / unitScala
}

/**
 * A Semaphore that coordinates the CPU threads (ForcibleSemaphore) and concurrency (ResizableSemaphore) where
 * - for invocations when maxConcurrent == 1, delegate to super
 * - for others, acquire cpu slots and concurrency slots, do it atomically
 * @param cpuThreads
 * @tparam T
 */
class NestedCPUSemaphore[T](cpuThreads: Float) extends NestedSemaphore[T](CPULimitUtils.threadsToPermits(cpuThreads)) {
  final override def tryAcquireConcurrent(actionid: T, maxConcurrent: Int, permits: Int): Boolean = {
    super.tryAcquireConcurrent(actionid, maxConcurrent, permits)
  }
}
