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

package org.apache.openwhisk.standalone

import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DockerVersionTests extends FlatSpec with Matchers {
  behavior of "DockerVersion"

  it should "parse docker version" in {
    val v = DockerVersion("18.09.2")
    v shouldBe DockerVersion(18, 9, 2)
  }

  it should "parse docker version from command output" in {
    val v = DockerVersion.fromVersionCommand("Docker version 18.09.2, build 624796")
    v shouldBe DockerVersion(18, 9, 2)
  }

  it should "compare 2 versions semantically" in {
    DockerVersion("17.09.2") should be < DockerVersion("18.09.2")
    DockerVersion("17.09.2") should be < DockerVersion("18.03.2")
    DockerVersion("17.09") should be < DockerVersion("18.03.2")
  }
}
