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

package org.apache.openwhisk.core.containerpool.lambda
import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.aws.{LambdaAction, LambdaStore}
import org.apache.openwhisk.core.containerpool.logging.LogLine
import org.apache.openwhisk.core.containerpool.{Container, ContainerAddress, ContainerId, Interval, RunResult}
import org.apache.openwhisk.core.entity.ByteSize
import spray.json._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

case class LambdaContainer(action: LambdaAction, store: LambdaStore)(implicit override protected val as: ActorSystem,
                                                                     protected val ec: ExecutionContext,
                                                                     protected val logging: Logging)
    extends Container {
  override protected val id: ContainerId = ContainerId(action.arn.name)
  //Using a fake address here as its not used in case of Lambda
  override protected val addr: ContainerAddress = ContainerAddress("0.0.0.0")

  /**
   * These is no explicit initialization phase with Lambda as functions have the required code attached to them
   */
  override def initialize(initializer: JsObject, timeout: FiniteDuration, maxConcurrent: Int)(
    implicit transid: TransactionId): Future[Interval] = Future.successful(Interval.zero)

  override protected def callContainer(path: String,
                                       body: JsObject,
                                       timeout: FiniteDuration,
                                       maxConcurrent: Int,
                                       retry: Boolean)(implicit transid: TransactionId): Future[RunResult] = {
    //TODO Use timeout
    require(!retry, "Retry should be false as LambdaContainer only supports '/run' call")
    logging.info(this, s"Invoking Lambda $action")
    store.invokeLambda(action, body)
  }

  private val logMsg = "Log collection is not configured correctly, check with your service administrator."

  override def logs(limit: ByteSize, waitForSentinel: Boolean)(
    implicit transid: TransactionId): Source[ByteString, Any] =
    Source.single(ByteString(LogLine(logMsg, "stdout", Instant.now.toString).toJson.compactPrint))
}
