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

package whisk.http

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.Forbidden
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import akka.http.scaladsl.server.StandardRoute

import spray.json._

import whisk.common.TransactionId
import whisk.core.entity.SizeError
import whisk.core.entity.ByteSize
import whisk.core.entity.Exec
import whisk.core.entity.ExecMetaDataBase
import whisk.core.entity.ActivationId

object Messages {

  /** Standard message for reporting resource conflicts. */
  val conflictMessage = "Concurrent modification to resource detected."

  /**
   * Standard message for reporting resource conformance error when trying to access
   * a resource from a different collection.
   */
  val conformanceMessage = "Resource by this name exists but is not in this collection."
  val corruptedEntity = "Resource is corrupted and cannot be read."

  /**
   * Standard message for reporting deprecated runtimes.
   */
  def runtimeDeprecated(e: Exec) =
    s"The '${e.kind}' runtime is no longer supported. You may read and delete but not update or invoke this action."

  /**
   * Standard message for reporting deprecated runtimes.
   */
  def runtimeDeprecated(e: ExecMetaDataBase) =
    s"The '${e.kind}' runtime is no longer supported. You may read and delete but not update or invoke this action."

  /** Standard message for resource not found. */
  val resourceDoesNotExist = "The requested resource does not exist."
  def resourceDoesntExist(value: String) = s"The requested resource '$value' does not exist."

  /** Standard message for too many activation requests within a rolling time window. */
  def tooManyRequests(count: Int, allowed: Int) =
    s"Too many requests in the last minute (count: $count, allowed: $allowed)."

  /** Standard message for too many concurrent activation requests within a time window. */
  def tooManyConcurrentRequests(count: Int, allowed: Int) =
    s"Too many concurrent requests in flight (count: $count, allowed: $allowed)."

  /** System overload message. */
  val systemOverloaded = "System is overloaded, try again later."

  /** Standard message when supplied authkey is not authorized for an operation. */
  val notAuthorizedtoOperateOnResource = "The supplied authentication is not authorized to access this resource."
  def notAuthorizedtoAccessResource(value: String) =
    s"The supplied authentication is not authorized to access '$value'."

  /** Standard error message for malformed fully qualified entity names. */
  val malformedFullyQualifiedEntityName =
    "The fully qualified name of the entity must contain at least the namespace and the name of the entity."
  def entityNameTooLong(error: SizeError) = {
    s"${error.field} longer than allowed: ${error.is.toBytes} > ${error.allowed.toBytes}."
  }
  val entityNameIllegal = "The name of the entity contains illegal characters."
  val namespaceIllegal = "The namespace contains illegal characters."

  /** Standard error for malformed activation id. */
  val activationIdIllegal = "The activation id is not valid."
  def activationIdLengthError(error: SizeError) = {
    s"${error.field} length is ${error.is.toBytes} but must be ${error.allowed.toBytes}."
  }

  /** Error messages for sequence actions. */
  val sequenceIsTooLong = "Too many actions in the sequence."
  val sequenceNoComponent = "No component specified for the sequence."
  val sequenceIsCyclic = "Sequence may not refer to itself."
  val sequenceComponentNotFound = "Sequence component does not exist."

  /** Error message for packages. */
  val bindingDoesNotExist = "Binding references a package that does not exist."
  val packageCannotBecomeBinding = "Resource is a package and cannot be converted into a binding."
  val bindingCannotReferenceBinding = "Cannot bind to another package binding."
  val requestedBindingIsNotValid = "Cannot bind to a resource that is not a package."
  val notAllowedOnBinding = "Operation not permitted on package binding."
  def packageNameIsReserved(name: String) = {
    s"Package name '$name' is reserved."
  }

  /** Error messages for sequence activations. */
  def sequenceRetrieveActivationTimeout(id: ActivationId) =
    s"Timeout reached when retrieving activation $id for sequence component."
  val sequenceActivationFailure = "Sequence failed."

  /** Error messages for bad requests where parameters do not conform. */
  val parametersNotAllowed = "Request defines parameters that are not allowed (e.g., reserved properties)."
  def invalidTimeout(max: FiniteDuration) = s"Timeout must be number of milliseconds up to ${max.toMillis}."

  /** Error messages for activations. */
  val abnormalInitialization = "The action did not initialize and exited unexpectedly."
  val abnormalRun = "The action did not produce a valid response and exited unexpectedly."
  val memoryExhausted = "The action exhausted its memory and was aborted."
  def badNameFilter(value: String) = s"Parameter may be a 'simple' name or 'package-name/simple' name: $value"
  def badEpoch(value: String) = s"Parameter is not a valid value for epoch seconds: $value"

