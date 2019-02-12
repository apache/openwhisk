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

package org.apache.openwhisk.core.database

import akka.stream.ActorMaterializer
import common.{StreamLogging, WskActorSystem}
import org.rogach.scallop.throwError
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import org.apache.openwhisk.core.cli.{Conf, WhiskAdmin}
import org.apache.openwhisk.core.database.test.DbUtils
import org.apache.openwhisk.core.entity.WhiskAuthStore

import scala.util.Random

trait WhiskAdminCliTestBase
    extends FlatSpec
    with WskActorSystem
    with DbUtils
    with StreamLogging
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with ScalaFutures
    with Matchers {

  implicit val materializer = ActorMaterializer()
  //Bring in sync the timeout used by ScalaFutures and DBUtils
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = dbOpTimeout)
  protected val authStore = WhiskAuthStore.datastore()

  //Ensure scalaop does not exit upon validation failure
  throwError.value = true

  override def afterEach(): Unit = {
    cleanup()
  }

  override def afterAll(): Unit = {
    println("Shutting down store connections")
    authStore.shutdown()
    super.afterAll()
  }

  protected def randomString(len: Int = 5): String = Random.alphanumeric.take(len).mkString

  protected def resultOk(args: String*): String =
    WhiskAdmin(new Conf(args.toSeq))
      .executeCommand()
      .futureValue
      .right
      .get

  protected def resultNotOk(args: String*): String =
    WhiskAdmin(new Conf(args.toSeq))
      .executeCommand()
      .futureValue
      .left
      .get
      .message
}
