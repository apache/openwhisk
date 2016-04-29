/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

import scala.math.BigDecimal.int2bigDecimal
import scala.util.Try

import spray.json.JsArray
import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat

/**
 * A transaction id for tracking operations in the system that are specific to a request.
 * An instance of TransactionId is implicitly received by all logging methods. This is a
 * value type so that the type system ensures it is never null. The actual metadata is stored
 * indirectly in the referenced meta object.
 */
case class TransactionId private (meta: TransactionMetadata) extends AnyVal {
    def apply() = meta.id
    override def toString = if (meta.id > 0) s"#tid_${meta.id}" else (if (meta.id < 0) s"#sid_${-meta.id}" else "??")

    /**
     * Computes elapsed milliseconds since start of transaction if given token is defined.
     *
     * @param token the marker name
     * @return Some LogMarker iff token is defined and None otherwise
     */
    def mark(token: LogMarkerToken): Option[LogMarker] = {
        val now = Instant.now(Clock.systemUTC())
        Option(token) filter { _.toString.trim.nonEmpty } map { _ =>
            val delta = now.toEpochMilli - meta.start.toEpochMilli
            LogMarker(now, delta, token.toString.trim)
        }
    }
}

/**
 * The transaction metadata encapsulates important properties about a transaction.
 *
 * @param id the transaction identifier; it is positive for client requests,
 *           negative for system operation and zero when originator is not known
 * @param start the timestamp when the request processing commenced
 */
protected case class TransactionMetadata(val id: Long, val start: Instant)

object TransactionId {
    val unknown = TransactionId(0)
    val testing = TransactionId(-1) // a common id for for unit testing
    val invoker = TransactionId(-100) // Invoker startup/shutdown or GC activity
    val invokerWarmup = TransactionId(-101) // Invoker warmup thread

    def apply(tid: BigDecimal): TransactionId = {
        Try {
            val now = Instant.now(Clock.systemUTC())
            TransactionId(TransactionMetadata(tid.toLong, now))
        } getOrElse unknown
    }

    implicit val serdes = new RootJsonFormat[TransactionId] {
        def write(t: TransactionId) = JsArray(JsNumber(t.meta.id), JsNumber(t.meta.start.toEpochMilli))

        def read(value: JsValue) = Try {
            value match {
                case JsArray(Vector(JsNumber(id), JsNumber(start))) =>
                    TransactionId(TransactionMetadata(id.longValue, Instant.ofEpochMilli(start.longValue)))
            }
        } getOrElse unknown
    }
}

/**
 * A thread-safe transaction counter.
 */
trait TransactionCounter {
    def transid(): TransactionId = {
        TransactionId(cnt.incrementAndGet())
    }

    private val cnt = new AtomicInteger(1)
}
