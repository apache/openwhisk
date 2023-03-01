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

package org.apache.openwhisk.common.etcd

import common.WskActorSystem
import org.apache.openwhisk.core.etcd.{EtcdClient, EtcdConfig}
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContextExecutor
@RunWith(classOf[JUnitRunner])
class EtcdConfigTests extends FlatSpec with Matchers with WskActorSystem {
  behavior of "EtcdConfig"

  implicit val ece: ExecutionContextExecutor = actorSystem.dispatcher

  it should "create client when no auth is supplied through config" in {
    val config = EtcdConfig("localhost:2379", None, None)

    val client = EtcdClient(config)
    client.close()
  }

  it should "create client when auth is supplied through config" in {
    val config = EtcdConfig("localhost:2379", Some("username"), Some("password"))

    val client = EtcdClient(config)
    client.close()
  }

  it should "fail to create client when one of username or password is supplied in config" in {
    val config = EtcdConfig("localhost:2379", None, Some("password"))

    assertThrows[IllegalArgumentException](EtcdClient(config))
  }
}
