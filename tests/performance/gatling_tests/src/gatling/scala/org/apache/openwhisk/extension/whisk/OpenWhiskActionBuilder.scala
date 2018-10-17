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

package org.apache.openwhisk.extension.whisk

import io.gatling.core.Predef._
import io.gatling.core.action.Action
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioContext
import io.gatling.http.request.builder.{Http, HttpRequestBuilder}
import spray.json.DefaultJsonProtocol._
import spray.json._

case class OpenWhiskActionBuilderBase(requestName: Expression[String]) {

  implicit private val http = new Http(requestName)

  /** Call the `/api/v1`-endpoint of the specified system */
  def info() = {
    OpenWhiskActionBuilder(http.get("/api/v1"))
  }

  /**
   * Specify authentication data. This is needed to perform operations on namespaces or working with entities.
   *
   * @param uuid The UUID of the namespace
   * @param key The key of the namespace
   */
  def authenticate(uuid: Expression[String], key: Expression[String]) = {
    OpenWhiskActionBuilderWithNamespace(uuid, key)
  }
}

case class OpenWhiskActionBuilderWithNamespace(private val uuid: Expression[String],
                                               private val key: Expression[String],
                                               private val namespace: String = "_")(implicit private val http: Http) {

  /**
   * Specify on which namespace you want to perform any action.
   *
   * @param namespace The namespace you want to use.
   */
  def namespace(namespace: String) = {
    OpenWhiskActionBuilderWithNamespace(uuid, key, namespace)
  }

  /** List all namespaces you have access to, with your current authentication. */
  def list() = {
    OpenWhiskActionBuilder(http.get("/api/v1/namespaces").basicAuth(uuid, key))
  }

  /**
   * Perform any request against the actions-API. E.g. creating, invoking or deleting actions.
   *
   * @param actionName Name of the action in the Whisk-system.
   */
  def action(actionName: String) = {
    OpenWhiskActionBuilderWithAction(uuid, key, namespace, actionName)
  }
}

case class OpenWhiskActionBuilderWithAction(private val uuid: Expression[String],
                                            private val key: Expression[String],
                                            private val namespace: String,
                                            private val action: String)(implicit private val http: Http) {
  private val path: Expression[String] = s"/api/v1/namespaces/$namespace/actions/$action"

  /** Fetch the action from OpenWhisk */
  def get() = {
    OpenWhiskActionBuilder(http.get(path).basicAuth(uuid, key))
  }

  /** Delete the action from OpenWhisk */
  def delete() = {
    OpenWhiskActionBuilder(http.delete(path).basicAuth(uuid, key))
  }

  /**
   * Create the action in OpenWhisk.
   *
   * @param code The code of the action to create.
   * @param kind The kind of the action you want to create. Default is `nodejs:default`.
   * @param main Main method of your action. This is only needed for java actions.
   */
  def create(code: Expression[String], kind: Expression[String] = "nodejs:default", main: Expression[String] = "") = {

    val json: Expression[String] = session => {
      code(session).flatMap { c =>
        kind(session).flatMap { k =>
          main(session).map { m =>
            val exec = Map("kind" -> k, "code" -> c) ++ (if (m.size > 0) Map("main" -> m) else Map[String, String]())
            JsObject("exec" -> exec.toJson).compactPrint
          }
        }
      }
    }

    OpenWhiskActionBuilder(http.put(path).basicAuth(uuid, key).body(StringBody(json)))
  }

  /** Invoke the action. */
  def invoke() = {
    OpenWhiskActionBuilder(http.post(path).queryParam("blocking", "true").basicAuth(uuid, key))
  }
}

case class OpenWhiskActionBuilder(http: HttpRequestBuilder) extends ActionBuilder {
  override def build(ctx: ScenarioContext, next: Action): Action = {
    http.build(ctx, next)
  }
}
