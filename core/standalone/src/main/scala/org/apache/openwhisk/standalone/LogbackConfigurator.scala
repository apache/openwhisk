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

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.joran.spi.JoranException
import ch.qos.logback.core.util.StatusPrinter
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * Resets the Logback config if logging is configure via non standard file
 */
object LogbackConfigurator {

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
