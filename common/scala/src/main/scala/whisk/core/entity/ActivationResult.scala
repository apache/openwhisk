/*
 * Copyright 2015-2017 IBM Corporation
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
import spray.json._
import whisk.common.Logging
import whisk.http.Messages._

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

    protected[core] def messageForCode(code: Int) = {
        require(code >= 0 && code <= 3)
        code match {
            case 0 => "success"
            case 1 => "application error"
            case 2 => "action developer error"
            case 3 => "whisk internal error"
        }
    }

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

    /**
     * Returns an ActivationResponse that is used as a placeholder for payload
     * Used as a feed for starting a sequence.
     * NOTE: the code is application error (since this response could be used as a response for the sequence
     * if the payload contains an error)
     */
    protected[core] def payloadPlaceholder(payload: Option[JsObject]) = ActivationResponse(ApplicationError, payload)

    /**
     * Interprets response from container after initialization. This method is only called when the initialization failed.
     *
     * @param response an Option (HTTP Status Code, HTTP response bytes as String)
     * @return appropriate ActivationResponse representing initialization error
     */
    protected[core] def processInitResponseContent(response: Option[(Int, String)], logger: Logging): ActivationResponse = {
        require(response map { _._1 != 200 } getOrElse true, s"should not interpret init response when status code is 200")

        response map {
            case (code, contents) =>
                logger.debug(this, s"init response: '$contents'")
                Try { contents.parseJson.asJsObject } match {
                    case scala.util.Success(result @ JsObject(fields)) =>
                        // If the response is a JSON object container an error field, accept it as the response error.
                        val errorOpt = fields.get(ERROR_FIELD)
                        val errorContent = errorOpt getOrElse invalidInitResponse(contents).toJson
                        containerError(errorContent)
                    case _ =>
                        containerError(invalidInitResponse(contents))
                }
        } getOrElse {
            // This indicates a terminal failure in the container (it exited prematurely).
            containerError(abnormalInitialization)
        }
    }

    /**
     * Interprets response from container after running the action. This method is only called when the initialization succeeded.
     *
     * @param response an Option (HTTP Status Code, HTTP response bytes as String)
     * @return appropriate ActivationResponse representing run result
     */
    protected[core] def processRunResponseContent(response: Option[(Int, String)], logger: Logging): ActivationResponse = {
        response map {
            case (code, contents) =>
                logger.debug(this, s"response: '$contents'")
                Try { contents.parseJson.asJsObject } match {
                    case scala.util.Success(result @ JsObject(fields)) =>
                        // If the response is a JSON object container an error field, accept it as the response error.
                        val errorOpt = fields.get(ERROR_FIELD)

                        if (code == 200) {
                            errorOpt map { error =>
                                applicationError(error)
                            } getOrElse {
                                // The happy path.
                                success(Some(result))
                            }
                        } else {
                            // Any non-200 code is treated as a container failure. We still need to check whether
                            // there was a useful error message in there.
                            val errorContent = errorOpt getOrElse invalidRunResponse(contents).toJson
                            containerError(errorContent)
                        }

                    case scala.util.Success(notAnObj) =>
                        // This should affect only blackbox containers, since our own containers should already test for that.
                        containerError(invalidRunResponse(contents))

                    case scala.util.Failure(t) =>
                        // This should affect only blackbox containers, since our own containers should already test for that.
                        logger.warn(this, s"response did not json parse: '$contents' led to $t")
                        containerError(invalidRunResponse(contents))
                }
        } getOrElse {
            // This indicates a terminal failure in the container (it exited prematurely).
            containerError(abnormalRun)
        }
    }
    protected[core] implicit val serdes = jsonFormat2(ActivationResponse.apply)
}
