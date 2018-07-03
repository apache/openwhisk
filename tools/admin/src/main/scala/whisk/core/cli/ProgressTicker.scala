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

package whisk.core.cli

import com.google.common.base.Stopwatch

trait Ticker {

  def tick(): Unit = {}

  def close(): Unit = {}

}

object NoopTicker extends Ticker

class ProgressTicker extends Ticker {
  private var count = 0
  private val anim = Array('|', '/', '-', '\\')
  private val watch = Stopwatch.createStarted()

  override def tick(): Unit = {
    count += 1
    draw()
  }

  override def close(): Unit = {
    println("\r" + status())
  }

  private def draw() = {
    val msg = s"\r[${anim(count % anim.size)}] ${status()}"
    print(msg)
  }

  private def status() = s"$count docs [$watch]"
}
