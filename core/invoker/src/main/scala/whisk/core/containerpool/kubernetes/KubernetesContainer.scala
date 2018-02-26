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

package whisk.core.containerpool.kubernetes

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

import akka.stream.StreamLimitReachedException
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.containerpool.Container
import whisk.core.containerpool.WhiskContainerStartupError
import whisk.core.containerpool.ContainerId
import whisk.core.containerpool.ContainerAddress
import whisk.core.containerpool.docker.{CompleteAfterOccurrences, DockerContainer, OccurrencesNotFoundException}
import whisk.core.entity.ByteSize
import whisk.core.entity.size._
import whisk.http.Messages

object KubernetesContainer {

  /**
   * Creates a container running in kubernetes
   *
   * @param transid transaction creating the container
   * @param image image to create the container from
   * @param userProvidedImage whether the image is provided by the user
   *     or is an OpenWhisk provided image
   * @param labels labels to set on the container
   * @param name optional name for the container
   * @return a Future which either completes with a KubernetesContainer or one of two specific failures
   */
  def create(transid: TransactionId,
             name: String,
             image: String,
             userProvidedImage: Boolean = false,
             memory: ByteSize = 256.MB,
             environment: Map[String, String] = Map(),
             labels: Map[String, String] = Map())(implicit kubernetes: KubernetesApi,
                                                  ec: ExecutionContext,
                                                  log: Logging): Future[KubernetesContainer] = {
    implicit val tid = transid

    val podName = name.replace("_", "-").replaceAll("[()]", "").toLowerCase()

    val environmentArgs = environment.flatMap {
      case (key, value) => Seq("--env", s"$key=$value")
    }.toSeq

    val labelArgs = labels.map {
      case (key, value) => s"$key=$value"
    } match {
      case Seq() => Seq()
      case pairs => Seq("-l") ++ pairs
    }

    val args = Seq("--generator", "run-pod/v1", "--restart", "Always", "--limits", s"memory=${memory.toMB}Mi") ++ environmentArgs ++ labelArgs

    for {
      id <- kubernetes.run(podName, image, args).recoverWith {
        case _ => Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
      }
      ip <- kubernetes.inspectIPAddress(id).recoverWith {
        // remove the container immediately if inspect failed as
        // we cannot recover that case automatically
        case _ =>
          kubernetes.rm(id)
          Future.failed(WhiskContainerStartupError(Messages.resourceProvisionError))
      }
    } yield new KubernetesContainer(id, ip)
  }

}

/**
 * Represents a container as run by kubernetes.
 *
 * This class contains OpenWhisk specific behavior and as such does not necessarily
 * use kubernetes commands to achieve the effects needed.
 *
 * @constructor
 * @param id the id of the container
 * @param addr the ip & port of the container
 */
class KubernetesContainer(protected val id: ContainerId, protected val addr: ContainerAddress)(
  implicit kubernetes: KubernetesApi,
  protected val ec: ExecutionContext,
  protected val logging: Logging)
    extends Container {

  /** The last read timestamp in the log file */
  private val lastTimestamp = new AtomicReference[Option[Instant]](None)

  protected val waitForLogs: FiniteDuration = 2.seconds

  // no-op under Kubernetes
  def suspend()(implicit transid: TransactionId): Future[Unit] = Future.successful({})

  // no-op under Kubernetes
  def resume()(implicit transid: TransactionId): Future[Unit] = Future.successful({})

  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    kubernetes.rm(id)
  }

  private val stringSentinel = DockerContainer.ActivationSentinel.utf8String

  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {

    kubernetes
      .logs(id, lastTimestamp.get, waitForSentinel)
      .limitWeighted(limit.toBytes) { obj =>
        // Adding + 1 since we know there's a newline byte being read
        obj.jsonSize.toLong + 1
      }
      .via(new CompleteAfterOccurrences(_.log == stringSentinel, 2, waitForSentinel))
      .recover {
        case _: StreamLimitReachedException =>
          // While the stream has already ended by failing the limitWeighted stage above, we inject a truncation
          // notice downstream, which will be processed as usual. This will be the last element of the stream.
          TypedLogLine(Instant.now, "stderr", Messages.truncateLogs(limit))
        case _: OccurrencesNotFoundException | _: FramingException =>
          // Stream has already ended and we insert a notice that data might be missing from the logs. While a
          // FramingException can also mean exceeding the limits, we cannot decide which case happened so we resort
          // to the general error message. This will be the last element of the stream.
          TypedLogLine(Instant.now, "stderr", Messages.logFailure)
      }
      .takeWithin(waitForLogs)
      .map { line =>
        lastTimestamp.set(Some(line.time))
        line.toByteString
      }
  }

}
