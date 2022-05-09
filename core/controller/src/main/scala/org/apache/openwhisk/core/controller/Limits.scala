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

package org.apache.openwhisk.core.controller

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Directives}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonMarshaller
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entitlement.{Collection, Privilege, Resource}
import org.apache.openwhisk.core.entitlement.Privilege.READ
import org.apache.openwhisk.core.entity.{ConcurrencyLimit, Identity, LogLimit, MemoryLimit, TimeLimit}

trait WhiskLimitsApi extends Directives with AuthenticatedRouteProvider with AuthorizedRouteProvider {

  protected val whiskConfig: WhiskConfig

  protected override val collection = Collection(Collection.LIMITS)

  protected val invocationsPerMinuteSystemDefault = whiskConfig.actionInvokePerMinuteLimit.toInt
  protected val concurrentInvocationsSystemDefault = whiskConfig.actionInvokeConcurrentLimit.toInt
  protected val firePerMinuteSystemDefault = whiskConfig.triggerFirePerMinuteLimit.toInt

  override protected lazy val entityOps = get

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /** Dispatches resource to the proper handler depending on context. */
  protected override def dispatchOp(user: Identity, op: Privilege, resource: Resource)(
    implicit transid: TransactionId) = {

    resource.entity match {
      case Some(_) =>
        //TODO: Process entity level requests for an individual limit here
        reject //should never get here
      case None =>
        op match {
          case READ =>
            val limits = user.limits.copy(
              Some(user.limits.invocationsPerMinute.getOrElse(invocationsPerMinuteSystemDefault)),
              Some(user.limits.concurrentInvocations.getOrElse(concurrentInvocationsSystemDefault)),
              Some(user.limits.firesPerMinute.getOrElse(firePerMinuteSystemDefault)),
              actionMemoryMax = Some(user.limits.actionMemoryMax.getOrElse(MemoryLimit(MemoryLimit.MAX_MEMORY_DEFAULT))),
              actionMemoryMin = Some(user.limits.actionMemoryMin.getOrElse(MemoryLimit(MemoryLimit.MIN_MEMORY_DEFAULT))),
              actionLogsMax = Some(user.limits.actionLogsMax.getOrElse(LogLimit(LogLimit.MAX_LOGSIZE_DEFAULT))),
              actionLogsMin = Some(user.limits.actionLogsMin.getOrElse(LogLimit(LogLimit.MIN_LOGSIZE_DEFAULT))),
              actionDurationMax =
                Some(user.limits.actionDurationMax.getOrElse(TimeLimit(TimeLimit.MAX_DURATION_DEFAULT))),
              actionDurationMin =
                Some(user.limits.actionDurationMin.getOrElse(TimeLimit(TimeLimit.MIN_DURATION_DEFAULT))),
              actionConcurrencyMax = Some(
                user.limits.actionConcurrencyMax.getOrElse(ConcurrencyLimit(ConcurrencyLimit.MAX_CONCURRENT_DEFAULT))),
              actionConcurrencyMin = Some(
                user.limits.actionConcurrencyMin.getOrElse(ConcurrencyLimit(ConcurrencyLimit.MIN_CONCURRENT_DEFAULT))),
            )
            pathEndOrSingleSlash { complete(OK, limits) }
          case _ => reject //should never get here
        }
    }
  }

  protected override def entityname(n: String): Directive1[String] = {
    validate(false, "Inner entity level routes for limits are not yet implemented.") & extract(_ => n)
  }
}
