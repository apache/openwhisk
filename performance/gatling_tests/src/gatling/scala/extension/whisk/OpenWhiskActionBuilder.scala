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

package extension.whisk

import io.gatling.core.Predef._
import io.gatling.core.action.Action
import io.gatling.core.action.builder.ActionBuilder
import io.gatling.core.session.Expression
import io.gatling.core.structure.ScenarioContext
import io.gatling.core.util.Resource
import io.gatling.http.Predef._
import io.gatling.http.request.builder.{Http, HttpRequestBuilder}
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.io.Source
import scala.language.implicitConversions

case class OpenWhiskActionBuilderBase(requestName: Expression[String]) {

  implicit private val http = new Http(requestName)

  def info() = OpenWhiskActionBuilder(http.get("/api/v1"))
  def authenticate(uuid: String, key: String) = OpenWhiskActionBuilderWithNamespace(uuid, key)
}

case class OpenWhiskActionBuilderWithNamespace(private val uuid: String,
                                               private val key: String,
                                               private val namespace: String = "_")(implicit private val http: Http) {

  def namespace(namespace: String) = OpenWhiskActionBuilderWithNamespace(uuid, key, namespace)
  def actionInvoke(actionName: String, blocking: Boolean = false) = {
    OpenWhiskActionBuilderActionInvoke(
      http
        .post(s"/api/v1/namespaces/$namespace/actions/$actionName?blocking=${blocking.toString}")
        .basicAuth(uuid, key)
        .check(status.is(if (blocking) 200 else 202)))
  }
  def actionCreate(actionName: String, fileName: String, kind: String = "nodejs:default") =
    OpenWhiskActionBuilder(
      http
        .put(s"/api/v1/namespaces/$namespace/actions/$actionName")
        .basicAuth(uuid, key)
        .body(createActionCreateBody(fileName, kind)))
  def actionDelete(actionName: String) =
    OpenWhiskActionBuilder(http.delete(s"/api/v1/namespaces/$namespace/actions/$actionName").basicAuth(uuid, key))

  private def createActionCreateBody(fileName: String, kind: String) = {
    val code = Source.fromFile(Resource.feeder(fileName).get.file).mkString
    val json = JsObject("exec" -> JsObject("kind" -> JsString(kind), "code" -> JsString(code)))
    StringBody(json.compactPrint)
  }
}

case class OpenWhiskActionBuilderActionInvoke(http: HttpRequestBuilder, parameters: Map[String, String] = Map()) {

  def withParameters(key: String, value: String) =
    OpenWhiskActionBuilderActionInvoke(http, parameters ++ Map((key, value)))
  def build = OpenWhiskActionBuilder(http.body(StringBody(parameters.toJson.compactPrint)))
}

object OpenWhiskActionBuilderActionInvoke {
  implicit def toOpenWhiskActionBuilder(builder: OpenWhiskActionBuilderActionInvoke): OpenWhiskActionBuilder =
    builder.build
}

case class OpenWhiskActionBuilder(http: HttpRequestBuilder) extends ActionBuilder {
  override def build(ctx: ScenarioContext, next: Action): Action = {
    http.build(ctx, next)
  }
}
