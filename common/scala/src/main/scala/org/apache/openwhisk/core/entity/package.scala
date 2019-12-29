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

package org.apache.openwhisk.core

import org.apache.openwhisk.core.entity.ExecManifest.RuntimeManifestConfig
import org.apache.openwhisk.core.entity.size._
import pureconfig.ConfigReader
import pureconfig.module.reflect.ReflectConfigReaders

package object entity extends ReflectConfigReaders {
  implicit val manifestConfigReader: ConfigReader[RuntimeManifestConfig] = configReader2(RuntimeManifestConfig)
  implicit val concurrencyConfigReader: ConfigReader[ConcurrencyLimitConfig] = configReader3(ConcurrencyLimitConfig)
  implicit val memoryConfigReader: ConfigReader[MemoryLimitConfig] = configReader3(MemoryLimitConfig)
  implicit val logLimitConfigReader: ConfigReader[LogLimitConfig] = configReader3(LogLimitConfig)
  implicit val timeLimitConfigReader: ConfigReader[TimeLimitConfig] = configReader3(TimeLimitConfig)
  implicit val dbConfigReader: ConfigReader[DBConfig] = configReader4(DBConfig)
  implicit val actPayloadConfigReader: ConfigReader[ActivationEntityPayload] = configReader1(ActivationEntityPayload)
  implicit val actLimitConfigReader: ConfigReader[ActivationEntityLimitConf] = configReader2(ActivationEntityLimitConf)
}
