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

package whisk.core.connector

import scala.concurrent.duration.Duration

trait MessageConsumer {

    /** The maximum number of messages peeked (i.e., max number of messages retrieved during a long poll). */
    val maxPeek: Int

    /**
     * Gets messages via a long poll. May or may not remove messages
     * from the message connector. Use commit() to ensure messages are
     * removed from the connector.
     *
     * @param duration for the long poll
     * @return iterable collection (topic, partition, offset, bytes)
     */
    def peek(duration: Duration): Iterable[(String, Int, Long, Array[Byte])]

    /**
     * Commits offsets from last peek operation to ensure they are removed
     * from the connector.
     */
    def commit()

    /**
     * Calls process for every message received. Process receives a tuple
     * (topic, partition, offset, and message as byte array).
     */
    def onMessage(process: (String, Int, Long, Array[Byte]) => Unit): Unit

    /** Closes consumer. */
    def close(): Unit

}
