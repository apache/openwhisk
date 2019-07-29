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
import java.io.Closeable
import java.util.concurrent.{ExecutorService, TimeUnit}

import akka.event.slf4j.SLF4JLogging

import scala.concurrent.duration._

case class ExecutorCloser(service: ExecutorService, timeout: FiniteDuration = 5.seconds)
    extends Closeable
    with SLF4JLogging {
  override def close(): Unit = {
    try {
      service.shutdown()
      service.awaitTermination(timeout.toSeconds, TimeUnit.SECONDS)
    } catch {
      case e: InterruptedException =>
        log.error("Error while shutting down the ExecutorService", e)
        Thread.currentThread.interrupt()
    } finally {
      if (!service.isShutdown) {
        log.warn(s"ExecutorService `$service` didn't shutdown property. Will be forced now.")
      }
      service.shutdownNow()
    }
  }
}
