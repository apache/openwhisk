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

private object NestedCPUSemaphoreUtils {
  // CPU cores would be transformed to CPU permits, and calculate by CPU permits.
  val unitScala = 100
  def coresToPermits(cores: Float): Int = (cores * unitScala).toInt
  def permitsToCores(permits: Int): Float = permits.toFloat / unitScala
}

/**
 * A Semaphore that coordinates the CPU cores (ForcibleSemaphore) and concurrency (ResizableSemaphore) where
 * - for invocations when maxConcurrent == 1, delegate to super
 * - for invocations that cause acquire on cpu slots, also acquire concurrency slots, and do it atomically
 * @param cpuCores
 * @tparam T
 */
class NestedCPUSemaphore[T](cpuCores: Float) extends NestedSemaphore[T](NestedCPUSemaphoreUtils.coresToPermits(cpuCores)) {
  final override def tryAcquireConcurrent(actionid: T, maxConcurrent: Int, permits: Int): Boolean = {
    super.tryAcquireConcurrent(actionid, maxConcurrent, permits)
  }
}
