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

import java.time.Instant
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.Framing.FramingException
import spray.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool._
import org.apache.openwhisk.core.entity.ActivationResponse.{ConnectionError, MemoryExhausted}
import org.apache.openwhisk.core.entity.{ActivationEntityLimit, ByteSize}
import org.apache.openwhisk.core.entity.size._
import akka.stream.scaladsl.{Framing, Source}
import akka.stream.stage._
import akka.util.ByteString
import spray.json._
import org.apache.openwhisk.core.containerpool.logging.LogLine
import org.apache.openwhisk.core.entity.ExecManifest.ImageName
import org.apache.openwhisk.http.Messages

object DockerContainer {

  private val byteStringSentinel = ByteString(Container.ACTIVATION_LOG_SENTINEL)

  /**
   * Creates a container running on a docker daemon.
   *
   * @param transid transaction creating the container
   * @param image either a user provided (Left) or OpenWhisk provided (Right) image
   * @param memory memorylimit of the container
   * @param cpuShares sharefactor for the container
   * @param environment environment variables to set on the container
   * @param network network to launch the container in
   * @param dnsServers list of dns servers to use in the container
   * @param name optional name for the container
   * @param useRunc use docker-runc to pause/unpause container?
   * @return a Future which either completes with a DockerContainer or one of two specific failures
   */
  def create(transid: TransactionId,
             image: Either[ImageName, ImageName],
             registryConfig: Option[RuntimesRegistryConfig] = None,
             memory: ByteSize = 256.MB,
             cpuShares: Int = 0,
             environment: Map[String, String] = Map.empty,
             network: String = "bridge",
             dnsServers: Seq[String] = Seq.empty,
             dnsSearch: Seq[String] = Seq.empty,
             dnsOptions: Seq[String] = Seq.empty,
             name: Option[String] = None,
             useRunc: Boolean = true,
             dockerRunParameters: Map[String, Set[String]])(implicit docker: DockerApiWithFileAccess,
                                                            runc: RuncApi,
                                                            as: ActorSystem,
                                                            ec: ExecutionContext,
                                                            log: Logging): Future[DockerContainer] = {
    implicit val tid: TransactionId = transid

    val environmentArgs = environment.flatMap {
      case (key, value) => Seq("-e", s"$key=$value")
    }

    val params = dockerRunParameters.flatMap {
      case (key, valueList) => valueList.toList.flatMap(Seq(key, _))
    }

    // NOTE: --dns-option on modern versions of docker, but is --dns-opt on docker 1.12
    val dnsOptString = if (docker.clientVersion.startsWith("1.12")) { "--dns-opt" } else { "--dns-option" }
    val args = Seq(
      "--cpu-shares",
      cpuShares.toString,
      "--memory",
      s"${memory.toMB}m",
      "--memory-swap",
      s"${memory.toMB}m",
      "--network",
      network) ++
      environmentArgs ++
      dnsServers.flatMap(d => Seq("--dns", d)) ++
      dnsSearch.flatMap(d => Seq("--dns-search", d)) ++
      dnsOptions.flatMap(d => Seq(dnsOptString, d)) ++
      name.map(n => Seq("--name", n)).getOrElse(Seq.empty) ++
      params

    val registryConfigUrl = registryConfig.map(_.url).getOrElse("")
    val imageToUse = image.merge.resolveImageName(Some(registryConfigUrl))

    val pulled = image match {
      case Left(userProvided) if userProvided.tag.map(_ == "latest").getOrElse(true) =>
        // Iff the image tag is "latest" explicitly (or implicitly because no tag is given at all), failing to pull will
        // fail the whole container bringup process, because it is expected to pick up the very latest "untagged"
        // version every time.
        docker.pull(imageToUse).map(_ => true).recoverWith {
          case _ => Future.failed(BlackboxStartupError(Messages.imagePullError(imageToUse)))
        }
      case Left(_) =>
        // Iff the image tag is something else than latest, we tolerate an outdated image if one is available locally.
        // A `docker run` will be tried nonetheless to try to start a container (which will succeed if the image is
        // already available locally)
        docker.pull(imageToUse).map(_ => true).recover { case _ => false }
      case Right(_) =>
        // Iff we're not pulling at all (OpenWhisk provided image) we act as if the pull was successful.
        Future.successful(true)
    }

    for {
      pullSuccessful <- pulled
      id <- docker.run(imageToUse, args).recoverWith {
        case BrokenDockerContainer(brokenId, _) =>
          // Remove the broken container - but don't wait or check for the result.
          // If the removal fails, there is nothing we could do to recover from the recovery.
          docker.rm(brokenId)
          Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
        case _ =>
          // Iff the pull was successful, we assume that the error is not due to an image pull error, otherwise
          // the docker run was a backup measure to try and start the container anyway. If it fails again, we assume
          // the image could still not be pulled and wasn't available locally.
          if (pullSuccessful) {
            Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
          } else {
            Future.failed(BlackboxStartupError(Messages.imagePullError(imageToUse)))
          }
      }
      ip <- docker.inspectIPAddress(id, network).recoverWith {
        // remove the container immediately if inspect failed as
        // we cannot recover that case automatically
        case _ =>
          docker.rm(id)
          Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
      }
    } yield new DockerContainer(id, ip, useRunc)
  }
}

