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

import org.apache.openwhisk.common.Logging

import scala.util.control.NonFatal

trait Exceptions {

  /**
   * Executes the block, catches and logs a NonFatal exception and swallows it.
   *
   * @param task description of the task that's being executed
   * @param block the block to execute
   */
  def tryAndSwallow(task: String)(block: => Any)(implicit logging: Logging): Unit = {
    try block
    catch {
      case NonFatal(t) => logging.error(this, s"$task failed: $t")
    }
  }

  /**
   * Executes the block, catches and logs a NonFatal exception and rethrows it.
   *
   * @param task description of the task that's being executed
   * @param block the block to execute
   */
  def tryAndThrow[T](task: String)(block: => T)(implicit logging: Logging): T = {
    try block
    catch {
      case NonFatal(t) =>
        logging.error(this, s"$task failed: $t")
        throw t
    }
  }

}
