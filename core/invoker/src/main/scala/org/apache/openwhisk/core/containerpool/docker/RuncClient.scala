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

package org.apache.openwhisk.core.containerpool.docker

import akka.actor.ActorSystem

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.util.Failure
import org.apache.openwhisk.common.TransactionId

import scala.util.Success
import org.apache.openwhisk.common.LoggingMarkers
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.ConfigKeys
import pureconfig._
import pureconfig.generic.auto._
import akka.event.Logging.{ErrorLevel, InfoLevel}
import org.apache.openwhisk.core.containerpool.ContainerId

import scala.concurrent.duration.Duration

/**
 * Configuration for runc client command timeouts.
 */
case class RuncClientTimeouts(pause: Duration, resume: Duration)

/**
 * Serves as interface to the docker CLI tool.
 *
 * Be cautious with the ExecutionContext passed to this, as the
 * calls to the CLI are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class RuncClient(timeouts: RuncClientTimeouts = loadConfigOrThrow[RuncClientTimeouts](ConfigKeys.runcTimeouts))(
  executionContext: ExecutionContext)(implicit log: Logging, as: ActorSystem)
    extends RuncApi
    with ProcessRunner {
  implicit private val ec = executionContext

  // Determines how to run docker. Failure to find a Docker binary implies
  // a failure to initialize this instance of DockerClient.
  protected val runcCmd: Seq[String] = Seq("/usr/bin/docker-runc")

  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("pause", id.asString), timeouts.pause).map(_ => ())

  def resume(id: ContainerId)(implicit transid: TransactionId): Future[Unit] =
    runCmd(Seq("resume", id.asString), timeouts.resume).map(_ => ())

  private def runCmd(args: Seq[String], timeout: Duration)(implicit transid: TransactionId): Future[String] = {
    val cmd = runcCmd ++ args
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_RUNC_CMD(args.head),
      s"running ${cmd.mkString(" ")} (timeout: $timeout)",
      logLevel = InfoLevel)
    executeProcess(cmd, timeout).andThen {
      case Success(_) => transid.finished(this, start, logLevel = InfoLevel)
      case Failure(t) => transid.failed(this, start, t.getMessage, ErrorLevel)
    }
  }
}

trait RuncApi {

  /**
   * Pauses the container with the given id.
   *
   * @param id the id of the container to pause
   * @return a Future completing according to the command's exit-code
   */
  def pause(id: ContainerId)(implicit transid: TransactionId): Future[Unit]

  /**
   * Unpauses the container with the given id.
   *
   * @param id the id of the container to unpause
   * @return a Future completing according to the command's exit-code
   */
  def resume(id: ContainerId)(implicit transid: TransactionId): Future[Unit]
}
