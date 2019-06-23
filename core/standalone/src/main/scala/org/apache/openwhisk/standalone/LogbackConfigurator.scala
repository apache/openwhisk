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

package org.apache.openwhisk.standalone

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

import akka.event.Logging.LogLevel
import akka.event.LoggingAdapter
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import ch.qos.logback.core.pattern.color.ANSIConstants._
import ch.qos.logback.core.util.StatusPrinter
import org.apache.commons.io.IOUtils
import org.apache.openwhisk.common.{AkkaLogging, Logging, TransactionId}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Resets the Logback config if logging is configure via non standard file
 */
object LogbackConfigurator {

  def initLogging(conf: Conf): Unit = {
    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(toLevel(conf.verbose()))
  }

  private def toLevel(v: Int) = {
    v match {
      case 0 => Level.INFO
      case 1 => Level.DEBUG
      case _ => Level.ALL
    }
  }

  def configureLogbackFromResource(resourceName: String): Unit = {
    Try(configureLogback(IOUtils.resourceToString("/" + resourceName, UTF_8))).failed.foreach(t =>
      println(s"Could not load resource $resourceName- ${t.getMessage}"))
  }

  private def configureLogback(fileContent: String): Unit = {
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

    try {
      val configurator = new JoranConfigurator
      configurator.setContext(context)
      // Call context.reset() to clear any previous configuration, e.g. default
      // configuration. For multi-step configuration, omit calling context.reset().
      context.reset()
      val is = new ByteArrayInputStream(fileContent.getBytes(UTF_8))
      configurator.doConfigure(is)
    } catch {
      case _: JoranException =>
      // StatusPrinter will handle this
    }
    StatusPrinter.printInCaseOfErrorsOrWarnings(context)
  }
}

/**
 * Similar to AkkaLogging but with color support
 */
class ColoredAkkaLogging(loggingAdapter: LoggingAdapter) extends Logging {
  private val setDefaultColor = ESC_START + "0;" + DEFAULT_FG + ESC_END
  def emit(loglevel: LogLevel, id: TransactionId, from: AnyRef, message: => String) = {
    if (loggingAdapter.isEnabled(loglevel)) {
      val logmsg: String = message // generates the message
      if (logmsg.nonEmpty) { // log it only if its not empty
        val name = if (from.isInstanceOf[String]) from else Logging.getCleanSimpleClassName(from.getClass)
        loggingAdapter.log(loglevel, s"[${clr(id.toString, BOLD)}] [${clr(name.toString, CYAN_FG)}] $logmsg")
      }
    }
  }

  def clr(s: String, code: String) = s"$ESC_START$code$ESC_END$s$setDefaultColor"
}