/**
 * Represents a container as run by docker.
 *
 * This class contains OpenWhisk specific behavior and as such does not necessarily
 * use docker commands to achieve the effects needed.
 *
 * @constructor
 * @param id the id of the container
 * @param addr the ip of the container
 */
class DockerContainer(protected val id: ContainerId,
                      protected[core] val addr: ContainerAddress,
                      protected val useRunc: Boolean)(implicit docker: DockerApiWithFileAccess,
                                                      runc: RuncApi,
                                                      override protected val as: ActorSystem,
                                                      protected val ec: ExecutionContext,
                                                      protected val logging: Logging)
    extends Container {

  /** The last read-position in the log file */
  private var logFileOffset = new AtomicLong(0)

  protected val logCollectingIdleTimeout: FiniteDuration = 2.seconds
  protected val logCollectingTimeoutPerMBLogLimit: FiniteDuration = 2.seconds
  protected val waitForOomState: FiniteDuration = 2.seconds
  protected val filePollInterval: FiniteDuration = 5.milliseconds

  override def suspend()(implicit transid: TransactionId): Future[Unit] = {
    super.suspend().flatMap(_ => if (useRunc) runc.pause(id) else docker.pause(id))
  }
  override def resume()(implicit transid: TransactionId): Future[Unit] = {
    (if (useRunc) { runc.resume(id) } else { docker.unpause(id) }).flatMap(_ => super.resume())
  }
  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    docker.rm(id)
  }

  /**
   * Was the container killed due to memory exhaustion?
   *
   * Retries because as all docker state-relevant operations, they won't
   * be reflected by the respective commands immediately but will take
   * some time to be propagated.
   *
   * @param retries number of retries to make
   * @return a Future indicating a memory exhaustion situation
   */
  private def isOomKilled(retries: Int = (waitForOomState / filePollInterval).toInt)(
    implicit transid: TransactionId): Future[Boolean] = {
    docker.isOomKilled(id)(TransactionId.invoker).flatMap { killed =>
      if (killed) Future.successful(true)
      else if (retries > 0) akka.pattern.after(filePollInterval, as.scheduler)(isOomKilled(retries - 1))
      else Future.successful(false)
    }
  }

  override protected def callContainer(
    path: String,
    body: JsObject,
    timeout: FiniteDuration,
    maxConcurrent: Int,
    retry: Boolean = false,
    reschedule: Boolean = false)(implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val http = httpConnection.getOrElse {
      val conn = if (Container.config.akkaClient) {
        new AkkaContainerClient(
          addr.host,
          addr.port,
          timeout,
          ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT,
          ActivationEntityLimit.MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT,
          1024)
      } else {
        new ApacheBlockingContainerClient(
          s"${addr.host}:${addr.port}",
          timeout,
          ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT,
          ActivationEntityLimit.MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT,
          maxConcurrent)
      }
      httpConnection = Some(conn)
      conn
    }

    http
      .post(path, body, retry, reschedule)
      .flatMap { response =>
        val finished = Instant.now()

        response.left
          .map {
            // Only check for memory exhaustion if there was a
            // terminal connection error.
            case error: ConnectionError =>
              isOomKilled().map {
                case true  => MemoryExhausted()
                case false => error
              }
            case other => Future.successful(other)
          }
          .fold(_.map(Left(_)), right => Future.successful(Right(right)))
          .map(res => RunResult(Interval(started, finished), res))
      }
  }

  /**
   * Obtains the container's stdout and stderr output and converts it to our own JSON format.
   * At the moment, this is done by reading the internal Docker log file for the container.
   * Said file is written by Docker's JSON log driver and has a "well-known" location and name.
   *
   * For warm containers, the container log file already holds output from
   * previous activations that have to be skipped. For this reason, a starting position
   * is kept and updated upon each invocation.
   *
   * There are two possible modes controlled by parameter waitForSentinel:
   *
   * 1. Wait for sentinel:
   *    Tail container log file until two sentinel markers show up. Complete
   *    once two sentinel markers have been identified, regardless whether more
   *    data could be read from container log file.
   *    A log file reading error is reported if sentinels cannot be found.
   *    Managed action runtimes use the the sentinels to mark the end of
   *    an individual activation.
   *
   * 2. Do not wait for sentinel:
   *    Read container log file up to its end. Stop reading once the end
   *    has been reached. Complete once two sentinel markers have been
   *    identified, regardless whether more data could be read from
   *    container log file.
   *    No log file reading error is reported if sentinels cannot be found.
   *    Blackbox actions do not necessarily produce marker sentinels properly,
   *    so this mode is used for all blackbox actions.
   *    In addition, this mode can / should be used in error situations with
   *    managed action runtimes where sentinel markers may be missing or
   *    arrive too late - Example: action exceeds time or memory limit during
   *    init or run.
   *
   * The result returned from this method does never contain any log sentinel markers. These are always
   * filtered - regardless of the specified waitForSentinel mode.
   *
   * Only parses and returns as much logs as fit in the passed log limit. Stops log collection with an error
   * if processing takes too long or time gaps between processing individual log lines are too long.
   *
   * @param limit the limit to apply to the log size
   * @param waitForSentinel determines if the processor should wait for a sentinel to appear
   * @return a vector of Strings with log lines in our own JSON format
   */
  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {
    // Define a time limit for collecting and processing the logs of a single activation.
    // If this time limit is exceeded, log processing is stopped and declared unsuccessful.
    // Calculate the timeout based on the maximum expected log size, i.e. the log limit.
    // Use a lower bound of 5 MB log size to account for base overhead.
    val logCollectingTimeout = limit.toMB.toInt.max(5) * logCollectingTimeoutPerMBLogLimit

    docker
      .rawContainerLogs(id, logFileOffset.get(), if (waitForSentinel) Some(filePollInterval) else None)
      // This stage only throws 'FramingException' so we cannot decide whether we got truncated due to a size
      // constraint (like StreamLimitReachedException below) or due to the file being truncated itself.
      .via(Framing.delimiter(delimiter, limit.toBytes.toInt))
      .limitWeighted(limit.toBytes) { obj =>
        // Adding + 1 since we know there's a newline byte being read
        val size = obj.size + 1
        logFileOffset.addAndGet(size)
        size
      }
      .via(new CompleteAfterOccurrences(_.containsSlice(DockerContainer.byteStringSentinel), 2, waitForSentinel))
      // As we're reading the logs after the activation has finished the invariant is that all loglines are already
      // written and we mostly await them being flushed by the docker daemon. Therefore we can time out based on the time
      // between two loglines appear without relying on the log frequency in the action itself.
      .idleTimeout(logCollectingIdleTimeout)
      // Apply an overall time limit for this log collecting and processing stream.
      .completionTimeout(logCollectingTimeout)
      .recover {
        case _: StreamLimitReachedException =>
          // While the stream has already ended by failing the limitWeighted stage above, we inject a truncation
          // notice downstream, which will be processed as usual. This will be the last element of the stream.
          ByteString(LogLine(Instant.now.toString, "stderr", Messages.truncateLogs(limit)).toJson.compactPrint)
        case _: OccurrencesNotFoundException | _: FramingException | _: TimeoutException =>
          // Stream has already ended and we insert a notice that data might be missing from the logs. While a
          // FramingException can also mean exceeding the limits, we cannot decide which case happened so we resort
          // to the general error message. This will be the last element of the stream.
          ByteString(LogLine(Instant.now.toString, "stderr", Messages.logFailure).toJson.compactPrint)
      }
  }

  /** Delimiter used to split log-lines as written by the json-log-driver. */
  private val delimiter = ByteString("\n")
}

