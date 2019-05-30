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

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * JDK 11 Instant uses nano second precision by default. However OpenWhisk usage of Instant in
 * many cases involves storing the timestamp in database which uses milli second precision.
 *
 * To ensure consistency below utilities can be used to truncate the Instant to millis
 */
trait WhiskInstants {

  implicit class InstantImplicits(i: Instant) {
    def inMills = i.truncatedTo(ChronoUnit.MILLIS)
  }

  def nowInMillis(): Instant = Instant.now.truncatedTo(ChronoUnit.MILLIS)

}

object WhiskInstants extends WhiskInstants
