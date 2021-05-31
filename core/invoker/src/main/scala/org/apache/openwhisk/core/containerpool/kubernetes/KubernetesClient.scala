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

package org.apache.openwhisk.core.containerpool.kubernetes

import java.io.IOException
import java.net.SocketTimeoutException
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.{Instant, ZoneId}

import akka.actor.ActorSystem
import akka.event.Logging.ErrorLevel
import akka.event.Logging.InfoLevel
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.pattern.after
import akka.stream.scaladsl.Source
import akka.stream.stage._
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import akka.util.ByteString

import collection.JavaConverters._
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.utils.Serialization
import io.fabric8.kubernetes.client.{ConfigBuilder, DefaultKubernetesClient}
import okhttp3.{Call, Callback, Request, Response}
import okio.BufferedSource
import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.openwhisk.common.LoggingMarkers
import org.apache.openwhisk.common.{ConfigMapValue, Logging, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.docker.ProcessRunner
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size._
import pureconfig._
import pureconfig.generic.auto._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Configuration for kubernetes client command timeouts.
 */
case class KubernetesClientTimeoutConfig(run: FiniteDuration, logs: FiniteDuration)

/**
 * Configuration for kubernetes cpu resource request/limit scaling based on action memory limit
 */
case class KubernetesCpuScalingConfig(millicpus: Int, memory: ByteSize, maxMillicpus: Int)

/**
 * Configuration for kubernetes ephemeral storage limit for the action container
 */
case class KubernetesEphemeralStorageConfig(limit: ByteSize)

/**
 * Exception to indicate a pod took too long to become ready.
 */
case class KubernetesPodReadyTimeoutException(timeout: FiniteDuration)
    extends Exception(s"Pod readiness timed out after ${timeout.toSeconds}s")

/**
 * Exception to indicate a pod could not be created at the apiserver.
 */
case class KubernetesPodApiException(e: Throwable) extends Exception(s"Pod was not created at apiserver: ${e}", e)

/**
 * Configuration for node affinity for the pods that execute user action containers
 * The key,value pair should match the <key,value> pair with which the invoker worker nodes
 * are labeled in the Kubernetes cluster.  The default pair is <openwhisk-role,invoker>,
 * but a deployment may override this default if needed.
 */
case class KubernetesInvokerNodeAffinity(enabled: Boolean, key: String, value: String)

/**
 * General configuration for kubernetes client
 */
case class KubernetesClientConfig(timeouts: KubernetesClientTimeoutConfig,
                                  userPodNodeAffinity: KubernetesInvokerNodeAffinity,
                                  portForwardingEnabled: Boolean,
                                  actionNamespace: Option[String],
                                  podTemplate: Option[ConfigMapValue],
                                  cpuScaling: Option[KubernetesCpuScalingConfig],
                                  pdbEnabled: Boolean,
                                  fieldRefEnvironment: Option[Map[String, String]],
                                  ephemeralStorage: Option[KubernetesEphemeralStorageConfig])

/**
 * Serves as an interface to the Kubernetes API by proxying its REST API and/or invoking the kubectl CLI.
 *
 * Be cautious with the ExecutionContext passed to this, as many
 * operations are blocking.
 *
 * You only need one instance (and you shouldn't get more).
 */
class KubernetesClient(
  config: KubernetesClientConfig = loadConfigOrThrow[KubernetesClientConfig](ConfigKeys.kubernetes),
  testClient: Option[DefaultKubernetesClient] = None)(executionContext: ExecutionContext)(implicit log: Logging,
                                                                                          as: ActorSystem)
    extends KubernetesApi
    with ProcessRunner {
  implicit protected val ec = executionContext
  implicit protected val am = ActorMaterializer()
  implicit protected val scheduler = as.scheduler
  implicit protected val kubeRestClient = testClient.getOrElse {
    val configBuilder = new ConfigBuilder()
      .withConnectionTimeout(config.timeouts.logs.toMillis.toInt)
      .withRequestTimeout(config.timeouts.logs.toMillis.toInt)
    config.actionNamespace.foreach(configBuilder.withNamespace)
    new DefaultKubernetesClient(configBuilder.build())
  }

  private val podBuilder = new WhiskPodBuilder(kubeRestClient, config)

  def run(name: String,
          image: String,
          memory: ByteSize = 256.MB,
          environment: Map[String, String] = Map.empty,
          labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {

    val (pod, pdb) = podBuilder.buildPodSpec(name, image, memory, environment, labels, config)
    if (transid.meta.extraLogging) {
      log.info(this, s"Pod spec being created\n${Serialization.asYaml(pod)}")
    }
    val namespace = kubeRestClient.getNamespace
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_KUBEAPI_CMD("create"),
      s"launching pod $name (image:$image, mem: ${memory.toMB}) (timeout: ${config.timeouts.run.toSeconds}s)",
      logLevel = akka.event.Logging.InfoLevel)

    //create the pod; catch any failure to end the transaction timer
    Try {
      val created = kubeRestClient.pods.inNamespace(namespace).create(pod)
      pdb.map(
        p =>
          kubeRestClient.policy.podDisruptionBudget
            .inNamespace(namespace)
            .withName(name)
            .create(p))
      created
    } match {
      case Failure(e) =>
        //call to api-server failed
        val stackTrace = ExceptionUtils.getStackTrace(e)
        transid.failed(
          this,
          start,
          s"Failed create pod for '$name': ${e.getClass} (Caused by: ${e.getCause}) - ${e.getMessage}; stacktrace: $stackTrace",
          ErrorLevel)
        Future.failed(KubernetesPodApiException(e))
      case Success(createdPod) => {
        //call to api-server succeeded; wait for the pod to become ready; catch any failure to end the transaction timer
        waitForPod(namespace, createdPod, start.start, config.timeouts.run)
          .map { readyPod =>
            transid.finished(this, start, logLevel = InfoLevel)
            toContainer(readyPod)
          }
          .recoverWith {
            case e =>
              transid.failed(this, start, s"Failed create pod for '$name': ${e.getClass} - ${e.getMessage}", ErrorLevel)
              //log pod events to diagnose pod readiness failures
              val podEvents = kubeRestClient.events
                .inNamespace(namespace)
                .withField("involvedObject.name", name)
                .list()
                .getItems
                .asScala
              if (podEvents.isEmpty) {
                log.info(this, s"No pod events for failed pod '$name'")
              } else {
                podEvents.foreach { podEvent =>
                  log.info(
                    this,
                    s"Pod event for failed pod '$name' ${podEvent.getLastTimestamp}: ${podEvent.getMessage}")
                }
              }
              Future.failed(e)
          }
      }
    }
  }

  def rm(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
    deleteByName(container.id.asString)
  }

  def rm(podName: String)(implicit transid: TransactionId): Future[Unit] = {
    deleteByName(podName)
  }

  def rm(labels: Map[String, String], ensureUnpaused: Boolean = false)(
    implicit transid: TransactionId): Future[Unit] = {
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_KUBEAPI_CMD("delete"),
      s"Deleting pods with label $labels",
      logLevel = akka.event.Logging.InfoLevel)
    Future {
      blocking {
        kubeRestClient
          .inNamespace(kubeRestClient.getNamespace)
          .pods()
          .withLabels(labels.asJava)
          .delete()
        if (config.pdbEnabled) {
          kubeRestClient.policy.podDisruptionBudget
            .inNamespace(kubeRestClient.getNamespace)
            .withLabels(labels.asJava)
            .delete()
        }
      }
    }.map(_ => transid.finished(this, start, logLevel = InfoLevel))
      .recover {
        case e =>
          transid.failed(
            this,
            start,
            s"Failed delete pods with label $labels: ${e.getClass} - ${e.getMessage}",
            ErrorLevel)
      }
  }

  private def deleteByName(podName: String)(implicit transid: TransactionId) = {
    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_KUBEAPI_CMD("delete"),
      s"Deleting pod ${podName}",
      logLevel = akka.event.Logging.InfoLevel)
    Future {
      blocking {
        kubeRestClient
          .inNamespace(kubeRestClient.getNamespace)
          .pods()
          .withName(podName)
          .delete()
        if (config.pdbEnabled) {
          kubeRestClient.policy.podDisruptionBudget
            .inNamespace(kubeRestClient.getNamespace)
            .withName(podName)
            .delete()
        }
      }
    }.map(_ => transid.finished(this, start, logLevel = InfoLevel))
      .recover {
        case e =>
          transid.failed(
            this,
            start,
            s"Failed delete pod for '${podName}': ${e.getClass} - ${e.getMessage}",
            ErrorLevel)
      }
  }

  // suspend is a no-op with the basic KubernetesClient
  def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = Future.successful({})

  // resume is a no-op with the basic KubernetesClient
  def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = Future.successful({})

  def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
    implicit transid: TransactionId): Source[TypedLogLine, Any] = {

    log.debug(this, "Parsing logs from Kubernetes Graph Stage…")

    Source
      .fromGraph(new KubernetesRestLogSourceStage(container.id, sinceTime, waitForSentinel))
      .log("kubernetesLogs")

  }

  protected def toContainer(pod: Pod): KubernetesContainer = {
    val id = ContainerId(pod.getMetadata.getName)

    val portFwd = if (config.portForwardingEnabled) {
      Some(kubeRestClient.pods().withName(pod.getMetadata.getName).portForward(8080))
    } else None

    val addr = portFwd
      .map(fwd => ContainerAddress("localhost", fwd.getLocalPort))
      .getOrElse(ContainerAddress(pod.getStatus.getPodIP))
    val workerIP = pod.getStatus.getHostIP
    // Extract the native (docker or containerd) containerId for the container
    // By convention, kubernetes adds a docker:// prefix when using docker as the low-level container engine
    val nativeContainerId = pod.getStatus.getContainerStatuses.get(0).getContainerID.stripPrefix("docker://")
    implicit val kubernetes = this
    new KubernetesContainer(id, addr, workerIP, nativeContainerId, portFwd)
  }

  // check for ready status every 1 second until timeout (minus the start time, which is the time for the pod create call) has past
  private def waitForPod(namespace: String,
                         pod: Pod,
                         start: Instant,
                         timeout: FiniteDuration,
                         deadlineOpt: Option[Deadline] = None): Future[Pod] = {
    val readyPod = kubeRestClient
      .pods()
      .inNamespace(namespace)
      .withName(pod.getMetadata.getName)
    val deadline = deadlineOpt.getOrElse((timeout - (System.currentTimeMillis() - start.toEpochMilli).millis).fromNow)
    if (!readyPod.isReady) {
      if (deadline.isOverdue()) {
        Future.failed(KubernetesPodReadyTimeoutException(timeout))
      } else {
        after(1.seconds, scheduler) {
          waitForPod(namespace, pod, start, timeout, Some(deadline))
        }
      }
    } else {
      Future.successful(readyPod.get())
    }
  }

  def addLabel(container: KubernetesContainer, labels: Map[String, String]): Future[Unit] =
    try {
      kubeRestClient
        .pods()
        .withName(container.id.asString)
        .edit()
        .editMetadata()
        .addToLabels(labels.asJava)
        .endMetadata()
        .done()
      Future.successful({})
    } catch {
      case e: Throwable => Future.failed(e)
    }
}
object KubernetesClient {

