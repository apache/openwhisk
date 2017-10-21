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

package whisk.common

import java.util.Date
import java.text.SimpleDateFormat
import java.lang.System

/**
 * Utility methods for creating formatted date strings.
 *
 */
object DateUtil {

  /**
   * Returns the current time as a string in yyyy-MM-dd'T'HH:mm:ss.SSSZ format.
   */
  def getTimeString(): String = {
    val now = new Date(System.currentTimeMillis())
    timeFormat.synchronized {
      timeFormat.format(now)
    }
  }

  /**
   * Takes a string in a format given by getTimeString and returns time in epoch millis.
   */
  def parseToMilli(dateStr: String): Long = {
    val date = timeFormat.synchronized {
      timeFormat.parse(dateStr, new java.text.ParsePosition(0))
    }
    date.getTime()
  }

  private val timeFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

}