/**
 * Completes the stream once the given predicate is fulfilled by N events in the stream.
 *
 * '''Emits when''' an upstream element arrives and does not fulfill the predicate
 *
 * '''Backpressures when''' downstream backpressures
 *
 * '''Completes when''' upstream completes or predicate is fulfilled N times
 *
 * '''Cancels when''' downstream cancels
 *
 * '''Errors when''' stream completes, not enough occurrences have been found and errorOnNotEnough is true
 */
class CompleteAfterOccurrences[T](isInEvent: T => Boolean, neededOccurrences: Int, errorOnNotEnough: Boolean)
    extends GraphStage[FlowShape[T, T]] {
  val in: Inlet[T] = Inlet[T]("WaitForOccurrences.in")
  val out: Outlet[T] = Outlet[T]("WaitForOccurrences.out")
  override val shape: FlowShape[T, T] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private var occurrencesFound = 0

      override def onPull(): Unit = pull(in)

      override def onPush(): Unit = {
        val element = grab(in)
        val isOccurrence = isInEvent(element)

        if (isOccurrence) occurrencesFound += 1

        if (occurrencesFound >= neededOccurrences) {
          completeStage()
        } else {
          if (isOccurrence) {
            pull(in)
          } else {
            push(out, element)
          }
        }
      }

      override def onUpstreamFinish(): Unit = {
        if (occurrencesFound >= neededOccurrences || !errorOnNotEnough) {
          completeStage()
        } else {
          failStage(OccurrencesNotFoundException(neededOccurrences, occurrencesFound))
        }
      }

      setHandlers(in, out, this)
    }
}

/** Indicates that Occurrences have not been found in the stream */
case class OccurrencesNotFoundException(neededCount: Int, actualCount: Int)
    extends RuntimeException(s"Only found $actualCount out of $neededCount occurrences.")
