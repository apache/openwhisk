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

package org.apache.openwhisk.core.aws.lambda
import common.WskActorSystem
import org.apache.openwhisk.common.TransactionId
import org.apache.openwhisk.core.aws.LambdaStoreProvider
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class LambdaStoreTest extends FlatSpec with Matchers with WskActorSystem with ScalaFutures {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 60.seconds)
  behavior of "invoke"

  it should "hello world" in {
    implicit val tid = TransactionId.testing
    val store = LambdaStoreProvider.makeStore()
    val body = """{
       |  "value": {
       |    "foo" : "bar"
       |  }
       |}""".stripMargin.parseJson.asJsObject
    val r = store.invoke("hello-world-custom-1", body).futureValue
    println(r.response.right.get.entity)
  }
}
