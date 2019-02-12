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

package org.apache.openwhisk.core.invoker.test

import common.StreamLogging
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import org.apache.openwhisk.core.invoker.InstanceIdAssigner

@RunWith(classOf[JUnitRunner])
class InstanceIdAssignerTests extends FlatSpec with Matchers with StreamLogging with BeforeAndAfterEach {
  behavior of "Id Assignment"

  private var zkServer: TestingServer = _

  override protected def beforeEach(): Unit = {
    zkServer = new TestingServer()
  }

  override protected def afterEach(): Unit = {
    zkServer.stop()
  }

  it should "assign fresh id" in {
    val assigner = new InstanceIdAssigner(zkServer.getConnectString)
    assigner.getId("foo") shouldBe 0
  }

  it should "reuse id if exists" in {
    val assigner = new InstanceIdAssigner(zkServer.getConnectString)
    assigner.getId("foo") shouldBe 0
    assigner.getId("bar") shouldBe 1
    assigner.getId("bar") shouldBe 1
  }

}
