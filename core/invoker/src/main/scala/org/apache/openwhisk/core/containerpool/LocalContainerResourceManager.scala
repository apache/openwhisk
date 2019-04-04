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

package org.apache.openwhisk.core.containerpool
import akka.actor.ActorRef
import org.apache.openwhisk.common.Logging
import org.apache.openwhisk.core.entity.ByteSize

class LocalContainerResourceManager(pool: ActorRef)(implicit logging: Logging) extends ContainerResourceManager {
  pool ! InitPrewarms //init prewarms immediately
  override def canLaunch(size: ByteSize,
                         poolMemory: Long,
                         poolConfig: ContainerPoolConfig,
                         prewarm: Boolean): Boolean = {
    prewarm || poolMemory + size.toMB <= poolConfig.userMemory.toMB //in this impl we do not restrict starting of prewarm based on pool capacity
  }
}
