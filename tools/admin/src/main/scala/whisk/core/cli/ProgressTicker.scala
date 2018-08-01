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

abstract class ProgressBarBase(val colored: Boolean = true) extends Ticker {
  protected val width = 10
  protected val nextDrawDelta = 200 //Redraw ever this millis
  protected val watch = Stopwatch.createStarted()

  protected val barCurrent: String
  protected val barCurrentN: String
  protected val barRemain: String

  protected val greenCol = color("\u001B[1;92m") //Green
  protected val normalCol = color("\u001B[0m") //Normal

  protected val colStart: String
  protected val colCur: String
  protected val colNorm: String

  protected var count = 0
  protected var pos = 0
  private var lastDrawTime = 0L
  private var maxStatusLength = 0

  override def tick(): Unit = {
    count += 1
    draw()
  }

  override def close(): Unit = {
    println(s"\r${statusPrefix()}${statusSuffix()}")
  }

  protected def doMove(): Unit

  protected def statusSuffix(): String

  protected def statusPrefix(): String

  private def draw() = {
    if (move()) {
      val msg = s"\r${statusPrefix()}[${animation()}] ${statusSuffix()}"
      val msgToPrint = if (msg.length > maxStatusLength) {
        maxStatusLength = msg.length
        msg
      } else {
        msg + " " * (maxStatusLength - msg.length) //Add padding
      }
      print(msgToPrint)
    }
  }

  private def animation(): String = {
    s"$colStart${barCurrent * pos}$colCur$barCurrentN$colNorm${barRemain * (width - pos)}"
  }

  protected def speed(): String = "%.0f/s".format(rate())

  protected def rate(): Double = count * 1.0 / (watch.elapsed(TimeUnit.SECONDS) + 1)

  protected def elapsedTime(): String = formatTime(watch.elapsed(TimeUnit.SECONDS))

  protected def formatTime(s: Long) = "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)

  private def move(): Boolean = {
    val elapsesMillis = watch.elapsed(TimeUnit.MILLISECONDS)
    if (elapsesMillis - lastDrawTime >= nextDrawDelta) {
      lastDrawTime = elapsesMillis
      doMove()
      true
    } else false
  }
  private def color(code: String) = if (colored) code else ""
}

class InfiniteProgressBar(action: String, override val colored: Boolean = true) extends ProgressBarBase() {
  private var movingRight = true

  override protected val barCurrent = "-"
  override protected val barCurrentN = "*"
  override protected val barRemain = "-"

  override protected val colStart = normalCol
  override protected val colCur = greenCol
  override protected val colNorm = normalCol

  override protected def doMove(): Unit = {
    if (movingRight) pos += 1 else pos -= 1

    if (pos == 0) movingRight = true
    if (pos == width) movingRight = false
  }

  override protected def statusSuffix() = s"$count docs ${speed()} (${elapsedTime()})"
  override protected def statusPrefix() = s"$action "
}

class FiniteProgressBar(action: String, total: Long, override val colored: Boolean = true) extends ProgressBarBase() {
  override protected val barCurrent = "="
  override protected val barCurrentN = ">"
  override protected val barRemain = "-"

  override protected val colStart = greenCol
  override protected val colCur = greenCol
  override protected val colNorm = normalCol

  override protected def doMove(): Unit = {
    pos = progress(width)
  }

  override protected def statusSuffix() = s"$count/$total docs ${speed()} (${elapsedTime()} / ${eta()})"
  override protected def statusPrefix() = s"$action ${progressPercent()}% "

  private def progressPercent() = progress(100)
  private def progress(n: Int) = Math.ceil((count.toFloat / total) * n).toInt
  private def eta() = formatTime(((total - count) / rate()).toLong)
}

object ConsoleUtil {
  def showProgressBar(): Boolean = System.console() != null
}
