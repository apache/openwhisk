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

object AverageRingBuffer {
  def apply(maxSize: Int) = new AverageRingBuffer(maxSize)
}

/**
 * This buffer provides the average of the given elements.
 * The number of elements are limited and the first element is removed if the maximum size is reached.
 * Since it is based on the Vector, its operation takes effectively constant time.
 * For more details, please visit https://docs.scala-lang.org/overviews/collections/performance-characteristics.html
 *
 * @param maxSize the maximum size of the buffer
 */
class AverageRingBuffer(private val maxSize: Int) {
  private var elements = Vector.empty[Double]
  private var sum = 0.0
  private var max = 0.0
  private var min = 0.0

  def nonEmpty: Boolean = elements.nonEmpty

  def average: Double = {
    val size = elements.size
    if (size > 2) {
      (sum - max - min) / (size - 2)
    } else {
      sum / size
    }
  }

  def add(el: Double): Unit = synchronized {
    if (elements.size == maxSize) {
      sum = sum + el - elements.head
      elements = elements.tail :+ el
    } else {
      sum += el
      elements = elements :+ el
    }
    if (el > max) {
      max = el
    }
    if (el < min) {
      min = el
    }
  }

  def size(): Int = elements.size
}