  // Necessary, as Kubernetes uses nanosecond precision in logs, but java.time.Instant toString uses milliseconds
  //%Y-%m-%dT%H:%M:%S.%N%z
  val K8STimestampFormat = new DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .appendPattern("u-MM-dd")
    .appendLiteral('T')
    .appendPattern("HH:mm:ss")
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
    .appendLiteral('Z')
    .toFormatter()
    .withZone(ZoneId.of("UTC"))

  def parseK8STimestamp(ts: String): Try[Instant] =
    Try(Instant.from(K8STimestampFormat.parse(ts)))

  def formatK8STimestamp(ts: Instant): Try[String] =
    Try(K8STimestampFormat.format(ts))
}

trait KubernetesApi {

  def run(name: String,
          image: String,
          memory: ByteSize,
          environment: Map[String, String] = Map.empty,
          labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer]

  def rm(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]
  def rm(podName: String)(implicit transid: TransactionId): Future[Unit]
  def rm(labels: Map[String, String], ensureUnpaused: Boolean)(implicit transid: TransactionId): Future[Unit]

  def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]

  def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit]

  def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
    implicit transid: TransactionId): Source[TypedLogLine, Any]

  def addLabel(container: KubernetesContainer, labels: Map[String, String]): Future[Unit]
}

