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

package org.apache.openwhisk.core.aws
import java.time.Instant

import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.{Interval, RunResult}
import org.apache.openwhisk.core.entity.ActivationResponse.{ConnectionError, ContainerResponse}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.{InvocationType, InvokeRequest}
import spray.json.JsObject

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object LambdaStoreProvider {

  def makeStore(config: Config = ConfigFactory.defaultApplication())(implicit ec: ExecutionContext): LambdaStore = {
    val awsConfig = config.atPath(ConfigKeys.aws)
    val client = LambdaAsyncClient
      .builder()
      .credentialsProvider(CredentialProvider(awsConfig))
      .region(RegionProvider(awsConfig).getRegion)
      .build()
    new LambdaStore(client)
  }
}

class LambdaStore(client: LambdaAsyncClient)(implicit ec: ExecutionContext) {

  def invoke(name: String, body: JsObject)(implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val request = InvokeRequest
      .builder()
      .functionName(name)
      .invocationType(InvocationType.REQUEST_RESPONSE)
      .payload(SdkBytes.fromUtf8String(body.toString()))
      .build()

    //TODO Apply timeout. Looks like timeout is only used in case retry = true
    //for /run case retry is false. So need not bother about timeout
    client
      .invoke(request)
      .toScala
      .map { invokeResponse =>
        // Lambda has 6 MB limit on request and response. So we may need to truncate the response
        val entity = invokeResponse.payload().asUtf8String()
        val res = invokeResponse.functionError() match {
          //TODO Map error code - Default nodejs runtime seems to return 502 for some of the handled error messages
          //TODO Honor MaxResponse
          //TODO Need to stuff requestId someway with activationResponse to allow corelating the logs
          case "Handled"   => ContainerResponse(502, entity, None)
          case "Unhandled" => ContainerResponse(InternalServerError.intValue, entity, None)
          case _           => ContainerResponse(OK.intValue, entity, None)
        }
        Right(res)
      }
      .recover {
        case NonFatal(t) => Left(ConnectionError(t))
      }
      .map { response =>
        val finished = Instant.now()
        RunResult(Interval(started, finished), response)
      }
  }

}
