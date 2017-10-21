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

package whisk.core.containerpool.docker.test

import java.nio.charset.StandardCharsets

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FlatSpec
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner

import common.StreamLogging
import spray.json.pimpAny
import whisk.common.TransactionId
import whisk.core.entity.size._
import whisk.http.Messages
import whisk.core.containerpool.docker.DockerActionLogDriver
import whisk.core.containerpool.docker.LogLine

@RunWith(classOf[JUnitRunner])
class ActionLogDriverTests
    extends FlatSpec
    with BeforeAndAfter
    with Matchers
    with DockerActionLogDriver
    with StreamLogging {

  private def makeLogMsgs(lines: Seq[String], stream: String = "stdout", addSentinel: Boolean = true) = {
    val msgs = if (addSentinel) {
      lines.map((stream, _)) :+
        ("stdout", s"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}") :+
        ("stderr", s"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}")
    } else {
      lines.map((stream, _))
    }

    msgs
      .map(p => LogLine("", p._1, p._2).toJson.compactPrint)
      .mkString("\n")
  }

  private def makeLogLines(lines: Seq[String], stream: String = "stdout") = {
    lines.map(LogLine("", stream, _)).filter(_.log.nonEmpty).map(_.toFormattedString).toVector
  }

  behavior of "LogLine"

  it should "truncate log line" in {
    "❄".sizeInBytes shouldBe 3.B

    Seq("abcdef", "❄ ☃ ❄").foreach { logline =>
      val bytes = logline.sizeInBytes
      LogLine("", "", logline).dropRight(0.B).log shouldBe logline
      LogLine("", "", logline).dropRight(1.B).log shouldBe {
        val truncated = logline.getBytes(StandardCharsets.UTF_8).dropRight(1)
        new String(truncated, StandardCharsets.UTF_8)
      }
    }
  }

  behavior of "ActionLogDriver"

  it should "mock container log drain" in {
    makeLogMsgs(Seq("a", "b", "c")) shouldBe {
      raw"""|{"time":"","stream":"stdout","log":"a"}
                  |{"time":"","stream":"stdout","log":"b"}
                  |{"time":"","stream":"stdout","log":"c"}
                  |{"time":"","stream":"stdout","log":"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}"}
                  |{"time":"","stream":"stderr","log":"${DockerActionLogDriver.LOG_ACTIVATION_SENTINEL}"}"""
        .stripMargin('|')
    }
  }

  it should "handle empty logs" in {
    implicit val tid = TransactionId.testing
    processJsonDriverLogContents("", true, 0.B) shouldBe {
      (false, false, Vector())
    }

    processJsonDriverLogContents("", false, 0.B) shouldBe {
      (true, false, Vector())
    }
  }

  it should "not truncate logs within limit" in {
    implicit val tid = TransactionId.testing

    Seq((Seq("\n"), 1), (Seq("a"), 1), (Seq("❄"), 3), (Seq("", "a", "❄"), 4), (Seq("abc\n", "abc\n"), 8))
      .foreach {
        case (msgs, l) =>
          Seq(false).foreach { sentinel =>
            processJsonDriverLogContents(makeLogMsgs(msgs, addSentinel = sentinel), sentinel, l.B) shouldBe {
              (true, false, makeLogLines(msgs))
            }
          }
      }
  }

  it should "account for sentinels when logs are not from a sentinelled action runtime" in {
    implicit val tid = TransactionId.testing

    Seq((Seq(""), 0), (Seq("\n"), 1), (Seq("a"), 1), (Seq("❄"), 3), (Seq("", "a", "❄"), 4), (Seq("abc\n", "abc\n"), 8))
      .foreach {
        case (msgs, l) =>
          processJsonDriverLogContents(makeLogMsgs(msgs, addSentinel = true), false, l.B) shouldBe {
            (true, true, makeLogLines(msgs) ++ Vector(Messages.truncateLogs(l.B)))
          }
      }
  }

  it should "truncate logs exceeding limit" in {
    implicit val tid = TransactionId.testing

    Seq(
      (Seq("\n"), Seq(), 0),
      (Seq("a"), Seq(), 0),
      (Seq("ab"), Seq("a"), 1),
      (Seq("❄"), Seq("�"), 1),
      (Seq("❄"), Seq("�"), 2),
      (Seq("abc\n", "abc\n", "abc\n"), Seq("abc\n", "abc\n"), 8))
      .foreach {
        case (msgs, exp, l) =>
          Seq(true, false).foreach { sentinel =>
            processJsonDriverLogContents(makeLogMsgs(msgs, addSentinel = sentinel), sentinel, l.B) shouldBe {
              (!sentinel, true, makeLogLines(exp) ++ Vector(Messages.truncateLogs(l.B)))
            }
          }
      }
  }
}
