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

import java.io.File

import org.apache.commons.lang3.StringUtils
import org.apache.openwhisk.standalone.ColorOutput.clr

import scala.io.AnsiColor

class ServiceInfoLogger(conf: Conf, services: Seq[ServiceContainer], workDir: File) extends AnsiColor {
  private val separator = "=" * 80

  def run(): Unit = {
    println(separator)
    println("Launched service details")
    println()
    services.foreach(logService)
    println()
    println(s"Local working directory - ${workDir.getAbsolutePath}")
    println(separator)
  }

  private def logService(s: ServiceContainer): Unit = {
    val msg = s"${portInfo(s.port)} ${s.description} (${clr(s.name, BOLD, conf.colorEnabled)})"
    println(msg)
  }

  private def portInfo(port: Int) = {
    val msg = StringUtils.center(port.toString, 7)
    s"[${clr(msg, GREEN, conf.colorEnabled)}]"
  }
}
