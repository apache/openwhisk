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

package whisk.core.containerpool.docker.test

import org.scalatest.FlatSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers
import org.scalamock.scalatest.MockFactory
import scala.concurrent.ExecutionContext.Implicits.global
import common.StreamLogging
import whisk.common.TransactionId
import whisk.core.containerpool.docker.RuncApi
import whisk.core.containerpool.docker.DockerApi
import whisk.core.containerpool.docker.DockerContainer
import whisk.core.containerpool.docker.ContainerId
import whisk.core.containerpool.docker.ContainerIp
import spray.json.JsObject
import scala.concurrent.Future
import whisk.core.container.RunResult
import whisk.core.entity.ActivationResponse.ContainerResponse
import whisk.core.container.Interval
import java.time.Instant
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import whisk.common.LoggingMarkers._
import whisk.common.LogMarker
import whisk.core.containerpool.InitializationError
import whisk.core.entity.ActivationResponse
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.Await
import whisk.core.entity.ActivationResponse.Timeout

/**
 * Unit tests for ContainerPool schedule
 */
@RunWith(classOf[JUnitRunner])
class DockerContainerTests extends FlatSpec
    with Matchers
    with MockFactory
    with StreamLogging
    with ScalaFutures
    with BeforeAndAfterEach {

    override def beforeEach() = {
        stream.reset()
    }

    /** Awaits the given future, throws the exception enclosed in Failure. */
    def await[A](f: Future[A], timeout: FiniteDuration = 100.millisecond) = Await.result[A](f, timeout)

    def dockerContainer(id: ContainerId = ContainerId("id"), ip: ContainerIp = ContainerIp("ip"))(ccRes: Future[RunResult])(
        implicit docker: DockerApi, runc: RuncApi): DockerContainer = {

        new DockerContainer(id, ip) {
            override protected def callContainer(path: String, body: JsObject, timeout: FiniteDuration, retry: Boolean = false): Future[RunResult] = {
                ccRes
            }
        }
    }

    def intervalOf(duration: FiniteDuration) = Interval(Instant.EPOCH, Instant.ofEpochMilli(duration.toMillis))

    behavior of "DockerContainer"

    implicit val transid = TransactionId.testing

    ignore should "create a new instance" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val container = DockerContainer.create(transid = TransactionId.testing, image = "image")
    }

    it should "halt and resume container via runc" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val id = ContainerId("id")
        val container = new DockerContainer(id, ContainerIp("ip"))

        container.halt()
        container.resume()

        (runc.pause(_: ContainerId)(_: TransactionId)).verify(id, transid)
        (runc.resume(_: ContainerId)(_: TransactionId)).verify(id, transid)
    }

    it should "destroy a container via Docker" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val id = ContainerId("id")
        val container = new DockerContainer(id, ContainerIp("ip"))

        container.destroy()

        (docker.rm(_: ContainerId)(_: TransactionId)).verify(id, transid)
    }

    it should "initialize a container" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val interval = intervalOf(1.millisecond)
        val container = dockerContainer() {
            Future.successful(RunResult(interval, Right(ContainerResponse(true, "", None))))
        }

        val initInterval = container.initialize(Some(JsObject()), 1.second)
        initInterval.futureValue shouldBe interval

        // assert the starting log is there
        val start = LogMarker.parse(logLines.head)
        start.token shouldBe INVOKER_ACTIVATION_INIT

        // assert the end log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
        end.deltaToMarkerStart shouldBe Some(interval.duration.toMillis)
    }

    it should "properly deal with terminal initialization failures" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val container = dockerContainer() {
            Future.failed(new RuntimeException())
        }

        val init = container.initialize(Some(JsObject()), 1.second)

        val error = the[InitializationError] thrownBy await(init)
        error.interval.duration shouldBe Duration.Zero
        error.response.statusCode shouldBe ActivationResponse.WhiskError

        // assert the error log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_INIT.asError
    }

    it should "properly deal with a timeout during initialization" in {
        implicit val docker = stub[DockerApi]
        implicit val runc = stub[RuncApi]

        val initTimeout = 1.second
        val interval = intervalOf(initTimeout + 1.nanoseconds)

        val container = dockerContainer() {
            Future.successful(RunResult(interval, Left(Timeout())))
        }

        val init = container.initialize(Some(JsObject()), initTimeout)

        val error = the[InitializationError] thrownBy await(init)
        error.interval shouldBe interval
        error.response.statusCode shouldBe ActivationResponse.ApplicationError

        // assert the finish log is there
        val end = LogMarker.parse(logLines.last)
        end.token shouldBe INVOKER_ACTIVATION_INIT.asFinish
    }
}

