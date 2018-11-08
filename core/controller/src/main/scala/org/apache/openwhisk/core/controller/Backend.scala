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

import org.apache.openwhisk.core.WhiskConfig
import org.apache.openwhisk.core.entitlement._
import org.apache.openwhisk.core.entity.ActivationId.ActivationIdGenerator
import org.apache.openwhisk.core.loadBalancer.LoadBalancer

/**
 * A trait which defines a few services which a whisk microservice may rely on.
 */
trait WhiskServices {

  /** Whisk configuration object. */
  protected val whiskConfig: WhiskConfig

  /** An entitlement service to check access rights. */
  protected val entitlementProvider: EntitlementProvider

  /** A generator for new activation ids. */
  protected val activationIdFactory: ActivationIdGenerator

  /** A load balancing service that launches invocations. */
  protected val loadBalancer: LoadBalancer
}
