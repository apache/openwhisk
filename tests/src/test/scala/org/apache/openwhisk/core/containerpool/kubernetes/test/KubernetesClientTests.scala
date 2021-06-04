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

package org.apache.openwhisk.core.containerpool.kubernetes.test

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Concat, Sink, Source}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalatest.time.{Seconds, Span}
import common.{StreamLogging, WskActorSystem}
import okio.Buffer
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.containerpool.{ContainerAddress, ContainerId}
import org.apache.openwhisk.core.containerpool.kubernetes._
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size._
import org.apache.openwhisk.core.containerpool.Container.ACTIVATION_LOG_SENTINEL

import scala.collection.mutable
import scala.collection.immutable.Queue

@RunWith(classOf[JUnitRunner])
class KubernetesClientTests
    extends FlatSpec
    with Matchers
    with StreamLogging
    with BeforeAndAfterEach
    with Eventually
    with WskActorSystem {

  import KubernetesClientTests._

  val commandTimeout = 500.milliseconds
  def await[A](f: Future[A], timeout: FiniteDuration = commandTimeout) = Await.result(f, timeout)

  /** Reads logs into memory and awaits them */
  def awaitLogs(source: Source[TypedLogLine, Any], timeout: FiniteDuration = 1000.milliseconds): Vector[TypedLogLine] =
    Await.result(source.runWith(Sink.seq[TypedLogLine]), timeout).toVector

  override def beforeEach = stream.reset()

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)))

  implicit val transid = TransactionId.testing
  val id = ContainerId("55db56ee082239428b27d3728b4dd324c09068458aad9825727d5bfc1bba6d52")
  val container = kubernetesContainer(id)

  /** Returns a KubernetesClient with a mocked result for 'executeProcess' */
  def kubernetesClient(fixture: => Future[String]) = {
    new KubernetesClient()(executionContext) {
      override def executeProcess(args: Seq[String], timeout: Duration)(implicit ec: ExecutionContext,
                                                                        as: ActorSystem) =
        fixture
    }
  }

  def kubernetesContainer(id: ContainerId) =
    new KubernetesContainer(id, ContainerAddress("ip"), "ip", "docker://" + id.asString)(kubernetesClient {
      Future.successful("")
    }, actorSystem, executionContext, logging)

  behavior of "KubernetesClient"

  val firstLog = s"""2018-02-06T00:00:18.419889342Z first activation
                   |2018-02-06T00:00:18.419929471Z $ACTIVATION_LOG_SENTINEL
                   |2018-02-06T00:00:18.419988733Z $ACTIVATION_LOG_SENTINEL
                   |""".stripMargin
  val secondLog = s"""2018-02-06T00:09:35.38267193Z second activation
                    |2018-02-06T00:09:35.382990278Z $ACTIVATION_LOG_SENTINEL
                    |2018-02-06T00:09:35.383116503Z $ACTIVATION_LOG_SENTINEL
                    |""".stripMargin

  def firstSource(lastTimestamp: Option[Instant] = None): Source[TypedLogLine, Any] =
    Source(
      KubernetesRestLogSourceStage
        .readLines(new Buffer().writeUtf8(firstLog), lastTimestamp, Queue.empty))

  def secondSource(lastTimestamp: Option[Instant] = None): Source[TypedLogLine, Any] =
    Source(
      KubernetesRestLogSourceStage
        .readLines(new Buffer().writeUtf8(secondLog), lastTimestamp, Queue.empty))

  it should "forward suspend commands to the client" in {
    implicit val kubernetes = new TestKubernetesClient
    val id = ContainerId("id")
    val container = new KubernetesContainer(id, ContainerAddress("ip"), "127.0.0.1", "docker://foo")
    await(container.suspend())
    kubernetes.suspends should have size 1
    kubernetes.suspends(0) shouldBe id
  }

  it should "forward resume commands to the client" in {
    implicit val kubernetes = new TestKubernetesClient
    val id = ContainerId("id")
    val container = new KubernetesContainer(id, ContainerAddress("ip"), "127.0.0.1", "docker://foo")
    await(container.resume())
    kubernetes.resumes should have size 1
    kubernetes.resumes(0) shouldBe id
  }

  it should "return all logs when no sinceTime passed" in {
    val client = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        firstSource()
      }
    }
    val logs = awaitLogs(client.logs(container, None))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:00:18.419889342Z", "stdout", "first activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:00:18.419988733Z", "stdout", ACTIVATION_LOG_SENTINEL)
  }

  it should "return all logs after the one matching sinceTime" in {

    val testDate: Option[Instant] = "2018-02-06T00:00:18.419988733Z"
    val client = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        Source.combine(firstSource(testDate), secondSource(testDate))(Concat(_))
      }
    }
    val logs = awaitLogs(client.logs(container, testDate))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:09:35.38267193Z", "stdout", "second activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:09:35.383116503Z", "stdout", ACTIVATION_LOG_SENTINEL)
  }

  it should "return all logs if none match sinceTime" in {
    val testDate: Option[Instant] = "2018-02-06T00:00:18.419988733Z"
    val client = new TestKubernetesClient {
      override def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean)(
        implicit transid: TransactionId): Source[TypedLogLine, Any] = {
        secondSource(testDate)
      }
    }
    val logs = awaitLogs(client.logs(container, testDate))
    logs should have size 3
    logs(0) shouldBe TypedLogLine("2018-02-06T00:09:35.38267193Z", "stdout", "second activation")
    logs(2) shouldBe TypedLogLine("2018-02-06T00:09:35.383116503Z", "stdout", ACTIVATION_LOG_SENTINEL)
  }

}

