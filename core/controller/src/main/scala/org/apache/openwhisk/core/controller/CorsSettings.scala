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

import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.HttpMethods.{DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT}

/**
 * Defines the CORS settings for the REST APIs and Web Actions.
 */
protected[controller] object CorsSettings {

  trait RestAPIs {
    val allowOrigin = Defaults.allowOrigin
    val allowHeaders = Defaults.allowHeaders
    val allowMethods =
      `Access-Control-Allow-Methods`(GET, DELETE, POST, PUT, HEAD)
  }

  trait WebActions {
    val allowOrigin = Defaults.allowOrigin
    val allowHeaders = Defaults.allowHeaders
    val allowMethods = `Access-Control-Allow-Methods`(OPTIONS, GET, DELETE, POST, PUT, HEAD, PATCH)
  }

  object Defaults {
    val allowOrigin = `Access-Control-Allow-Origin`.*

    val allowHeaders = `Access-Control-Allow-Headers`(
      "Authorization",
      "Origin",
      "X-Requested-With",
      "Content-Type",
      "Accept",
      "User-Agent")
  }
}
