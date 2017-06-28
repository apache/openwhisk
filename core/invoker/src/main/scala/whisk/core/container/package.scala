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

package whisk.core

import java.time.Instant

import scala.concurrent.duration._

import whisk.core.entity.ActivationResponse._
import whisk.core.entity.DocRevision
import whisk.core.entity.UUID

/**
 * This object contains type definitions that are useful when observing and timing container operations.
 */
package object container {

    /**
     * Identifies a combination of owner+action+version (except special cases)
     */
    class ActionContainerId(val stringRepr: String) extends AnyVal

    object ActionContainerId {
        // Convenience "constructor" since this is the most common case.
        def apply(uuid: UUID, actionFullyQualifiedName: String, actionRevision: DocRevision) =
            new ActionContainerId(s"instantiated.${uuid}.${actionFullyQualifiedName}.${actionRevision}")
    }

    /**
     * Special case for stem cell containers
     */
    val StemCellNodeJsActionContainerId = new ActionContainerId("stemcell.nodejs")

    /**
     * Represents a time interval, which can be viewed as a duration for which
     *  the start/end instants are fully known (as opposed to being relative).
     */
    case class Interval(start: Instant, end: Instant) {
        def duration = Duration.create(end.toEpochMilli() - start.toEpochMilli(), MILLISECONDS)
    }

    object Interval {
        /** An interval starting now with zero duration. */
        def zero = {
            val now = Instant.now
            Interval(now, now)
        }
    }

    /**
     * Represents the result of accessing an endpoint in a container:
     * Start time, End time, Some(response) from container consisting of status code and payload
     * If there is no response or an exception, then None.
     */
    case class RunResult(interval: Interval, response: Either[ContainerConnectionError, ContainerResponse]) {
        def duration = interval.duration
        def ok = response.right.exists(_.ok)
        def errored = !ok
        def toBriefString = response.fold(_.toString, _.toString)
    }

    /**
     * The result of trying to obtain a container that has already run this user+action in the past.
     */
    sealed trait CacheResult

    case object CacheMiss extends CacheResult
    case object CacheBusy extends CacheResult
    case class CacheHit(con: WhiskContainer) extends CacheResult

    /**
     * The result of trying to obtain a container which is known to exist or to create one.
     * Capacity constraints have been passed by this point so there are no Busy's.
     * Initiailization is performed later so no field for initResult here.
     */
    sealed trait ContainerResult

    case class Warm(con: WhiskContainer) extends ContainerResult
    case class Cold(con: WhiskContainer) extends ContainerResult

    // Note: not using InetAddress here because we don't want to do any lookup
    // until used for something.
    case class ContainerAddr(host: String, port: Int) {
        override def toString() = s"$host:$port"
    }

    sealed abstract class ContainerIdentifier(val id: String) {
        override def toString = id
    }
    class ContainerName(val name: String) extends ContainerIdentifier(name) {
        override def toString = id
    }
    class ContainerHash(val hash: String) extends ContainerIdentifier(hash) {
        override def toString = id
    }

    object ContainerIdentifier {
        def fromString(str: String): ContainerIdentifier = {
            val s = str.trim
            require(!s.contains("\n"))
            if (s.matches("^[0-9a-fA-F]+$")) {
                new ContainerHash(s)
            } else {
                new ContainerName(s)
            }
        }
    }

    object ContainerName {
        def fromString(str: String) = new ContainerName(str)
    }

    object ContainerHash {
        def fromString(str: String) = {
            require(str.matches("^[0-9a-fA-F]+$"))
            new ContainerHash(str)
        }
    }

    final class DockerOutput(val toOption: Option[String]) extends AnyVal
    object DockerOutput {
        def apply(content: String) = new DockerOutput(Some(content))
        def unavailable = new DockerOutput(None)

        def isSuccessful(output: DockerOutput): Boolean =
            output match {
                case output if output == DockerOutput.unavailable => false
                case _ => true
            }
    }

    sealed class ContainerError(val msg: String) extends Throwable(msg)
    case class WhiskContainerError(override val msg: String) extends ContainerError(msg)
    case class BlackBoxContainerError(override val msg: String) extends ContainerError(msg)
}
