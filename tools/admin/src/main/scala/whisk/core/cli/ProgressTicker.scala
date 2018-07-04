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
  private val nextDrawDelta = 200 //Redraw ever this millis
  private val watch = Stopwatch.createStarted()
  private val barBase = "-"
  private val barCurrent = "*"

  private var count = 0
  private var pos = 0
  private var movingRight = true
  private var lastDrawTime = 0L
  private var maxStatusLength = 0

  override def tick(): Unit = {
    count += 1
    draw()
  }

  override def close(): Unit = {
    println("\r" + status())
  }

  private def draw() = {
    if (move()) {
      val msg = s"\r[${animation()}] ${status()}"
      print(msg)
    }
  }

  private def animation() = {
    s"${barBase * pos}$barCurrent${barBase * (width - pos)}"
  }

  private def speed(): String = "%.0f/s".format(count * 1.0 / (watch.elapsed(TimeUnit.SECONDS) + 1))

  private def move(): Boolean = {
    val elapsesMillis = watch.elapsed(TimeUnit.MILLISECONDS)
    if (elapsesMillis - lastDrawTime >= nextDrawDelta) {
      lastDrawTime = elapsesMillis
      if (movingRight) pos += 1 else pos -= 1

      if (pos == 0) movingRight = true
      if (pos == width) movingRight = false
      true
    } else false
  }

  private def status(): String = {
    val s = s"$count docs ${speed()} [$watch]"
    if (s.length > maxStatusLength) {
      maxStatusLength = s.length
      s
    } else {
      s + " " * (maxStatusLength - s.length)
    } //Add padding
  }
}

object ConsoleUtil {
  def showProgressBar(): Boolean = System.console() != null
}
