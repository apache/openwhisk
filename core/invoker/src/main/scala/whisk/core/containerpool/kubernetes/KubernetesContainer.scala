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

import akka.actor.ActorSystem
import java.time.Instant
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import akka.stream.StreamLimitReachedException
import akka.stream.scaladsl.Framing.FramingException
import akka.stream.scaladsl.Source
import akka.util.ByteString
import spray.json._
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
   * @return a Future which either completes with a KubernetesContainer or a failure to create a container
   */
  def create(transid: TransactionId,
             name: String,
             image: String,
             userProvidedImage: Boolean = false,
             memory: ByteSize = 256.MB,
             environment: Map[String, String] = Map.empty,
             labels: Map[String, String] = Map.empty)(implicit kubernetes: KubernetesApi,
                                                      ec: ExecutionContext,
                                                      log: Logging): Future[KubernetesContainer] = {
    implicit val tid = transid

    val podName = name.replace("_", "-").replaceAll("[()]", "").toLowerCase()

    for {
      container <- kubernetes.run(podName, image, memory, environment, labels).recoverWith {
        case _ => Future.failed(WhiskContainerStartupError(s"Failed to run container with image '${image}'."))
      }
    } yield container
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
 * @param workerIP the ip of the workernode on which the container is executing
 * @param nativeContainerId the docker/containerd lowlevel id for the container
 */
class KubernetesContainer(protected[core] val id: ContainerId,
                          protected[core] val addr: ContainerAddress,
                          protected[core] val workerIP: String,
                          protected[core] val nativeContainerId: String)(implicit kubernetes: KubernetesApi,
                                                                         override protected val as: ActorSystem,
                                                                         protected val ec: ExecutionContext,
                                                                         protected val logging: Logging)
    extends Container {

  /** The last read timestamp in the log file */
  private val lastTimestamp = new AtomicReference[Option[Instant]](None)

  /** The last offset read in the remote log file */
  private val lastOffset = new AtomicLong(0)

  protected val waitForLogs: FiniteDuration = 2.seconds

  def suspend()(implicit transid: TransactionId): Future[Unit] = kubernetes.suspend(this)

  def resume()(implicit transid: TransactionId): Future[Unit] = kubernetes.resume(this)

  override def destroy()(implicit transid: TransactionId): Future[Unit] = {
    super.destroy()
    kubernetes.rm(this)
  }

  private val stringSentinel = DockerContainer.ActivationSentinel.utf8String

  /**
   * Request that the activation's log output be forwarded to an external log service (implicit in LogProvider choice).
   * Additional per log line metadata and the activation record is provided to be optionally included
   * in the forwarded log entry.
   *
   * @param sizeLimit The maximum number of bytes of log that should be forwardewd
   * @param sentinelledLogs Should the log forwarder expect a sentinel line at the end of stdout/stderr streams?
   * @param additionalMetadata Additional metadata that should be injected into every log line
   * @param augmentedActivation Activation record to be appended to the forwarded log.
   */
  def forwardLogs(sizeLimit: ByteSize,
                  sentinelledLogs: Boolean,
                  additionalMetadata: Map[String, JsValue],
                  augmentedActivation: JsObject)(implicit transid: TransactionId): Future[Unit] = {
    kubernetes match {
      case client: KubernetesApiWithInvokerAgent => {
        client
          .forwardLogs(this, lastOffset.get, sizeLimit, sentinelledLogs, additionalMetadata, augmentedActivation)
          .map(newOffset => lastOffset.set(newOffset))
      }
      case _ =>
        Future.failed(new UnsupportedOperationException("forwardLogs requires whisk.kubernetes.invokerAgent.enabled"))
    }
  }

  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any] = {

    kubernetes
      .logs(this, lastTimestamp.get, waitForSentinel)
      .limitWeighted(limit.toBytes) { obj =>
        // Adding + 1 since we know there's a newline byte being read
        obj.jsonSize.toLong + 1
      }
      .map { line =>
        lastTimestamp.set(Option(line.time))
        line
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
      .map { _.toByteString }
  }
}
