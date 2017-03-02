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

package whisk.http

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import spray.http.MediaType
import spray.http.MediaTypes
import spray.http.StatusCode
import spray.http.StatusCodes.Forbidden
import spray.http.StatusCodes.NotFound
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.json._
import spray.json.DefaultJsonProtocol._
import spray.routing.Directives
import spray.routing.Rejection
import spray.routing.StandardRoute
import whisk.common.TransactionId
import whisk.core.entity.SizeError
import whisk.core.entity.ByteSize
import whisk.core.entity.Exec

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
    def runtimeDeprecated(e: Exec) = s"The '${e.kind}' runtime is no longer supported. You may read and delete but not update or invoke this action."

    /** Standard message for resource not found. */
    val resourceDoesNotExist = "The requested resource does not exist."

    /** Standard message for too many activation requests within a rolling time window. */
    val tooManyRequests = "Too many requests in a given amount of time for namespace."

    /** Standard message for too many concurrent activation requests within a time window. */
    val tooManyConcurrentRequests = "Too many concurrent requests in flight for namespace."

    /** System overload message. */
    val systemOverloaded = "System is overloaded, try again later."

    /** Standard message when supplied authkey is not authorized for an operation. */
    val notAuthorizedtoOperateOnResource = "The supplied authentication is not authorized to access this resource."

    /** Standard error message for malformed fully qualified entity names. */
    val malformedFullyQualifiedEntityName = "The fully qualified name of the entity must contain at least the namespace and the name of the entity."
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

    /** Error messages for sequence activations. */
    val sequenceRetrieveActivationTimeout = "Timeout reached when retrieving activation for sequence component."
    val sequenceActivationFailure = "Sequence failed."

    /** Error messages for bad requests where parameters do not conform. */
    val parametersNotAllowed = "Request defines parameters that are not allowed (e.g., reserved properties)."
    def invalidTimeout(max: FiniteDuration) = s"Timeout must be number of milliseconds up to ${max.toMillis}."

    /** Error messages for activations. */
    val abnormalInitialization = "The action did not initialize and exited unexpectedly."
    val abnormalRun = "The action did not produce a valid response and exited unexpectedly."

    /** Error message for size conformance. */
    def entityTooBig(error: SizeError) = {
        s"${error.field} larger than allowed: ${error.is.toBytes} > ${error.allowed.toBytes} bytes."
    }

    def truncateLogs(limit: ByteSize) = {
        s"Logs were truncated because the total bytes size exceeds the limit of ${limit.toBytes} bytes."
    }

    /** Error for meta api. */
    val propertyNotFound = "Response does not include requested property."
    def invalidMedia(m: MediaType) = s"Response is not valid '${m.value}'."
    val contentTypeExtentionNotSupported = """Extension must be specified and one of [".json", ".html", ".http", ".text"]."""
    val contentTypeNotSupported = s"Content-type must be ${MediaTypes.`application/json`} or ${MediaTypes.`application/x-www-form-urlencoded`}."
    def invalidAcceptType(m: MediaType) = s"Response type of ${m.value} does not correspond with accept header."

    val responseNotReady = "Response not yet ready."
    val httpUnknownContentType = "Response did not specify a known content-type."
    val httpContentTypeError = "Response type in header did not match generated content type."
    val errorProcessingRequest = "There was an error processing your request."

    def invalidInitResponse(actualResponse: String) = {
        "The action failed during initialization" + {
            Option(actualResponse) filter { _.nonEmpty } map { s => s": $s" } getOrElse "."
        }
    }

    def invalidRunResponse(actualResponse: String) = {
        "The action did not produce a valid JSON response" + {
            Option(actualResponse) filter { _.nonEmpty } map { s => s": $s" } getOrElse "."
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
}

/** Replaces rejections with Json object containing cause and transaction id. */
case class ErrorResponse(error: String, code: TransactionId)

/** Custom rejection, wraps status code for response and a cause. */
case class CustomRejection private (status: StatusCode, cause: String) extends Rejection

object ErrorResponse extends Directives {

    def terminate(status: StatusCode, error: String)(implicit transid: TransactionId): StandardRoute = {
        terminate(status, Option(error) filter { _.trim.nonEmpty } map {
            e => Some(ErrorResponse(e.trim, transid))
        } getOrElse None)
    }

    def terminate(status: StatusCode, error: Option[ErrorResponse] = None)(implicit transid: TransactionId): StandardRoute = {
        complete(status, error getOrElse response(status))
    }

    def response(status: StatusCode)(implicit transid: TransactionId): ErrorResponse = status match {
        case NotFound  => ErrorResponse(Messages.resourceDoesNotExist, transid)
        case Forbidden => ErrorResponse(Messages.notAuthorizedtoOperateOnResource, transid)
        case _         => ErrorResponse(status.defaultMessage, transid)
    }

    implicit val serializer = new RootJsonFormat[ErrorResponse] {
        def write(er: ErrorResponse) = JsObject(
            "error" -> er.error.toJson,
            "code" -> er.code.meta.id.toJson)

        def read(v: JsValue) = Try {
            v.asJsObject.getFields("error", "code") match {
                case Seq(JsString(error), JsNumber(code)) =>
                    ErrorResponse(error, TransactionId(code))
                case Seq(JsString(error)) =>
                    ErrorResponse(error, TransactionId.unknown)
            }
        } getOrElse deserializationError("error response malformed")
    }

}

object CustomRejection {
    def apply(status: StatusCode): CustomRejection = {
        CustomRejection(status, status.defaultMessage)
    }
}
