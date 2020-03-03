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

package org.apache.openwhisk.core.database.elasticsearch

import org.scalatest.FlatSpec
import org.apache.openwhisk.core.controller.test.WhiskAuthHelpers
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.database.test.behavior.ActivationStoreBehaviorBase
import org.apache.openwhisk.core.entity.{ActivationResponse, Parameters, WhiskActivation}
import org.testcontainers.elasticsearch.ElasticsearchContainer
import pureconfig.loadConfigOrThrow
import spray.json.{JsObject, JsString}

trait ElasticSearchActivationStoreBehaviorBase extends FlatSpec with ActivationStoreBehaviorBase {
  val imageName = loadConfigOrThrow[String]("whisk.elasticsearch.docker-image")
  val container = new ElasticsearchContainer(imageName)
  container.start()

  override def afterAll = {
    container.close()
    super.afterAll()
  }

  override def storeType = "ElasticSearch"

  val creds = WhiskAuthHelpers.newIdentity()
  override val context = UserContext(creds)

  override lazy val activationStore = {
    val storeConfig =
      ElasticSearchActivationStoreConfig("http", container.getHttpHostAddress, "unittest-%s", "fake", "fake")
    new ElasticSearchActivationStore(None, storeConfig, true)
  }

  // add result and annotations
  override def newActivation(ns: String, actionName: String, start: Long): WhiskActivation = {
    super
      .newActivation(ns, actionName, start)
      .copy(
        response = ActivationResponse.success(Some(JsObject("name" -> JsString("whisker")))),
        annotations = Parameters("database", "elasticsearch") ++ Parameters("type", "test"))
  }
}