object KubernetesRestLogSourceStage {

  import KubernetesClient.{formatK8STimestamp, parseK8STimestamp}

  val retryDelay = 100.milliseconds

  val actionContainerName = "user-action"

  sealed trait K8SRestLogTimingEvent

  case object K8SRestLogRetry extends K8SRestLogTimingEvent

  def constructPath(namespace: String, containerId: String): Path =
    Path / "api" / "v1" / "namespaces" / namespace / "pods" / containerId / "log"

  def constructQuery(sinceTime: Option[Instant], waitForSentinel: Boolean): Query = {

    val sinceTimestamp = sinceTime.flatMap(time => formatK8STimestamp(time).toOption)

    Query(Map("timestamps" -> "true", "container" -> actionContainerName) ++ sinceTimestamp.map(time =>
      "sinceTime" -> time))

  }

  @tailrec
  def readLines(src: BufferedSource,
                lastTimestamp: Option[Instant],
                lines: Queue[TypedLogLine] = Queue.empty[TypedLogLine]): Queue[TypedLogLine] = {
    if (!src.exhausted()) {
      (for {
        line <- Option(src.readUtf8Line()) if !line.isEmpty
        timestampDelimiter = line.indexOf(" ")
        // Kubernetes is ignoring nanoseconds in sinceTime, so we have to filter additionally here
        rawTimestamp = line.substring(0, timestampDelimiter)
        timestamp <- parseK8STimestamp(rawTimestamp).toOption if isRelevantLogLine(lastTimestamp, timestamp)
        msg = line.substring(timestampDelimiter + 1)
        stream = "stdout" // TODO - when we can distinguish stderr: https://github.com/kubernetes/kubernetes/issues/28167
      } yield {
        TypedLogLine(timestamp, stream, msg)
      }) match {
        case Some(logLine) =>
          readLines(src, lastTimestamp, lines :+ logLine)
        case None =>
          // we may have skipped a line for filtering conditions only; keep going
          readLines(src, lastTimestamp, lines)
      }
    } else {
      lines
    }

  }

