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

package common

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.PrintStreamLogging
import java.nio.charset.StandardCharsets

/**
 * Logging facility, that can be used by tests.
 *
 * It contains the implicit Logging-instance, that is needed implicitly for some methods and classes.
 * the logger logs to the stream, that can be accessed from your test, to check if a specific message has been written.
 */
trait StreamLogging {
  lazy val stream = new ByteArrayOutputStream
  lazy val printstream = new PrintStream(stream)
  implicit lazy val logging: Logging = new PrintStreamLogging(printstream)

  def logLines = new String(stream.toByteArray, StandardCharsets.UTF_8).linesIterator.toList
}
