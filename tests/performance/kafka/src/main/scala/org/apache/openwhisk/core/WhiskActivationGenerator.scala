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

package org.apache.openwhisk.core
import java.time.Instant

import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.connector.{AcknowledegmentMessage, CombinedCompletionAndResultMessage}
import org.apache.openwhisk.core.entity.{
  ActivationId,
  ActivationResponse,
  EntityName,
  EntityPath,
  InvokerInstanceId,
  Subject,
  WhiskActivation
}
import spray.json.{JsObject, JsString}
import org.apache.openwhisk.core.entity.size._

class WhiskActivationGenerator(size: Int) extends MessageGenerator {
  private val invokerId = InvokerInstanceId(1, userMemory = 1.MB)

  override def next(index: Int)(implicit tid: TransactionId): JsObject = {
    newCombinedMessage("test").toJson.asJsObject
  }

  private def newCombinedMessage(namespace: String): AcknowledegmentMessage = {
    CombinedCompletionAndResultMessage(TransactionId.testing, newActivation(namespace), invokerId)
  }

  private def newActivation(namespace: String) = {
    //TODO For now result size is increased by repetition
    //Later add support for random result string to check against
    //possible compression
    WhiskActivation(
      EntityPath(namespace),
      EntityName("testAction"),
      Subject(),
      ActivationId.generate(),
      Instant.now().minusSeconds(4000),
      Instant.now(),
      response = ActivationResponse.success(Some(JsString("a" * size))))
  }
}
