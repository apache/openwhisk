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

package whisk.core.containerpool

import akka.actor.ActorSystem
import java.time.Instant
import akka.stream.scaladsl.Source
import akka.util.ByteString
import pureconfig._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import spray.json.JsObject
import spray.json.DefaultJsonProtocol._
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionId
import whisk.core.entity.ActivationResponse
import whisk.core.entity.ActivationResponse.ContainerConnectionError
import whisk.core.entity.ActivationResponse.ContainerResponse
import whisk.core.entity.ByteSize
import whisk.http.Messages
import akka.event.Logging.InfoLevel
import whisk.core.ConfigKeys
import whisk.core.entity.ActivationEntityLimit

/**
 * An OpenWhisk biased container abstraction. This is **not only** an abstraction
 * for different container providers, but the implementation also needs to include
 * OpenWhisk specific behavior, especially for initialize and run.
 */
case class ContainerId(val asString: String) {
  require(asString.nonEmpty, "ContainerId must not be empty")
}
case class ContainerAddress(val host: String, val port: Int = 8080) {
  require(host.nonEmpty, "ContainerIp must not be empty")
}

trait Container {

  implicit protected val as: ActorSystem
  protected val id: ContainerId
  protected val addr: ContainerAddress
  protected implicit val logging: Logging
  protected implicit val ec: ExecutionContext

  protected[containerpool] val config: ContainerPoolConfig =
    loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool)

  /** HTTP connection to the container, will be lazily established by callContainer */
  protected var httpConnection: Option[ContainerClient] = None

  /** Stops the container from consuming CPU cycles. */
  def suspend()(implicit transid: TransactionId): Future[Unit]

  /** Dual of halt. */
  def resume()(implicit transid: TransactionId): Future[Unit]

  /** Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear. */
  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any]

  /** Completely destroys this instance of the container. */
  def destroy()(implicit transid: TransactionId): Future[Unit] = {
    Future.successful(httpConnection.foreach(_.close()))
  }

  /** Initializes code in the container. */
  def initialize(initializer: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval] = {
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_ACTIVATION_INIT,
      s"sending initialization to $id $addr",
      logLevel = InfoLevel)

    val body = JsObject("value" -> initializer)
    callContainer("/init", body, timeout, retry = true)
      .andThen { // never fails
        case Success(r: RunResult) =>
          transid.finished(
            this,
            start.copy(start = r.interval.start),
            s"initialization result: ${r.toBriefString}",
            endTime = r.interval.end,
            logLevel = InfoLevel)
        case Failure(t) =>
          transid.failed(this, start, s"initializiation failed with $t")
      }
      .flatMap { result =>
        if (result.ok) {
          Future.successful(result.interval)
        } else if (result.interval.duration >= timeout) {
          Future.failed(
            InitializationError(
              result.interval,
              ActivationResponse.applicationError(Messages.timedoutActivation(timeout, true))))
        } else {
          Future.failed(
            InitializationError(
              result.interval,
              ActivationResponse.processInitResponseContent(result.response, logging)))
        }
      }
  }

  /** Runs code in the container. */
  def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(
    implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
    val actionName = environment.fields.get("action_name").map(_.convertTo[String]).getOrElse("")
    val start =
      transid.started(
        this,
        LoggingMarkers.INVOKER_ACTIVATION_RUN,
        s"sending arguments to $actionName at $id $addr",
        logLevel = InfoLevel)

    val parameterWrapper = JsObject("value" -> parameters)
    val body = JsObject(parameterWrapper.fields ++ environment.fields)
    callContainer("/run", body, timeout, retry = false)
      .andThen { // never fails
        case Success(r: RunResult) =>
          transid.finished(
            this,
            start.copy(start = r.interval.start),
            s"running result: ${r.toBriefString}",
            endTime = r.interval.end,
            logLevel = InfoLevel)
        case Failure(t) =>
          transid.failed(this, start, s"run failed with $t")
      }
      .map { result =>
        val response = if (result.interval.duration >= timeout) {
          ActivationResponse.applicationError(Messages.timedoutActivation(timeout, false))
        } else {
          ActivationResponse.processRunResponseContent(result.response, logging)
        }

        (result.interval, response)
      }
  }

  /**
   * Makes an HTTP request to the container.
   *
   * Note that `http.post` will not throw an exception, hence the generated Future cannot fail.
   *
   * @param path relative path to use in the http request
   * @param body body to send
   * @param timeout timeout of the request
   * @param retry whether or not to retry the request
   */
  protected def callContainer(path: String, body: JsObject, timeout: FiniteDuration, retry: Boolean = false)(
    implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val http = httpConnection.getOrElse {
      val conn = if (config.akkaClient) {
        new AkkaContainerClient(addr.host, addr.port, timeout, ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT, 1024)
      } else {
        new ApacheBlockingContainerClient(
          s"${addr.host}:${addr.port}",
          timeout,
          ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT)
      }
      httpConnection = Some(conn)
      conn
    }
    http
      .post(path, body, retry)
      .map { response =>
        val finished = Instant.now()
        RunResult(Interval(started, finished), response)
      }
  }
}

/** Indicates a general error with the container */
sealed abstract class ContainerError(msg: String) extends Exception(msg)

/** Indicates an error while starting a container */
sealed abstract class ContainerStartupError(msg: String) extends ContainerError(msg)

/** Indicates any error while starting a container either of a managed runtime or a non-application-specific blackbox container */
case class WhiskContainerStartupError(msg: String) extends ContainerStartupError(msg)

/** Indicates an application-specific error while starting a blackbox container */
case class BlackboxStartupError(msg: String) extends ContainerStartupError(msg)

/** Indicates an error while initializing a container */
case class InitializationError(interval: Interval, response: ActivationResponse) extends Exception(response.toString)

case class Interval(start: Instant, end: Instant) {
  def duration = Duration.create(end.toEpochMilli() - start.toEpochMilli(), MILLISECONDS)
}

case class RunResult(interval: Interval, response: Either[ContainerConnectionError, ContainerResponse]) {
  def ok = response.right.exists(_.ok)
  def toBriefString = response.fold(_.toString, _.toString)
}

object Interval {

  /** An interval starting now with zero duration. */
  def zero = {
    val now = Instant.now
    Interval(now, now)
  }
}
