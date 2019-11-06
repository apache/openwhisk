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

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8

import org.apache.commons.io.FileUtils
import pureconfig.ConfigReader
import pureconfig.ConvertHelpers.catchReadError

class ConfigMapValue private (val value: String)

object ConfigMapValue {

  /**
   * Checks if the value is a file url like `file:/etc/config/foo.yaml` then treat it as a file reference
   * and read its content otherwise consider it as a literal value
   */
  def apply(config: String): ConfigMapValue = {
    val value = if (config.startsWith("file:")) {
      val uri = new URI(config)
      val file = new File(uri)
      FileUtils.readFileToString(file, UTF_8)
    } else config
    new ConfigMapValue(value)
  }

  implicit val reader: ConfigReader[ConfigMapValue] = ConfigReader.fromString[ConfigMapValue](catchReadError(apply))
}