  /** Error message for size conformance. */
  def entityTooBig(error: SizeError) = {
    s"${error.field} larger than allowed: ${error.is.toBytes} > ${error.allowed.toBytes} bytes."
  }
  def maxActivationLimitExceeded(value: Int, max: Int) = s"Activation limit of $value exceeds maximum limit of $max."

  def truncateLogs(limit: ByteSize) = {
    s"Logs were truncated because the total bytes size exceeds the limit of ${limit.toBytes} bytes."
  }

  /** Error for meta api. */
  val propertyNotFound = "Response does not include requested property."
  def invalidMedia(m: MediaType) = s"Response is not valid '${m.value}'."
  def contentTypeExtensionNotSupported(extensions: Set[String]) = {
    s"""Extension must be specified and one of ${extensions.mkString("[", ", ", "]")}."""
  }
  val unsupportedContentType = """Content type is not supported."""
  def unsupportedContentType(m: MediaType) = s"""Content type '${m.value}' is not supported."""
  val errorExtractingRequestBody = "Failed extracting request body."

  val responseNotReady = "Response not yet ready."
  val httpUnknownContentType = "Response did not specify a known content-type."
  val httpContentTypeError = "Response type in header did not match generated content type."
  val errorProcessingRequest = "There was an error processing your request."

  def invalidInitResponse(actualResponse: String) = {
    "The action failed during initialization" + {
      Option(actualResponse) filter { _.nonEmpty } map { s =>
        s": $s"
      } getOrElse "."
    }
  }

  def invalidRunResponse(actualResponse: String) = {
    "The action did not produce a valid JSON response" + {
      Option(actualResponse) filter { _.nonEmpty } map { s =>
        s": $s"
      } getOrElse "."
    }
  }

  def truncatedResponse(length: ByteSize, maxLength: ByteSize): String = {
    s"The action produced a response that exceeded the allowed length: ${length.toBytes} > ${maxLength.toBytes} bytes."
  }

  def truncatedResponse(trunk: String, length: ByteSize, maxLength: ByteSize): String = {
    s"${truncatedResponse(length, maxLength)} The truncated response was: $trunk"
  }

  def timedoutActivation(timeout: Duration, init: Boolean) = {
    s"The action exceeded its time limits of ${timeout.toMillis} milliseconds" + {
      if (!init) "." else " during initialization."
    }
  }

  val actionRemovedWhileInvoking = "Action could not be found or may have been deleted."
}

/** Replaces rejections with Json object containing cause and transaction id. */
case class ErrorResponse(error: String, code: TransactionId)

object ErrorResponse extends Directives with DefaultJsonProtocol {

  def terminate(status: StatusCode, error: String)(implicit transid: TransactionId,
                                                   jsonPrinter: JsonPrinter): StandardRoute = {
    terminate(status, Option(error) filter { _.trim.nonEmpty } map { e =>
      Some(ErrorResponse(e.trim, transid))
    } getOrElse None)
  }

  def terminate(status: StatusCode, error: Option[ErrorResponse] = None, asJson: Boolean = true)(
    implicit transid: TransactionId,
    jsonPrinter: JsonPrinter): StandardRoute = {
    val errorResponse = error getOrElse response(status)
    if (asJson) {
      complete(status, errorResponse)
    } else {
      complete(status, s"${errorResponse.error} (code: ${errorResponse.code})")
    }
  }

  def response(status: StatusCode)(implicit transid: TransactionId): ErrorResponse = status match {
    case NotFound  => ErrorResponse(Messages.resourceDoesNotExist, transid)
    case Forbidden => ErrorResponse(Messages.notAuthorizedtoOperateOnResource, transid)
    case _         => ErrorResponse(status.defaultMessage, transid)
  }

  implicit val serializer = new RootJsonFormat[ErrorResponse] {
    def write(er: ErrorResponse) = JsObject("error" -> er.error.toJson, "code" -> er.code.meta.id.toJson)

    def read(v: JsValue) =
      Try {
        v.asJsObject.getFields("error", "code") match {
          case Seq(JsString(error), JsNumber(code)) =>
            ErrorResponse(error, TransactionId(code))
          case Seq(JsString(error)) =>
            ErrorResponse(error, TransactionId.unknown)
        }
      } getOrElse deserializationError("error response malformed")
  }

}
