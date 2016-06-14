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

package whisk.core.entity

import scala.util.Try

import spray.json.DefaultJsonProtocol
import spray.json.JsBoolean
import spray.json.JsNull
import spray.json.JsValue
import spray.json.JsObject
import spray.json.JsString

protected[core] case class ActivationResponse private (
    val statusCode: Int, val result: Option[JsValue]) {

    def toJsonObject = ActivationResponse.serdes.write(this).asJsObject

    // Used when presenting to end-users, to hide the statusCode (which is an implementation detail),
    // and to provide a convenience boolean "success" field.
    def toExtendedJson: JsObject = {
        val baseFields = this.toJsonObject.fields
        JsObject((baseFields - "statusCode") ++ Seq(
            "success" -> JsBoolean(this.isSuccess),
            "status" -> JsString(ActivationResponse.messageForCode(statusCode))))

    }

    def isSuccess = statusCode == ActivationResponse.Success
    def isWhiskError = statusCode == ActivationResponse.WhiskError
    def isContainerError = statusCode == ActivationResponse.ContainerError
    def isApplicationError = statusCode == ActivationResponse.ApplicationError

    override def toString = toJsonObject.compactPrint
}

protected[core] object ActivationResponse extends DefaultJsonProtocol {
    /* The field name that is universally recognized as the marker of an error, from the application or otherwise. */
    val ERROR_FIELD: String = "error"

    val Success          = 0
    val ApplicationError = 1
    val ContainerError   = 2
    val WhiskError       = 3

    private def messageForCode(code: Int) = {
        require(code >= 0 && code <= 3)
        code match {
            case 0 => "success"
            case 1 => "application error"
            case 2 => "action developer error"
            case 3 => "whisk internal error"
        }
    }

    private def isSuccess(code: Int) = (code == Success)

    private def error(code: Int, errorValue: JsValue) = {
        require(code == ApplicationError || code == ContainerError || code == WhiskError)
        ActivationResponse(code, Some(JsObject(ERROR_FIELD -> errorValue)))
    }

    protected[core] def success(result: Option[JsValue] = None) = ActivationResponse(Success, result)

    protected[core] def applicationError(errorValue: JsValue) = error(ApplicationError, errorValue)
    protected[core] def applicationError(errorMsg: String)    = error(ApplicationError, JsString(errorMsg))
    protected[core] def containerError(errorValue: JsValue)   = error(ContainerError, errorValue)
    protected[core] def containerError(errorMsg: String)      = error(ContainerError, JsString(errorMsg))
    protected[core] def whiskError(errorValue: JsValue)       = error(WhiskError, errorValue)
    protected[core] def whiskError(errorMsg: String)          = error(WhiskError, JsString(errorMsg))

    protected[core] implicit val serdes = jsonFormat2(ActivationResponse.apply)
}
