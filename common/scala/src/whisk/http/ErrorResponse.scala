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

import scala.util.Try

import spray.http.StatusCode
import spray.http.StatusCodes.NotFound
import spray.httpx.marshalling.ToResponseMarshallable.isMarshallable
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.json.DefaultJsonProtocol.LongJsonFormat
import spray.json.DefaultJsonProtocol.StringJsonFormat
import spray.json.JsNumber
import spray.json.JsObject
import spray.json.JsString
import spray.json.JsValue
import spray.json.RootJsonFormat
import spray.json.deserializationError
import spray.json.pimpAny
import spray.routing.Directives
import spray.routing.StandardRoute
import whisk.common.TransactionId

/** Return all rejections as Json object. */
case class ErrorResponse(error: String, code: TransactionId)

object ErrorResponse extends Directives {

    def terminate(code: StatusCode, error: String)(implicit transid: TransactionId): StandardRoute = {
        terminate(code, if (error != null && error.trim.nonEmpty) {
            Some(ErrorResponse(error.trim, transid))
        } else None)
    }

    def terminate(code: StatusCode, error: Option[ErrorResponse] = None)(implicit transid: TransactionId): StandardRoute = {
        complete(code, error getOrElse response(code))
    }

    def response(code: StatusCode)(implicit transid: TransactionId): ErrorResponse = code match {
        case NotFound => ErrorResponse("The requested resource does not exist.", transid)
        case _        => ErrorResponse(code.defaultMessage, transid)
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
