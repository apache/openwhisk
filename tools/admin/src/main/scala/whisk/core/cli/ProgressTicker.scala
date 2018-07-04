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

import java.util.concurrent.TimeUnit

import com.google.common.base.Stopwatch

trait Ticker {

  def tick(): Unit = {}

  def close(): Unit = {}

}

object NoopTicker extends Ticker

class ProgressTicker extends Ticker {
  private val width = 10
  private val nextDrawDelta = 10
  private val watch = Stopwatch.createStarted()
  private val barBase = "-"
  private val barCurrent = "*"

  private var count = 0
  private var pos = 0
  private var movingRight = true

  override def tick(): Unit = {
    count += 1
    draw()
  }

  override def close(): Unit = {
    println("\r" + status())
  }

  private def draw() = {
    move()
    val msg = s"\r[${animation()}] ${status()}"
    print(msg)
  }

  private def animation() = {
    s"${barBase * pos}$barCurrent${barBase * (width - pos)}"
  }

  private def speed(): String = "%.0f/s".format(count * 1.0 / watch.elapsed(TimeUnit.SECONDS))

  private def move(): Unit = {
    if (count % nextDrawDelta == 0) {
      if (movingRight) pos += 1 else pos -= 1

      if (pos == 0) movingRight = true
      if (pos == width) movingRight = false
    }
  }

  private def status() = s"$count docs $speed [$watch]"
}
