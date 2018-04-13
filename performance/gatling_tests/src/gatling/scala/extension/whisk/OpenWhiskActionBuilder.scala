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
import io.gatling.http.request.builder.{Http, HttpRequestBuilder}

case class OpenWhiskActionBuilderBase(requestName: Expression[String]) {

  implicit private val http = new Http(requestName)

  def info() = OpenWhiskActionBuilder(http.get("/api/v1"))
}

case class OpenWhiskActionBuilder(http: HttpRequestBuilder) extends ActionBuilder {
  override def build(ctx: ScenarioContext, next: Action): Action = {
    http.build(ctx, next)
  }
}
