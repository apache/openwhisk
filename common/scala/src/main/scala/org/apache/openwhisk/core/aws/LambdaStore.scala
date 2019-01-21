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
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.zip.{ZipEntry, ZipOutputStream}

import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.containerpool.{Interval, RunResult}
import org.apache.openwhisk.core.entity.ActivationResponse.{ConnectionError, ContainerResponse}
import org.apache.openwhisk.core.entity.Attachments.Inline
import org.apache.openwhisk.core.entity.{CodeExecAsAttachment, WhiskAction}
import pureconfig._
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaAsyncClient
import software.amazon.awssdk.services.lambda.model.{
  CreateFunctionRequest,
  FunctionCode,
  InvocationType,
  InvokeRequest,
  Runtime => LambdaRuntime
}
import spray.json.JsObject

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object LambdaStoreProvider {

  def makeStore(config: Config = ConfigFactory.defaultApplication())(implicit ec: ExecutionContext): LambdaStore = {
    //TODO enable configuring unsecure access for dev purpose
    val awsConfig = config.atPath(ConfigKeys.aws)
    val client = LambdaAsyncClient
      .builder()
      .credentialsProvider(CredentialProvider(awsConfig))
      .region(RegionProvider(awsConfig).getRegion)
      .build()
    val lambdaConfig = loadConfigOrThrow[LambdaConfig](config, ConfigKeys.lambda)
    new LambdaStore(client, lambdaConfig)
  }
}

case class LambdaConfig(layerMappings: Map[String, String], accountId: String, commonRoleName: String)

case class LambdaAction(arn: String, revisionId: String)

class LambdaStore(client: LambdaAsyncClient, config: LambdaConfig)(implicit ec: ExecutionContext) {

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

  def createOrUpdate(action: WhiskAction): Future[Option[LambdaAction]] = {
    val r = for {
      layer <- getMatchingLayer(action)
      handlerName <- getHandlerName(action)
      code <- getFunctionCode(action)
    } yield (layer, handlerName, code)

    r.map {
        case (layer, handlerName, code) =>
          val funcName = getFunctionName(action)
          getOrCreateRole(funcName).flatMap { role =>
            val request = CreateFunctionRequest
              .builder()
              .code(code)
              .handler(handlerName)
              .layers(layer)
              .runtime(LambdaRuntime.PROVIDED)
              .functionName(funcName)
              .memorySize(getFunctionMemory(action))
              .timeout(getFunctionTimeout(action))
              .role(role)
              .tags(getTags(action).asJava)
              .build()

            client
              .createFunction(request)
              .toScala
              .map(response => Some(LambdaAction(response.functionArn(), response.revisionId())))
          }
      }
      .getOrElse(Future.successful(None))
  }

  def getMatchingLayer(action: WhiskAction): Option[String] = {
    config.layerMappings.get(action.exec.kind)
  }

  def getFunctionName(action: WhiskAction): String = {
    //TODO Lambda places a 64 char limit and OW allows much larger names
    //So need a way to encode name say via `<functionName{0,40}>_<10 letters from hash>`
    val name = action.fullyQualifiedName(false).asString.replace("/", "_")
    s"ow_$name"
  }

  def getFunctionMemory(action: WhiskAction): Int = {
    val mb = action.limits.memory.megabytes
    val delta = mb % 64

    //Memory needs to be in chunk of 64 MB
    mb + delta
  }

  def getOrCreateRole(functionName: String): Future[String] = {
    //TODO Temp usage of a generic role. Need to create per function role
    Future.successful(config.commonRoleName)
  }

  def getHandlerName(action: WhiskAction): Option[String] = {
    action.exec match {
      case exec @ CodeExecAsAttachment(_, _, entryPoint, _) =>
        if (isNodeJs(exec.kind)) Some(s"index.${entryPoint.getOrElse("main")}") else None
      case _ => None
    }
  }

  //TODO Other attributes to add
  def getTags(action: WhiskAction): Map[String, String] =
    Map(
      "fqn" -> action.fullyQualifiedName(true).asString,
      "rev" -> action.rev.asString,
      "namespace" -> action.namespace.asString,
      "name" -> action.name.asString)

  def getFunctionTimeout(action: WhiskAction): Int = {
    //Lambda imposes max limit of 900 secs
    action.limits.timeout.duration.toSeconds.toInt
  }

  def getFunctionCode(action: WhiskAction): Option[FunctionCode] = {
    action.exec match {
      case exec @ CodeExecAsAttachment(_, Inline(code), entryPoint, binary) =>
        Some(createFunctionCode(code, binary, entryPoint, exec.kind))
      //TODO handle attachments
      case _ => None
    }
  }

  def createZip(bytes: Array[Byte], fileName: String): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val zos = new ZipOutputStream(baos)
    val zipEntry = new ZipEntry(fileName)
    zos.putNextEntry(zipEntry)
    zos.write(bytes, 0, bytes.length)
    zos.closeEntry()
    zos.close()
    baos.toByteArray
  }

  def adaptCode(code: String, entryPoint: Option[String], kind: String): Array[Byte] = {
    val adaptedCode = if (isNodeJs(kind)) {
      val main = entryPoint.getOrElse("main")
      code + s"\nexports.$main = $main;"
    } else {
      code
    }
    adaptedCode.getBytes(UTF_8)
  }

  def createFunctionCode(code: String, binary: Boolean, entryPoint: Option[String], kind: String): FunctionCode = {
    val bytes = if (binary) {
      Base64.getDecoder.decode(code)
    } else {
      createZip(adaptCode(code, entryPoint, kind), getFileName(kind))
    }
    FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes)).build()
  }

  def getFileName(kind: String): String =
    if (isNodeJs(kind)) "index.js" else throw new IllegalArgumentException(s"Unsupported kind [$kind]")

  def isNodeJs(kind: String) = kind.startsWith("node")

}
