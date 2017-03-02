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

package whisk.core.controller.actions

import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

import spray.http.StatusCodes.BadRequest
import spray.json._
import whisk.common.TransactionId
import whisk.core.controller.RejectRequest
import whisk.core.controller.WhiskActionsApi._
import whisk.core.controller.WhiskServices
import whisk.core.entity._
import whisk.http.Messages

protected[core] trait PostActionActivation extends PrimitiveActions with SequenceActions {
    /** The core collections require backend services to be injected in this trait. */
    services: WhiskServices =>

    /**
     * Invokes an action which may be a sequence or a primitive (single) action.
     *
     * @param user the user posting the activation
     * @param action the action to activate (parameters for packaged actions must already be merged)
     * @param payload the parameters to pass to the action
     * @param blocking iff true, wait for the activation result
     * @param waitOverride iff blocking, wait up up to the action limit or a given max duration
     * @return a future that resolves with the (activation id, and some whisk activation if a blocking invoke)
     */
    protected[controller] def invokeAction(user: Identity, action: WhiskAction, payload: Option[JsObject], blocking: Boolean, waitOverride: Option[FiniteDuration] = None)(
        implicit transid: TransactionId): Future[(ActivationId, Option[WhiskActivation])] = {
        action.exec match {
            // this is a topmost sequence
            case SequenceExec(components) =>
                val futureSeqTuple = invokeSequence(user, action, payload, blocking, topmost = true, components, cause = None, 0)
                futureSeqTuple map { case (activationId, wskActivation, _) => (activationId, wskActivation) }
            case supportedExec if !supportedExec.deprecated =>
                val duration = action.limits.timeout.duration + blockingInvokeGrace
                val timeout = waitOverride.getOrElse(duration)
                invokeSingleAction(user, action, payload, timeout, blocking)
            case deprecatedExec =>
                Future.failed(RejectRequest(BadRequest, Messages.runtimeDeprecated(deprecatedExec)))
        }
    }
}

protected[controller] case class BlockingInvokeTimeout(activationId: ActivationId) extends TimeoutException