  def isRelevantLogLine(lastTimestamp: Option[Instant], newTimestamp: Instant): Boolean =
    lastTimestamp match {
      case Some(last) =>
        newTimestamp.isAfter(last)
      case None =>
        true
    }

}

final class KubernetesRestLogSourceStage(id: ContainerId, sinceTime: Option[Instant], waitForSentinel: Boolean)(
  implicit val kubeRestClient: DefaultKubernetesClient)
    extends GraphStage[SourceShape[TypedLogLine]] { stage =>

  import KubernetesRestLogSourceStage._

  val out = Outlet[TypedLogLine]("K8SHttpLogging.out")

  override val shape: SourceShape[TypedLogLine] = SourceShape.of(out)

  override protected def initialAttributes: Attributes = Attributes.name("KubernetesHttpLogSource")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogicWithLogging(shape) { logic =>

      private val queue = mutable.Queue.empty[TypedLogLine]
      private var lastTimestamp = sinceTime

      def fetchLogs(): Unit =
        try {
          val path = constructPath(kubeRestClient.getNamespace, id.asString)
          val query = constructQuery(lastTimestamp, waitForSentinel)

          log.debug("*** Fetching K8S HTTP Logs w/ Path: {} Query: {}", path, query)

          val url = Uri(kubeRestClient.getMasterUrl.toString)
            .withPath(path)
            .withQuery(query)

          val request = new Request.Builder().get().url(url.toString).build

          kubeRestClient.getHttpClient.newCall(request).enqueue(new LogFetchCallback())
        } catch {
          case NonFatal(e) =>
            onFailure(e)
            throw e
        }

      def onFailure(e: Throwable): Unit = e match {
        case _: SocketTimeoutException =>
          log.warning("* Logging socket to Kubernetes timed out.") // this should only happen with follow behavior
        case _ =>
          log.error(e, "* Retrieving the logs from Kubernetes failed.")
      }

      val emitCallback: AsyncCallback[Seq[TypedLogLine]] = getAsyncCallback[Seq[TypedLogLine]] {
        case lines @ firstLine +: restOfLines =>
          if (isAvailable(out)) {
            log.debug("* Lines Available & output ready; pushing {} (remaining: {})", firstLine, restOfLines)
            pushLine(firstLine)
            queue ++= restOfLines
          } else {
            log.debug("* Output isn't ready; queueing lines: {}", lines)
            queue ++= lines
          }
        case Nil =>
          log.debug("* Empty lines returned.")
          retryLogs()
      }

      class LogFetchCallback extends Callback {

        override def onFailure(call: Call, e: IOException): Unit = logic.onFailure(e)

        override def onResponse(call: Call, response: Response): Unit =
          try {
            val lines = readLines(response.body.source, lastTimestamp)

            log.debug("* Read & decoded lines for K8S HTTP: {}", lines)

            response.body.source.close()

            lines.lastOption.foreach { line =>
              log.debug("* Updating lastTimestamp (sinceTime) to {}", Option(line.time))
              lastTimestamp = Option(line.time)
            }

            emitCallback.invoke(lines)
          } catch {
            case NonFatal(e) =>
              log.error(e, "* Reading Kubernetes HTTP Response failed.")
              logic.onFailure(e)
              throw e
          }
      }

      def pushLine(line: TypedLogLine): Unit = {
        log.debug("* Pushing a chunk of kubernetes logging: {}", line)
        push(out, line)
      }

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            // if we still have lines queued up, return those; else make a new HTTP read.
            if (queue.nonEmpty) {
              log.debug("* onPull, nonEmpty queue... pushing line")
              pushLine(queue.dequeue())
            } else {
              log.debug("* onPull, empty queue... fetching logs")
              fetchLogs()
            }
          }
        })

      def retryLogs(): Unit = {
        // Pause before retrying so we don't thrash Kubernetes w/ HTTP requests
        log.debug("* Scheduling a retry of log fetch in {}", retryDelay)
        scheduleOnce(K8SRestLogRetry, retryDelay)
      }

      override protected def onTimer(timerKey: Any): Unit = timerKey match {
        case K8SRestLogRetry =>
          log.debug("* Timer trigger for log fetch retry")
          fetchLogs()
        case x =>
          log.warning("* Got a timer trigger with an unknown key: {}", x)
      }
    }
}

