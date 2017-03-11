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

package whisk.core.containerpool

import scala.concurrent.Future
import spray.json.JsObject
import whisk.common.TransactionId
import scala.concurrent.duration.FiniteDuration
import whisk.core.entity.ByteSize
import whisk.core.container.Interval
import whisk.core.entity.ActivationResponse

trait Container {
    /**
     * Stops the container from consuming CPU cycles.
     */
    def halt()(implicit transid: TransactionId): Future[Unit]

    /**
     * Dual of pause.
     */
    def resume()(implicit transid: TransactionId): Future[Unit]

    /**
     * Completely destroys this instance of the container.
     */
    def destroy()(implicit transid: TransactionId): Future[Unit]

    /**
     * Initializes code in the container.
     */
    def initialize(initializer: Option[JsObject], timeout: FiniteDuration)(implicit transid: TransactionId): Future[Interval]

    /**
     * Runs code in the container.
     */
    def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration)(implicit transid: TransactionId): Future[(Interval, ActivationResponse)]

    def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Future[List[String]]
}

sealed abstract class ContainerError(msg: String) extends Exception(msg)
sealed abstract class ContainerStartupError(msg: String) extends ContainerError(msg)
case class WhiskContainerStartupError(msg: String) extends ContainerStartupError(msg)
case class BlackboxStartupError(msg: String) extends ContainerStartupError(msg)
case class InitializationError(response: ActivationResponse, interval: Interval) extends Exception(response.toString)
