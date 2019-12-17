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

package org.apache.openwhisk.http

import akka.event.Logging
import org.apache.openwhisk.common.{MetricsRoute, TransactionId}

/**
 * This trait extends the BasicHttpService with a standard "ping" endpoint which
 * responds to health queries, intended for monitoring.
 */
trait BasicRasService extends BasicHttpService {

  override def routes(implicit transid: TransactionId) = ping ~ MetricsRoute()

  override def loglevelForRoute(route: String): Logging.LogLevel = {
    if (route == "/ping" || route == "/metrics") {
      Logging.DebugLevel
    } else {
      super.loglevelForRoute(route)
    }
  }

  val ping = path("ping") {
    get { complete("pong") }
  }
}