protected[core] final case class TypedLogLine(time: Instant, stream: String, log: String) {
  import KubernetesClient.formatK8STimestamp

  lazy val toJson: JsObject =
    JsObject("time" -> formatK8STimestamp(time).getOrElse("").toJson, "stream" -> stream.toJson, "log" -> log.toJson)

  lazy val jsonPrinted: String = toJson.compactPrint
  lazy val jsonSize: Int = jsonPrinted.length

  /**
   * Returns a ByteString representation of the json for this Log Line
   */
  val toByteString = ByteString(jsonPrinted)

  override def toString = s"${formatK8STimestamp(time).get} $stream: ${log.trim}"
}

protected[core] object TypedLogLine {

  import KubernetesClient.{parseK8STimestamp, K8STimestampFormat}

  def readInstant(json: JsValue): Instant = json match {
    case JsString(str) =>
      parseK8STimestamp(str) match {
        case Success(time) =>
          time
        case Failure(e) =>
          deserializationError(
            s"Could not parse a java.time.Instant from $str (Expected in format: $K8STimestampFormat: $e")
      }
    case _ =>
      deserializationError(s"Could not parse a java.time.Instant from $json (Expected in format: $K8STimestampFormat)")
  }

  implicit val typedLogLineFormat = new RootJsonFormat[TypedLogLine] {
    override def write(obj: TypedLogLine): JsValue = obj.toJson

    override def read(json: JsValue): TypedLogLine = {
      val obj = json.asJsObject
      val fields = obj.fields
      TypedLogLine(readInstant(fields("time")), fields("stream").convertTo[String], fields("log").convertTo[String])
    }
  }

}
