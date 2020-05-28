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

import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.entity.size._

case class ActivationEntityPayload(max: ByteSize, truncation: ByteSize)
case class ActivationEntityLimitConf(serdesOverhead: ByteSize, payload: ActivationEntityPayload)

/**
 * ActivationEntityLimit defines the limits on the input/output payloads for actions
 * and triggers. This refers to the invoke-time parameters for actions or the trigger-time
 * parameters for triggers.
 */
protected[core] object ActivationEntityLimit {
  private val config = loadConfigOrThrow[ActivationEntityLimitConf](ConfigKeys.activation)

  protected[core] val MAX_ACTIVATION_ENTITY_LIMIT: ByteSize = config.payload.max
  protected[core] val MAX_ACTIVATION_ENTITY_TRUNCATION_LIMIT: ByteSize = config.payload.truncation
  protected[core] val MAX_ACTIVATION_LIMIT: ByteSize = config.payload.max + config.serdesOverhead
}
