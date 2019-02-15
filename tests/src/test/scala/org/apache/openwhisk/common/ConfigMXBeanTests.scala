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

package org.apache.openwhisk.common
import java.lang.management.ManagementFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, Matchers}

@RunWith(classOf[JUnitRunner])
class ConfigMXBeanTests extends FlatSpec with Matchers {
  behavior of "ConfigMBean"

  it should "return config at path" in {
    ConfigMXBean.register()
    val config = ManagementFactory.getPlatformMBeanServer.invoke(
      ConfigMXBean.name,
      "getConfig",
      Array("whisk.spi", java.lang.Boolean.FALSE),
      Array("java.lang.String", "boolean"))
    config.asInstanceOf[String] should include("ArtifactStoreProvider")
    ConfigMXBean.unregister()
  }
}