object KubernetesClientTests {
  import scala.language.implicitConversions
  import scala.concurrent.ExecutionContext.Implicits.global

  implicit def strToDate(str: String): Option[Instant] =
    KubernetesClient.parseK8STimestamp(str).toOption

  implicit def strToInstant(str: String): Instant =
    strToDate(str).get

  class TestKubernetesClient(implicit as: ActorSystem) extends KubernetesApi with StreamLogging {
    var runs = mutable.Buffer.empty[(String, String, Map[String, String], Map[String, String])]
    var rms = mutable.Buffer.empty[ContainerId]
    var rmByLabels = mutable.Buffer.empty[(String, String)]
    var resumes = mutable.Buffer.empty[ContainerId]
    var suspends = mutable.Buffer.empty[ContainerId]
    var logCalls = mutable.Buffer.empty[(ContainerId, Option[Instant])]

    def run(name: String,
            image: String,
            memory: ByteSize = 256.MB,
            env: Map[String, String] = Map.empty,
            labels: Map[String, String] = Map.empty)(implicit transid: TransactionId): Future[KubernetesContainer] = {
      runs += ((name, image, env, labels))
      implicit val kubernetes = this
      val containerId = ContainerId("id")
      val addr: ContainerAddress = ContainerAddress("ip")
      val workerIP: String = "127.0.0.1"
      val nativeContainerId: String = "docker://" + containerId.asString
      Future.successful(new KubernetesContainer(containerId, addr, workerIP, nativeContainerId))
    }

    def rm(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
      rms += container.id
      Future.successful(())
    }

    override def rm(podName: String)(implicit transid: TransactionId): Future[Unit] = {
      rms += ContainerId(podName)
      Future.successful(())
    }
    def rm(labels: Map[String, String], ensureUnpause: Boolean = false)(
      implicit transid: TransactionId): Future[Unit] = {
      labels.foreach { label =>
        rmByLabels += ((label._1, label._2))
      }
      Future.successful(())
    }

    def resume(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
      resumes += (container.id)
      Future.successful({})
    }

    def suspend(container: KubernetesContainer)(implicit transid: TransactionId): Future[Unit] = {
      suspends += (container.id)
      Future.successful({})
    }

    def logs(container: KubernetesContainer, sinceTime: Option[Instant], waitForSentinel: Boolean = false)(
      implicit transid: TransactionId): Source[TypedLogLine, Any] = {
      logCalls += ((container.id, sinceTime))
      Source(List.empty[TypedLogLine])
    }

    override def addLabel(container: KubernetesContainer, labels: Map[String, String]): Future[Unit] = {
      Future.successful({})
    }
  }
}
