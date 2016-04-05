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

import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Try

import spray.json.JsNumber
import spray.json.JsValue
import spray.json.RootJsonFormat

object Verbosity extends Enumeration {
    type Level = Value
    val Quiet, Loud, Debug = Value
}

/**
 * A transaction id.
 */
case class TransactionId private (id: Long) extends AnyVal {
    override def toString = if (id == -1) "??" else s"#tid_$id"
}

object TransactionId {
    val dontcare = TransactionId(-1)

    def apply(tid: BigDecimal): Try[TransactionId] = {
        Try { TransactionId(tid.toLong) }
    }

    implicit val serdes = new RootJsonFormat[TransactionId] {
        def write(t: TransactionId) = JsNumber(t.id)

        def read(value: JsValue) = Try {
            val JsNumber(tid) = value
            TransactionId(tid.longValue)
        } getOrElse dontcare
    }
}

/**
 * A thread-safe transaction counter.
 */
trait TransactionCounter {
    def transid(): TransactionId = {
        TransactionId(cnt.incrementAndGet())
    }

    private val cnt = new AtomicInteger(0)
}

/**
 * A logging facility in which output is one line and fields are bracketed.
 *
 */
trait Logging {
    var outputStream: PrintStream = Console.out

    def setVerbosity(level: Verbosity.Level) =
        this.level = level

    def getVerbosity() = level

    def setComponentName(comp: String) =
        this.componentName = comp

    def debug(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.dontcare) =
        if (level == Verbosity.Debug)
            emit("DEBUG", id, from, message)

    def info(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.dontcare) =
        if (level != Verbosity.Quiet)
            emit("INFO", id, from, message)

    def warn(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.dontcare) =
        if (level != Verbosity.Quiet)
            emit("WARN", id, from, message)

    def error(from: AnyRef, message: String)(implicit id: TransactionId = TransactionId.dontcare) =
        emit("ERROR", id, from, message)

    def emit(category: AnyRef, id: TransactionId, from: AnyRef, message: String) = {
        val now = Instant.now(Clock.systemUTC())
        val time = Logging.timeFormat.format(now)
        val name = if (from.isInstanceOf[String]) {
            from
        } else Logging.getCleanSimpleClassName(from.getClass)
        if (componentName != "")
            outputStream.println(s"[$time] [$category] [$id] [$componentName] [$name] $message")
        else
            outputStream.println(s"[$time] [$category] [$id] [$name] $message")
    }

    private var level = Verbosity.Quiet
    private var componentName = "";
    private var sequence = new AtomicInteger()
}

private object Logging {
    /**
     * Given a class object, return its simple name less the trailing dollar sign.
     */
    def getCleanSimpleClassName(clz: Class[_]) = {
        val simpleName = clz.getSimpleName
        if (simpleName.endsWith("$")) simpleName.dropRight(1)
        else simpleName
    }

    private val timeFormat = DateTimeFormatter.
        ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").
        withZone(ZoneId.of("UTC"))
}
