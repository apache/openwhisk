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

import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import javax.management.ObjectName

trait ConfigMXBean {
  def getConfig(path: String, originComment: Boolean): String
}

object ConfigMXBean extends ConfigMXBean {
  val name = new ObjectName("org.apache.openwhisk:name=config")
  private val renderOptions =
    ConfigRenderOptions.defaults().setComments(false).setOriginComments(true).setFormatted(true).setJson(false)

  override def getConfig(path: String, originComment: Boolean): String =
    ConfigFactory.load().getConfig(path).root().render(renderOptions.setOriginComments(originComment))

  def register(): Unit = {
    ManagementFactory.getPlatformMBeanServer.registerMBean(ConfigMXBean, name)
  }

  def unregister(): Unit = {
    ManagementFactory.getPlatformMBeanServer.unregisterMBean(name)
  }
}
