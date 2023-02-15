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

package org.apache.openwhisk.core.ack

import org.apache.openwhisk.common.{Logging, TransactionId}
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, MessageProducer}
import org.apache.openwhisk.core.entity.{ControllerInstanceId, UUID, WhiskActivation}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.{ExecutionContext, Future}

class HealthActionAck(producer: MessageProducer)(implicit logging: Logging, ec: ExecutionContext) extends ActiveAck {
  override def apply(tid: TransactionId,
                     activationResult: WhiskActivation,
                     blockingInvoke: Boolean,
                     controllerInstance: ControllerInstanceId,
                     userId: UUID,
                     acknowledegment: AcknowledegmentMessage): Future[Any] = {
    implicit val transid: TransactionId = tid

    logging.debug(this, s"health action was successfully invoked")
    if (activationResult.response.isContainerError || activationResult.response.isWhiskError) {
      val actionPath =
        activationResult.annotations.getAs[String](WhiskActivation.pathAnnotation).getOrElse("unknown_path")
      logging.error(this, s"Failed to invoke action $actionPath, error: ${activationResult.response.toString}")
    }

    Future.successful({})
  }
}
