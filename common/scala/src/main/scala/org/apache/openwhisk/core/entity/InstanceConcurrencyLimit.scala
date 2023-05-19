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

package org.apache.openwhisk.core.entity

import org.apache.openwhisk.http.Messages

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import spray.json._

/**
 * InstanceConcurrencyLimit encapsulates max allowed container concurrency for an action within a given namespace.
 * A user is given a max concurrency for their entire namespace, but this doesn't allow for any fairness across their actions
 * during load spikes. This action limit allows a user to specify max container concurrency for a specific action within the
 * constraints of their namespace limit. By default, this limit does not exist and therefore the namespace concurrency limit is used.
 * The allowed range is thus [1, namespaceConcurrencyLimit]. If this config is not used by any actions, then the default behavior
 * of openwhisk continues in which any action can use the entire concurrency limit of the namespace. The limit less than namespace
 * limit check occurs at the api level.
 *
 * NOTE: This limit is only leveraged on openwhisk v2 with the scheduler service. If this limit is set on a deployment of openwhisk
 * not using the scheduler service, the limit will do nothing.
 *
 *
 * @param maxConcurrentInstances the max number of concurrent activations in a single container
 */
protected[entity] class InstanceConcurrencyLimit private (val maxConcurrentInstances: Int) extends AnyVal

protected[core] object InstanceConcurrencyLimit extends ArgNormalizer[InstanceConcurrencyLimit] {

  /** These values are set once at the beginning. Dynamic configuration updates are not supported at the moment. */
  protected[core] val MIN_INSTANCES_LIMIT: Int = 0

  /**
   * Creates ContainerConcurrencyLimit for limit, iff limit is within permissible range.
   *
   * @param maxConcurrenctInstances the limit, must be within permissible range
   * @return ConcurrencyLimit with limit set
   * @throws IllegalArgumentException if limit does not conform to requirements
   */
  @throws[IllegalArgumentException]
  protected[core] def apply(maxConcurrenctInstances: Int): InstanceConcurrencyLimit = {
    require(
      maxConcurrenctInstances >= MIN_INSTANCES_LIMIT,
      Messages.belowMinAllowedActionInstanceConcurrency(MIN_INSTANCES_LIMIT))
    new InstanceConcurrencyLimit(maxConcurrenctInstances)
  }

  override protected[core] implicit val serdes = new RootJsonFormat[InstanceConcurrencyLimit] {
    def write(m: InstanceConcurrencyLimit) = JsNumber(m.maxConcurrentInstances)

    def read(value: JsValue) = {
      Try {
        val JsNumber(c) = value
        require(c.isWhole, "instance concurrency limit must be whole number")

        InstanceConcurrencyLimit(c.toInt)
      } match {
        case Success(limit)                       => limit
        case Failure(e: IllegalArgumentException) => deserializationError(e.getMessage, e)
        case Failure(e: Throwable)                => deserializationError("instance concurrency limit malformed", e)
      }
    }
  }
}
