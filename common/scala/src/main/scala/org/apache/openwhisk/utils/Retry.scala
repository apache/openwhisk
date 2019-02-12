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

package org.apache.openwhisk.utils

import scala.concurrent.duration._

object retry {

  /**
   * Retries a method which returns a value or throws an exception on failure, up to N times,
   * and optionally sleeping up to specified duration between retries.
   *
   * @param fn the function to retry; fn is expected to throw an exception if it fails, else should return a value of type T
   * @param N the maximum number of times to apply fn, must be >= 1
   * @param waitBeforeRetry an option specifying duration to wait before retrying method, will not wait if none given
   * @param retryMessage an optional message to emit before retrying function
   * @return the result of fn iff it is successful
   * @throws Throwable exception from fn (or an illegal argument exception if N is < 1)
   */
  def apply[T](fn: => T,
               N: Int = 3,
               waitBeforeRetry: Option[Duration] = Some(50.milliseconds),
               retryMessage: Option[String] = None): T = {
    require(N >= 1, "maximum number of fn applications must be greater than 1")

    try fn
    catch {
      case _ if N > 1 =>
        retryMessage.foreach(println)
        waitBeforeRetry.foreach(t => Thread.sleep(t.toMillis))
        retry(fn, N - 1, waitBeforeRetry, retryMessage)
    }
  }
}
