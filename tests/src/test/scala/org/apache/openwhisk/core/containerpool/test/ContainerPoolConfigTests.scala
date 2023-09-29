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

package org.apache.openwhisk.core.containerpool.test

import org.apache.openwhisk.core.containerpool.ContainerPoolConfig
import org.apache.openwhisk.core.entity.ByteSize
import org.apache.openwhisk.core.entity.size.SizeInt
import org.junit.runner.RunWith
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration.DurationInt

@RunWith(classOf[JUnitRunner])
class ContainerPoolConfigTests extends FlatSpec with Matchers {

  def createPoolConfig(userMemory: ByteSize, userCpus: Option[Double] = None): ContainerPoolConfig = {
    ContainerPoolConfig(userMemory, 0.5, false, 2.second, 10.seconds, None, 1, 3, false, 1.second, 10, userCpus)
  }

  it should "calculate container cpu shares" in {
    val (userMemory, memoryLimit) = (2.GB, 256.MB)
    val poolConfig = createPoolConfig(userMemory)
    poolConfig.cpuShare(memoryLimit) shouldBe 128
  }

  it should "use min cpu shares when calculated container cpu shares is too low" in {
    val (userMemory, memoryLimit) = (1024.MB, 1.MB)
    val poolConfig = createPoolConfig(userMemory)
    poolConfig.cpuShare(memoryLimit) shouldBe 2 // calculated shares would be 1, but min is 2
  }

  it should "calculate container cpu limit" in {
    val (userMemory, memoryLimit, userCpus) = (2.GB, 256.MB, 2.0)
    val poolConfig = createPoolConfig(userMemory, Some(userCpus))
    poolConfig.cpuLimit(memoryLimit) shouldBe Some(0.25)
  }

  it should "correctly round container cpu limit" in {
    val (userMemory, memoryLimit, userCpus) = (768.MB, 256.MB, 2.0)
    val poolConfig = createPoolConfig(userMemory, Some(userCpus))
    poolConfig.cpuLimit(memoryLimit) shouldBe Some(0.66667) // calculated limit is 0.666..., rounded to 0.66667
  }

  it should "use min container cpu limit when calculated limit is too low" in {
    val (userMemory, memoryLimit, userCpus) = (1024.MB, 1.MB, 1.0)
    val poolConfig = createPoolConfig(userMemory, Some(userCpus))
    poolConfig.cpuLimit(memoryLimit) shouldBe Some(0.01) // calculated limit is 0.001, but min is 0.01
  }

  it should "return None for container cpu limit when userCpus is not set" in {
    val (userMemory, memoryLimit) = (2.GB, 256.MB)
    val poolConfig = createPoolConfig(userMemory)
    poolConfig.cpuLimit(memoryLimit) shouldBe None
  }

  it should "require userCpus to be greater than 0" in {
    assertThrows[IllegalArgumentException] {
      createPoolConfig(2.GB, Some(-1.0))
    }
  }
}
