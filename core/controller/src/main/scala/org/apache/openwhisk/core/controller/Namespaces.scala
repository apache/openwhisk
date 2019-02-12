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
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.entitlement.Collection
import org.apache.openwhisk.core.entity.Identity

trait WhiskNamespacesApi extends Directives with AuthenticatedRouteProvider {

  protected val collection = Collection(Collection.NAMESPACES)
  protected val collectionOps = pathEndOrSingleSlash & get

  /** JSON response formatter. */
  import RestApiCommons.jsonDefaultResponsePrinter

  /**
   * Rest API for managing namespaces. Defines all the routes handled by this API. They are:
   *
   * GET  namespaces[/] -- gets namespace for authenticated user
   *
   * @param user the authenticated user for this route
   */
  override def routes(user: Identity)(implicit transid: TransactionId) = {
    (pathPrefix(collection.path) & collectionOps) {
      complete(OK, List(user.namespace.name))
    }
  }
}
