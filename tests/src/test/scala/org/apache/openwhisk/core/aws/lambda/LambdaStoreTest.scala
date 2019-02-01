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
import org.apache.openwhisk.core.entity.test.ExecHelpers
import org.apache.openwhisk.core.entity.{DocRevision, EntityName, EntityPath, WhiskAction}
import org.junit.runner.RunWith
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}
import spray.json._

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class LambdaStoreTest extends FlatSpec with Matchers with WskActorSystem with ScalaFutures with ExecHelpers {
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 60.seconds)
  implicit val tid = TransactionId.testing
  behavior of "invoke"

  val helloWorld = """function main(params) {
                     |    greeting = 'hello new2, ' + params.payload + '!'
                     |    console.log(greeting);
                     |    return {payload: greeting}
                     |}""".stripMargin

  val store = LambdaStoreProvider.makeStore()

  it should "sanity check" in {
    val name = "hello-sanity-1"
    val ns = "test"
    val action = WhiskAction(EntityPath(ns), EntityName(name), jsDefault(helloWorld))
      .revision[WhiskAction](DocRevision("foo3"))
    val fqn = action.fullyQualifiedName(false)
    val la = store.createOrUpdateLambda(action).futureValue
    println(la)
    val body = """{
                 |  "value": {
                 |    "payload" : "bar"
                 |  }
                 |}""".stripMargin.parseJson.asJsObject
    val r = store.invokeLambda(la.get, body).futureValue
    println(r.response.right.get.entity)
    store.deleteLambda(fqn)
  }

}
