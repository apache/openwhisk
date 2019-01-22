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

package org.apache.openwhisk.connector.lean

import scala.concurrent.duration._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.connector.MessageConsumer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

class LeanConsumer(queue: BlockingQueue[Array[Byte]], override val maxPeek: Int)(implicit logging: Logging)
    extends MessageConsumer {

  /**
   * Long poll for messages. Method returns once message available but no later than given
   * duration.
   *
   * @param duration the maximum duration for the long poll
   */
  override def peek(duration: FiniteDuration, retry: Int): Iterable[(String, Int, Long, Array[Byte])] = {
    Option(queue.poll(duration.toMillis, TimeUnit.MILLISECONDS))
      .map(record => Iterable(("", 0, 0L, record)))
      .getOrElse(Iterable.empty)
  }

  /**
   * There's no cursor to advance since that's done in the poll above.
   */
  override def commit(retry: Int): Unit = { /*do nothing*/ }

  override def close(): Unit = {
    logging.info(this, s"closing lean consumer")
  }
}
